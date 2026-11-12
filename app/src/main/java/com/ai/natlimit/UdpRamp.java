package com.ai.natlimit;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * UDP 会话数阶梯加压。
 *
 * 会话怎么造：每个会话用一个独立的 DatagramSocket（独立源端口），
 * 全部发往服务端的同一个目的端口（20120）。NAT 按五元组建表，
 * 源端口不同 = 会话不同。
 *
 * 为什么不用「一个 socket 打多个目的端口」：
 *   那样最省 fd，但需要服务端在 10000-12999 一整段端口上都有归属，
 *   否则内核回 ICMP port-unreachable，路由器收到就删表项，永远测不出。
 *   而云安全组只放行了 20120 一个端口，所以只能反过来：目的端口固定，源端口变化。
 *
 * 代价：每条会话占一个 fd，和 TCP 模式一样受本机 fd 上限约束。
 */
public final class UdpRamp {

    public static final class Config {
        public String host = "";
        public int port = 20120;
        public int start = 100;
        public double step = 1.15;
        public int maxTarget = 3000;
        public int holdSec = 15;
        public String statsUrl = "";
        public String statsToken = "";
        public String gwHost = "";
        public int probePort = 80;
        public int probeTimeoutMs = 1500;
    }

    public static final class Level {
        public int target;
        public int socksOpen;
        public int sent;
        public int sendFailed;
        public long sendMs;
        public int serverSessions = -1;
        public int serverPeak = -1;
        public double baseGw = -1, worstGw = -1;
        public int gwProbeFail = 0;
        public String verdict = "";
        public String detail = "";
        public boolean degraded() { return !verdict.isEmpty(); }
    }

    public interface Cb {
        void onLog(String msg);
        void onLevel(Level lv);
        void onDone(Level stopLevel, String summary);
    }

    private final Config cfg;
    private final Cb cb;
    private final List<DatagramSocket> socks = new ArrayList<>();
    private volatile boolean stop = false;
    private Thread worker;
    private int fdCap = 3000;

    public UdpRamp(Config cfg, Cb cb) { this.cfg = cfg; this.cb = cb; }

    public void start() {
        worker = new Thread(this::run, "udp-ramp");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        stop = true;
        closeAll();
        if (worker != null) worker.interrupt();
    }

    private void closeAll() {
        synchronized (socks) {
            for (DatagramSocket s : socks) {
                try { s.close(); } catch (Exception ignored) { }
            }
            socks.clear();
        }
    }

    private void run() {
        try {
            long[] lim = Limits.read();
            long raised = Limits.raise(32768);
            if (raised > 0) lim[0] = raised;
            fdCap = (int) Math.max(0, lim[0] - 40);
            cb.onLog("fd 上限 " + lim[0] + "，UDP 最多可建约 " + fdCap + " 条会话");

            double baseGw = TcpProbe.median(cfg.gwHost, cfg.probePort, cfg.probeTimeoutMs, 5);
            cb.onLog(String.format("基线 RTT：路由器 %s",
                    baseGw < 0 ? "无响应" : String.format("%.1f ms", baseGw)));

            int target = cfg.start;
            Level last = null;
            while (!stop) {
                if (target > cfg.maxTarget) target = cfg.maxTarget;
                Level lv = runLevel(target, baseGw);
                last = lv;
                cb.onLevel(lv);

                if (lv.degraded()) {
                    cb.onDone(lv, lv.verdict);
                    return;
                }
                if (target >= cfg.maxTarget) {
                    cb.onDone(lv, "跑完上限档位未发现劣化，UDP 会话承受力高于 " + cfg.maxTarget);
                    return;
                }
                int next = (int) Math.ceil(target * cfg.step);
                if (next <= target) next = target + 1;
                target = next;
                sleep(4000);
            }
        } catch (Throwable t) {
            cb.onLog("异常：" + t);
            cb.onDone(null, "异常中断：" + t);
        } finally {
            closeAll();
        }
    }

