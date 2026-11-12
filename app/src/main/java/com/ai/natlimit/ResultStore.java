package com.ai.natlimit;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 每级结果先落本地，路由器挂了也不丢。 */
public final class ResultStore {
    private ResultStore() { }

    public static File save(Context ctx, String header, List<String> rows, String summary) {
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base == null) base = ctx.getFilesDir();
            File dir = new File(base, "results");
            if (!dir.exists() && !dir.mkdirs()) return null;

            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File csv = new File(dir, "nlt-" + ts + ".csv");

            StringBuilder sb = new StringBuilder();
            sb.append('\ufeff');          // BOM，Excel 打开中文不乱码
            sb.append(header).append('\n');
            for (String r : rows) sb.append(r).append('\n');
            if (summary != null) {
                sb.append('\n');
                for (String line : summary.split("\n")) sb.append("# ").append(line).append('\n');
            }
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(csv), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            return csv;
        } catch (Exception e) {
            return null;
        }
    }
}
