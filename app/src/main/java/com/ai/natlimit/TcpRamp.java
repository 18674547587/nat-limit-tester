package com.ai.natlimit;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 阶梯加压核心。
 *
 * 策略：小步递增 + 每级保持 + 三级判定（本机 fd / 建连失败 / 延迟劣化）。
 * 一旦劣化立即全断，不追极限 —— 目标是找「还能舒服用」的临界点，不是把路由器打死。
 */
public final class TcpRamp {

    public static final class Config {
        public String host = "";
        public int port = 80;
        public int start = 50;
        public double step = 1.15;
        public int maxTarget = 1200;
        public int holdSec = 15;
        public int window = 200;
        public int connectTimeoutMs = 8000;
        public String gwHost = "";
        public String onuHost = "";
        public int probePort = 80;
        public int probeTimeoutMs = 1500;
        public String statsUrl = "";   // http://server:9002
        public String statsToken = "";
    }

    public static final class Level {
        public int target;
        public int ok;
        public int failed;
        public long buildMs;
        public final Map<String, Integer> errors = new LinkedHashMap<>();
        public double baseGw = -1, baseOnu = -1;
        public double worstGw = -1, worstOnu = -1, worstSrv = -1;
        public int gwProbeFail = 0, onuProbeFail = 0;
        public int serverCurrent = -1, serverPeak = -1;
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
    private final List<Socket> live = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private volatile Thread worker;

    public TcpRamp(Config cfg, Cb cb) {
        this.cfg = cfg;
        this.cb = cb;
    }

    public int liveCount() { return live.size(); }

    public void start() {
        worker = new Thread(this::run, "tcp-ramp");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        stop.set(true);
        closeAll();
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private void closeAll() {
        synchronized (live) {
            for (Socket s : live) {
                try { s.close(); } catch (Exception ignored) { }
            }
            live.clear();
        }
    }

    private void run() {
        try {
            long[] lim = Limits.read();
            long raised = Limits.raise(32768);
            cb.onLog("fd 上限：soft=" + lim[0] + " hard=" + lim[1]
                    + (raised > 0 ? "，已提升到 " + raised : "，提升失败"));

            double baseGw = TcpProbe.median(cfg.gwHost, cfg.probePort, cfg.probeTimeoutMs, 5);
            double baseOnu = TcpProbe.median(cfg.onuHost, cfg.probePort, cfg.probeTimeoutMs, 5);
            cb.onLog(String.format("基线 RTT：路由器 %s / 光猫 %s",
                    fmt(baseGw), fmt(baseOnu)));

            int target = cfg.start;
            Level last = null;
            while (!stop.get()) {
                if (target > cfg.maxTarget) {
                    target = cfg.maxTarget;
                }
                Level lv = runLevel(target, baseGw, baseOnu);
                last = lv;
                cb.onLevel(lv);

                if (lv.degraded()) {
                    closeAll();
                    cb.onDone(lv, lv.verdict);
                    return;
                }
                if (target >= cfg.maxTarget) {
                    closeAll();
                    cb.onDone(lv, "跑完上限档位未发现劣化，实际承受力高于 " + cfg.maxTarget);
                    return;
                }
                int next = (int) Math.ceil(target * cfg.step);
                if (next <= target) next = target + 1;
                target = next;

                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        } catch (Throwable t) {
            cb.onLog("异常：" + t);
            cb.onDone(null, "异常中断：" + t);
        } finally {
            closeAll();
        }
    }

    private Level runLevel(int target, double baseGw, double baseOnu) {
        Level lv = new Level();
        lv.target = target;
        lv.baseGw = baseGw;
        lv.baseOnu = baseOnu;

        int need = target - live.size();
        if (need < 0) need = 0;

        long t0 = System.currentTimeMillis();
        if (need > 0) {
            int threads = Math.min(cfg.window, Math.max(1, need));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Runnable> tasks = new ArrayList<>(need);
            for (int i = 0; i < need; i++) tasks.add(() -> connectOne(lv));
            for (Runnable r : tasks) pool.submit(r);
            pool.shutdown();
            try {
                while (!pool.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (stop.get()) break;
                }
            } catch (InterruptedException ignored) { }
        }
        lv.buildMs = System.currentTimeMillis() - t0;

        // 保持期：每秒探测一次，记最差 RTT 与无响应次数
        long end = System.currentTimeMillis() + cfg.holdSec * 1000L;
        while (System.currentTimeMillis() < end && !stop.get()) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            sample(lv);
        }
        sample(lv);

        // 与服务端对账（服务端才是权威）
        if (!cfg.statsUrl.isEmpty()) {
            long[] s = StatsClient.fetch(cfg.statsUrl, cfg.statsToken);
            lv.serverCurrent = (int) s[0];
            lv.serverPeak = (int) s[1];
        }

        decide(lv);
        return lv;
    }

    private void sample(Level lv) {
        double g = TcpProbe.once(cfg.gwHost, cfg.probePort, cfg.probeTimeoutMs);
        double o = TcpProbe.once(cfg.onuHost, cfg.probePort, cfg.probeTimeoutMs);
        double s = TcpProbe.once(cfg.host, cfg.port, cfg.probeTimeoutMs);

        if (g < 0) { if (!cfg.gwHost.isEmpty()) lv.gwProbeFail++; }
        else if (g > lv.worstGw) lv.worstGw = g;

        if (o < 0) { if (!cfg.onuHost.isEmpty()) lv.onuProbeFail++; }
        else if (o > lv.worstOnu) lv.worstOnu = o;

        if (s >= 0 && s > lv.worstSrv) lv.worstSrv = s;
    }

    private void connectOne(Level lv) {
        if (stop.get()) return;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(cfg.host, cfg.port), cfg.connectTimeoutMs);
            s.setSoLinger(true, 0);   // 关闭时发 RST，NAT 会话立即释放
            s.setTcpNoDelay(true);
            synchronized (live) { live.add(s); }
            synchronized (lv) { lv.ok++; }
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
            String k = classify(e);
            synchronized (lv) {
                lv.failed++;
                Integer c = lv.errors.get(k);
                lv.errors.put(k, c == null ? 1 : c + 1);
            }
        }
    }

