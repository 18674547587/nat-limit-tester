package com.ai.natlimit;

import android.app.Activity;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements TcpRamp.Cb, UdpRamp.Cb {

    private TextView tvStatus, tvLog, tvResult;
    private EditText etHost, etPort, etToken, etStart, etStep, etMax, etHold, etWindow,
            etGw, etOnu, etProbePort;
    private Button btnStartTcp, btnStartUdp, btnStop;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TcpRamp tcpRamp;
    private UdpRamp udpRamp;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private final List<String> rows = new ArrayList<>();
    private final StringBuilder logBuf = new StringBuilder();
    private final StringBuilder tableBuf = new StringBuilder();
    private long[] fdLimits = {-1, -1};
    private String gwIp = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvResult = findViewById(R.id.tvResult);
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etToken = findViewById(R.id.etToken);
        etStart = findViewById(R.id.etStart);
        etStep = findViewById(R.id.etStep);
        etMax = findViewById(R.id.etMax);
        etHold = findViewById(R.id.etHold);
        etWindow = findViewById(R.id.etWindow);
        etGw = findViewById(R.id.etGw);
        etOnu = findViewById(R.id.etOnu);
        etProbePort = findViewById(R.id.etProbePort);
        btnStartTcp = findViewById(R.id.btnStartTcp);
        btnStartUdp = findViewById(R.id.btnStartUdp);
        btnStop = findViewById(R.id.btnStop);

        btnStartTcp.setOnClickListener(v -> startTcp());
        btnStartUdp.setOnClickListener(v -> startUdp());
        btnStop.setOnClickListener(v -> stopTest());

        probeDevice();
    }

    private void probeDevice() {
        new Thread(() -> {
            long[] lim = Limits.read();
            long raised = Limits.raise(32768);
            if (raised > 0) lim[0] = raised;
            fdLimits = lim;

            gwIp = gatewayIp();
            String ssid = currentSsid();

            StringBuilder sb = new StringBuilder();
            sb.append("fd 上限      : soft=").append(lim[0])
              .append("  hard=").append(lim[1])
              .append(Limits.available() ? "" : "  (native 未加载)")
              .append('\n');
            sb.append("TCP 理论上限 : 约 ").append(Math.max(0, lim[0] - 40)).append(" 条（1 连接 = 1 fd）\n");
            sb.append("UDP 理论上限 : 不受 fd 限制（1 个 socket 打多个目的端口）\n");
            sb.append("默认网关     : ").append(gwIp.isEmpty() ? "未获取" : gwIp).append('\n');
            sb.append("WiFi         : ").append(ssid).append('\n');
            sb.append("提示         : WiFi 会引入噪声，手机尽量靠近路由器");

            ui.post(() -> {
                tvStatus.setText(sb.toString());
                if (!gwIp.isEmpty() && etGw.getText().toString().trim().isEmpty()) {
                    etGw.setText(gwIp);
                }
            });
        }, "probe").start();
    }

    private String gatewayIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return "";
            int g = d.gateway;
            return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (Throwable t) {
            return "";
        }
    }

    private String currentSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null || wm.getConnectionInfo() == null) return "未知";
            String s = wm.getConnectionInfo().getSSID();
            return s == null ? "未知" : s.replace("\"", "");
        } catch (Throwable t) {
            return "未知";
        }
    }

    private boolean prepare() {
        String host = etHost.getText().toString().trim();
        if (host.isEmpty()) {
            appendLog("请先填写服务端 IP");
            return false;
        }
        rows.clear();
        logBuf.setLength(0);
        tableBuf.setLength(0);
        tvResult.setText("");
        acquireLocks();
        btnStartTcp.setEnabled(false);
        btnStartUdp.setEnabled(false);
        btnStop.setEnabled(true);
        return true;
    }

    private void startTcp() {
        if (!prepare()) return;
        TcpRamp.Config c = new TcpRamp.Config();
        c.host = etHost.getText().toString().trim();
        c.port = parseInt(etPort, 20120);
        c.start = parseInt(etStart, 50);
        c.step = parseDouble(etStep, 1.15);
        c.maxTarget = parseInt(etMax, 1200);
        c.holdSec = parseInt(etHold, 15);
        c.window = parseInt(etWindow, 200);
        c.gwHost = etGw.getText().toString().trim();
        c.onuHost = etOnu.getText().toString().trim();
        c.probePort = parseInt(etProbePort, 80);
        c.statsToken = etToken.getText().toString().trim();
        c.statsUrl = "http://" + c.host + ":" + c.port;

        if (c.start < 1) c.start = 1;
        if (c.maxTarget < c.start) c.maxTarget = c.start;
        if (c.holdSec < 3) c.holdSec = 3;
        if (c.window < 1) c.window = 1;

        appendLog(String.format(Locale.US, "TCP 开始：%d→%d，步进 %.2f，保持 %ds",
                c.start, c.maxTarget, c.step, c.holdSec));
        tcpRamp = new TcpRamp(c, this);
        tcpRamp.start();
    }

    private void startUdp() {
        if (!prepare()) return;
        UdpRamp.Config c = new UdpRamp.Config();
        c.host = etHost.getText().toString().trim();
        c.port = parseInt(etPort, 20120);
        c.start = parseInt(etStart, 100);
        c.step = parseDouble(etStep, 1.15);
        c.maxTarget = parseInt(etMax, 3000);
        c.holdSec = parseInt(etHold, 15);
        c.gwHost = etGw.getText().toString().trim();
        c.probePort = parseInt(etProbePort, 80);
        c.statsToken = etToken.getText().toString().trim();
        c.statsUrl = "http://" + c.host + ":" + c.port;

        if (c.start < 1) c.start = 1;
        if (c.maxTarget < c.start) c.maxTarget = c.start;
        if (c.holdSec < 3) c.holdSec = 3;

        appendLog(String.format(Locale.US, "UDP 开始：%d→%d，步进 %.2f，保持 %ds，目的端口 %d",
                c.start, c.maxTarget, c.step, c.holdSec, c.port));
        udpRamp = new UdpRamp(c, this);
        udpRamp.start();
    }

    private void stopTest() {
        if (tcpRamp != null) tcpRamp.stop();
        if (udpRamp != null) udpRamp.stop();
        appendLog("已急停，正在断开全部连接");
        finishUi();
    }

    private void finishUi() {
        btnStartTcp.setEnabled(true);
        btnStartUdp.setEnabled(true);
        btnStop.setEnabled(false);
        releaseLocks();
    }

    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nlt:test");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) { }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nlt:test");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(30 * 60 * 1000L);
            }
        } catch (Throwable ignored) { }
    }

    private void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
    }

    // ---------- TCP 回调 ----------

    @Override
    public void onLog(String msg) { ui.post(() -> appendLog(msg)); }

    private void appendLog(String msg) {
        logBuf.append(msg).append('\n');
        if (logBuf.length() > 20000) logBuf.delete(0, logBuf.length() - 20000);
        tvLog.setText(logBuf.toString());
    }

    @Override
    public void onLevel(TcpRamp.Level lv) {
        StringBuilder errs = new StringBuilder();
        for (Map.Entry<String, Integer> e : lv.errors.entrySet()) {
            if (errs.length() > 0) errs.append(' ');
            errs.append(e.getKey()).append('×').append(e.getValue());
        }
        String row = String.format(Locale.US,
                "%5d | 累计 %5d 新建 %4d 失败 %3d | 建连 %5dms | 路由 %s 光猫 %s | 服务端 %d/%d | %s",
                lv.target, lv.alive, lv.ok, lv.failed, lv.buildMs,
                rtt(lv.worstGw), rtt(lv.worstOnu), lv.serverCurrent, lv.serverPeak,
                lv.degraded() ? "⚠ " + lv.verdict : "ok");

        rows.add(String.format(Locale.US, "%d,%d,%d,%d,%d,%s,%s,%s,%d,%d,%s,%s",
                lv.target, lv.alive, lv.ok, lv.failed, lv.buildMs,
                f(lv.worstGw), f(lv.worstOnu),
                errs.toString().replace(",", ";"),
                lv.serverCurrent, lv.serverPeak,
                lv.degraded() ? "DEGRADED" : "OK",
                lv.detail.replace(",", ";")));

        ui.post(() -> {
            tableBuf.append(row).append('\n');
            tvResult.setText(tableBuf.toString());
        });
    }

    @Override
    public void onDone(TcpRamp.Level stop, String summary) {
        finish("TCP", stop == null ? -1 : stop.target,
                stop == null ? "" : stop.detail, summary,
                "target,alive,new_ok,new_failed,build_ms,worst_rtt_gw,worst_rtt_onu,errors,server_current,server_peak,verdict,detail");
    }

    // ---------- UDP 回调 ----------

    @Override
    public void onLevel(UdpRamp.Level lv) {
        String row = String.format(Locale.US,
                "%5d | 会话 %5d 本轮发 %5d 失败 %3d | 发送 %5dms | 路由 %s | 服务端会话 %d/%d | %s",
                lv.target, lv.socksOpen, lv.sent, lv.sendFailed, lv.sendMs,
                rtt(lv.worstGw), lv.serverSessions, lv.serverPeak,
                lv.degraded() ? "⚠ " + lv.verdict : "ok");

        rows.add(String.format(Locale.US, "%d,%d,%d,%d,%s,%d,%d,%s,%s",
                lv.target, lv.sent, lv.sendFailed, lv.sendMs,
                f(lv.worstGw), lv.serverSessions, lv.serverPeak,
                lv.degraded() ? "DEGRADED" : "OK",
                lv.detail.replace(",", ";")));

        ui.post(() -> {
            tableBuf.append(row).append('\n');
            tvResult.setText(tableBuf.toString());
        });
    }

    @Override
    public void onDone(UdpRamp.Level stop, String summary) {
        finish("UDP", stop == null ? -1 : stop.target,
                stop == null ? "" : stop.detail, summary,
                "target,sent,send_failed,send_ms,worst_rtt_gw,server_sessions,server_peak,verdict,detail");
    }

    private void finish(String mode, int target, String detail, String summary, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append("结论：").append(summary).append('\n');
        if (target > 0) {
            sb.append("触发档位：").append(target).append(mode.equals("TCP") ? " 条连接" : " 条会话").append('\n');
            if (detail != null && !detail.isEmpty()) sb.append("细节：").append(detail).append('\n');
        }
        sb.append("建议：等 2 分钟让 NAT 表项老化后再测");

        File f = ResultStore.save(this, header, rows, sb.toString());

        ui.post(() -> {
            appendLog(summary);
            if (f != null) appendLog("已保存：" + f.getAbsolutePath());
            tvResult.setText(tableBuf + "\n" + sb);
            finishUi();
        });
    }

    private static String rtt(double v) { return v < 0 ? "-" : String.format(Locale.US, "%.0fms", v); }
    private static String f(double v) { return v < 0 ? "" : String.format(Locale.US, "%.1f", v); }

    private static int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    private static double parseDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    @Override
    protected void onDestroy() {
        if (tcpRamp != null) tcpRamp.stop();
        if (udpRamp != null) udpRamp.stop();
        releaseLocks();
        super.onDestroy();
    }
}
