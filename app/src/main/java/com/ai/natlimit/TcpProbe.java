package com.ai.natlimit;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 用一次 TCP connect 的耗时当作 RTT 探针。
 * 选 TCP 而不是 ICMP 的原因：
 *  1) 不需要 ping 二进制的权限；
 *  2) 连路由器的 80 端口，本身就等于「管理页面还响应吗」，正好对上「路由器卡死」的症状。
 */
public final class TcpProbe {
    private TcpProbe() {}

    /** @return 毫秒；失败返回 -1 */
    public static double once(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty()) return -1;
        Socket s = new Socket();
        long t0 = System.nanoTime();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (Exception e) {
            return -1;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** 多次探测取中位数，忽略失败样本 */
    public static double median(String host, int port, int timeoutMs, int times) {
        double[] v = new double[times];
        int n = 0;
        for (int i = 0; i < times; i++) {
            double r = once(host, port, timeoutMs);
            if (r >= 0) v[n++] = r;
        }
        if (n == 0) return -1;
        java.util.Arrays.sort(v, 0, n);
        return v[n / 2];
    }
}
