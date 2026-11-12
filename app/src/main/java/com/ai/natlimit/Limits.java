package com.ai.natlimit;

/** 进程 fd 上限自检与提升。未 root 只能把 soft 提到 hard。 */
public final class Limits {
    private static boolean libOk = true;
    static {
        try { System.loadLibrary("natlimit"); }
        catch (Throwable t) { libOk = false; }
    }
    private static native long[] nativeLimits();
    private static native long nativeRaise(long want);

    public static boolean available() { return libOk; }

    /** @return {soft, hard}，失败返回 {-1,-1} */
    public static long[] read() {
        if (libOk) {
            try { return nativeLimits(); } catch (Throwable ignored) { }
        }
        return readFromProc();
    }

    /** 读 /proc/self/limits 兜底，格式：Max open files  1024  32768  files */
    private static long[] readFromProc() {
        long soft = -1, hard = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/limits"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Max open files")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 5) {
                        soft = Long.parseLong(p[3]);
                        hard = Long.parseLong(p[4]);
                    }
                    break;
                }
            }
        } catch (Exception ignored) { }
        return new long[]{soft, hard};
    }

    /** 尝试把 soft 提到 want（不超过 hard），返回提升后的 soft，失败 -1 */
    public static long raise(long want) {
        if (!libOk) return -1;
        try { return nativeRaise(want); } catch (Throwable t) { return -1; }
    }

    /** 当前已打开的 fd 数量（不含 native lib 自身） */
    public static int openFds() {
        try {
            String[] l = new java.io.File("/proc/self/fd").list();
            return l == null ? -1 : l.length;
        } catch (Exception e) {
            return -1;
        }
    }
}
