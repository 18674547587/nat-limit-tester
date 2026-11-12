package com.ai.natlimit;

import android.app.Activity;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements TcpRamp.Cb {

    private TextView tvStatus, tvLog, tvResult;
    private EditText etHost, etPort, etToken, etStart, etStep, etMax, etHold, etWindow,
            etGw, etOnu, etProbePort;
    private Button btnStart, btnStop;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TcpRamp ramp;
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
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> startTest());
        btnStop.setOnClickListener(v -> stopTest());

        probeDevice();
    }

    /** 开机自检：fd 上限、网关、WiFi 状态 */
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
            sb.append("理论可建连接 : 约 ").append(Math.max(0, lim[0] - 40)).append(" 条")
              .append("（要减掉已占用的 fd）\n");
            sb.append("默认网关     : ").append(gwIp.isEmpty() ? "未获取" : gwIp).append('\n');
            sb.append("WiFi         : ").append(ssid).append('\n');
            sb.append("提示         : WiFi 测试会引入无线噪声，手机尽量靠近路由器");

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

    private void startTest() {
        String host = etHost.getText().toString().trim();
        if (host.isEmpty()) {
            appendLog("请先填写服务端 IP");
            return;
        }
        rows.clear();
        logBuf.setLength(0);
        tableBuf.setLength(0);
        tvResult.setText("");

        TcpRamp.Config c = new TcpRamp.Config();
        c.host = host;
        c.port = parseInt(etPort, 80);
        c.start = parseInt(etStart, 50);
        c.step = parseDouble(etStep, 1.15);
        c.maxTarget = parseInt(etMax, 1200);
        c.holdSec = parseInt(etHold, 15);
        c.window = parseInt(etWindow, 200);
        c.gwHost = etGw.getText().toString().trim();
        c.onuHost = etOnu.getText().toString().trim();
        c.probePort = parseInt(etProbePort, 80);
        c.statsToken = etToken.getText().toString().trim();
        c.statsUrl = "http://" + host + ":" + c.port;   // 与 hold 复用同一端口

        if (c.start < 1) c.start = 1;
        if (c.maxTarget < c.start) c.maxTarget = c.start;
        if (c.holdSec < 3) c.holdSec = 3;
        if (c.window < 1) c.window = 1;

        acquireLocks();
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);

        appendLog(String.format(Locale.US,
                "开始：目标 %d→%d，步进 %.2f，每级保持 %ds，窗口 %d",
                c.start, c.maxTarget, c.step, c.holdSec, c.window));

        ramp = new TcpRamp(c, this);
        ramp.start();
    }

    private void stopTest() {
        if (ramp != null) ramp.stop();
        appendLog("已急停，正在断开全部连接");
        btnStart.setEnabled(true);
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

    // ---------- TcpRamp.Cb ----------

    @Override
    public void onLog(String msg) {
        ui.post(() -> appendLog(msg));
    }

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
                "%5d | 成功 %5d 失败 %4d | 建连 %5dms | 路由 %s 光猫 %s | 服务端 %d/%d | %s",
                lv.target, lv.ok, lv.failed, lv.buildMs,
                rtt(lv.worstGw), rtt(lv.worstOnu), lv.serverCurrent, lv.serverPeak,
                lv.degraded() ? "⚠ " + lv.verdict : "ok");

        rows.add(String.format(Locale.US,
                "%d,%d,%d,%d,%s,%s,%s,%d,%d,%s,%s",
                lv.target, lv.ok, lv.failed, lv.buildMs,
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
        StringBuilder sb = new StringBuilder();
        sb.append("结论：").append(summary).append('\n');
        if (stop != null) {
            sb.append("触发档位：").append(stop.target).append(" 条\n");
            if (!stop.detail.isEmpty()) sb.append("细节：").append(stop.detail).append('\n');
        }
        sb.append("建议：等 2 分钟让 NAT 表项老化后再测，别连着测");

        File f = ResultStore.save(this,
                "target,ok,failed,build_ms,worst_rtt_gw,worst_rtt_onu,errors,server_current,server_peak,verdict,detail",
                rows, sb.toString());

        ui.post(() -> {
            appendLog(summary);
            if (f != null) appendLog("已保存：" + f.getAbsolutePath());
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            tvResult.setText(tableBuf + "\n" + sb);
            releaseLocks();
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
        if (ramp != null) ramp.stop();
        releaseLocks();
        super.onDestroy();
    }
}