    private Level runLevel(int target, double baseGw) {
        Level lv = new Level();
        lv.target = target;
        lv.baseGw = baseGw;

        // 本机 fd 先到顶，直接判定，别赖路由器
        if (target > fdCap) {
            lv.socksOpen = socks.size();
            lv.verdict = "本机 fd 用尽（不是路由器的问题）";
            lv.detail = "目标 " + target + " 条，但本机 fd 上限只允许约 " + fdCap + " 条";
            return lv;
        }

        long t0 = System.currentTimeMillis();
        InetAddress addr;
        try {
            addr = InetAddress.getByName(cfg.host);
        } catch (Exception e) {
            lv.verdict = "服务端地址解析失败";
            lv.detail = String.valueOf(e);
            return lv;
        }

        // 补足 socket 到 target 个，每个发一个包
        synchronized (socks) {
            while (socks.size() < target && !stop) {
                DatagramSocket s;
                try {
                    s = new DatagramSocket();
                    s.setSendBufferSize(1 << 16);
                } catch (Exception e) {
                    lv.verdict = "本机 fd 用尽（不是路由器的问题）";
                    lv.detail = "第 " + (socks.size() + 1) + " 个 socket 创建失败：" + e;
                    return lv;
                }
                socks.add(s);
                try {
                    s.send(new DatagramPacket(new byte[1], 1, addr, cfg.port));
                    lv.sent++;
                } catch (Exception e) {
                    lv.sendFailed++;
                }
                if ((socks.size() & 31) == 0) sleep(5);
            }
            lv.socksOpen = socks.size();
        }
        lv.sendMs = System.currentTimeMillis() - t0;

        // 保持期：每秒重发一轮（UDP NAT 表项约 30s 老化），同时探测路由器
        long end = System.currentTimeMillis() + cfg.holdSec * 1000L;
        while (System.currentTimeMillis() < end && !stop) {
            sleep(1000);
            resend(addr);
            double g = TcpProbe.once(cfg.gwHost, cfg.probePort, cfg.probeTimeoutMs);
            if (g < 0) { if (!cfg.gwHost.isEmpty()) lv.gwProbeFail++; }
            else if (g > lv.worstGw) lv.worstGw = g;
        }

        sleep(600);
        if (!cfg.statsUrl.isEmpty()) {
            long[] s = StatsClient.fetchUdp(cfg.statsUrl, cfg.statsToken);
            lv.serverSessions = (int) s[0];
            lv.serverPeak = (int) s[1];
        }

        decide(lv);
        return lv;
    }

    private void resend(InetAddress addr) {
        DatagramPacket p = new DatagramPacket(new byte[1], 1, addr, cfg.port);
        synchronized (socks) {
            int i = 0;
            for (DatagramSocket s : socks) {
                if (stop) return;
                try { s.send(p); } catch (Exception ignored) { }
                if ((++i & 31) == 0) sleep(5);
            }
        }
    }

    private void decide(Level lv) {
        if (lv.sendFailed > 0) {
            lv.verdict = "UDP 发送失败";
            lv.detail = "目标 " + lv.target + "，失败 " + lv.sendFailed + " 次";
            return;
        }
        if (lv.gwProbeFail >= 2) {
            lv.verdict = "路由器无响应（管理页面已失联）";
            lv.detail = "保持期 " + lv.gwProbeFail + " 次探测失败";
            return;
        }
        if (lv.baseGw > 0 && lv.worstGw > lv.baseGw * 5 && lv.worstGw > lv.baseGw + 5) {
            lv.verdict = "路由器响应延迟劣化";
            lv.detail = String.format("最差 %.1f ms，基线 %.1f ms", lv.worstGw, lv.baseGw);
            return;
        }
        if (lv.serverSessions >= 0 && lv.serverSessions < lv.target * 0.85) {
            lv.verdict = "UDP 会话未全部建立";
            lv.detail = "目标 " + lv.target + "，服务端只看到 " + lv.serverSessions
                    + " 条（" + (100 * lv.serverSessions / Math.max(1, lv.target)) + "%）";
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