    private static String classify(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("Too many open files")) return "EMFILE 本机fd用尽";
        if (e instanceof SocketTimeoutException) return "TIMEOUT 超时";
        if (e instanceof NoRouteToHostException) return "NOROUTE 无路由";
        if (e instanceof ConnectException) {
            if (m.contains("refused")) return "REFUSED 拒绝";
            if (m.contains("unreachable")) return "UNREACH 不可达";
            return "CONN 连接失败";
        }
        return "OTHER " + e.getClass().getSimpleName();
    }

    /** 三级判定：本机 fd > 建连失败 > 延迟劣化 */
    private void decide(Level lv) {
        for (Map.Entry<String, Integer> e : lv.errors.entrySet()) {
            if (e.getKey().startsWith("EMFILE")) {
                lv.verdict = "本机 fd 用尽（不是路由器的问题）";
                lv.detail = "第 " + lv.target + " 条档位出现 " + e.getValue()
                        + " 次 EMFILE，说明是 App 进程文件描述符到顶";
                return;
            }
        }
        if (lv.failed > 0) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : lv.errors.entrySet()) {
                sb.append(e.getKey()).append("×").append(e.getValue()).append("  ");
            }
            lv.verdict = "建连开始失败";
            lv.detail = "目标 " + lv.target + "，成功 " + lv.ok + "，失败 " + lv.failed
                    + " → " + sb.toString().trim();
            return;
        }
        if (lv.gwProbeFail >= 2) {
            lv.verdict = "路由器无响应（管理页面已失联）";
            lv.detail = "保持期 " + lv.gwProbeFail + " 次探测失败，路由器可能已卡死";
            return;
        }
        if (lv.onuProbeFail >= 2) {
            lv.verdict = "光猫无响应";
            lv.detail = "保持期 " + lv.onuProbeFail + " 次探测失败";
            return;
        }
        if (lv.baseGw > 0 && lv.worstGw > lv.baseGw * 5 && lv.worstGw > lv.baseGw + 5) {
            lv.verdict = "路由器响应延迟劣化";
            lv.detail = String.format("最差 %.1f ms，基线 %.1f ms（%.1f 倍）",
                    lv.worstGw, lv.baseGw, lv.worstGw / lv.baseGw);
            return;
        }
        if (lv.baseOnu > 0 && lv.worstOnu > lv.baseOnu * 5 && lv.worstOnu > lv.baseOnu + 5) {
            lv.verdict = "光猫响应延迟劣化";
            lv.detail = String.format("最差 %.1f ms，基线 %.1f ms", lv.worstOnu, lv.baseOnu);
        }
    }

    private static String fmt(double v) { return v < 0 ? "无响应" : String.format("%.1f ms", v); }
}
