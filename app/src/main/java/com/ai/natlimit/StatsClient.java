package com.ai.natlimit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 极简 HTTP GET，取服务端 /stats 的 current / peak。不引第三方库。 */
public final class StatsClient {
    private StatsClient() {}

    /** @return {current, peak}，失败返回 {-1,-1} */
    public static long[] fetch(String baseUrl, String token) {
        String url = baseUrl.replaceAll("/+$", "") + "/stats?token=" + token;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code != 200) return new long[]{-1, -1};
            String body = read(c.getInputStream());
            long cur = pick(body, "\"current\":");
            long peak = pick(body, "\"peak\":");
            return new long[]{cur, peak};
        } catch (Exception e) {
            return new long[]{-1, -1};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static long pick(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return -1;
        int j = i + key.length();
        int k = j;
        while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-')) k++;
        try { return Long.parseLong(json.substring(j, k)); } catch (Exception e) { return -1; }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }
}
