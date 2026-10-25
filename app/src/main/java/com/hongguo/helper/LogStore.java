package com.hongguo.helper;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 简易日志: 记录每次检测与滑动, 供界面上翻看。
 * 存在应用私有目录, 只保留最近 N 行。
 */
public class LogStore {

    private static final String FILE_NAME = "diagnose.log";
    private static final int MAX_LINES = 400;

    private final File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public LogStore(Context ctx) {
        file = new File(ctx.getFilesDir(), FILE_NAME);
    }

    public synchronized void add(String msg) {
        try {
            String line = fmt.format(new Date()) + "  " + msg + "\n";
            FileWriter fw = new FileWriter(file, true);
            fw.write(line);
            fw.close();
            trimIfNeeded();
        } catch (Exception ignored) {
        }
    }

    private void trimIfNeeded() {
        try {
            if (file.length() < 60_000) return;
            BufferedReader br = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = br.readLine()) != null) total++;
            br.close();

            br = new BufferedReader(new FileReader(file));
            int skip = Math.max(0, total - MAX_LINES);
            int i = 0;
            while ((line = br.readLine()) != null) {
                if (i++ >= skip) sb.append(line).append('\n');
            }
            br.close();

            FileWriter fw = new FileWriter(file, false);
            fw.write(sb.toString());
            fw.close();
        } catch (Exception ignored) {
        }
    }

    public synchronized String readAll() {
        try {
            if (!file.exists()) return "(暂无日志)";
            BufferedReader br = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.length() == 0 ? "(暂无日志)" : sb.toString();
        } catch (Exception e) {
            return "(读取失败: " + e.getMessage() + ")";
        }
    }

    public synchronized void clear() {
        try {
            FileWriter fw = new FileWriter(file, false);
            fw.write("");
            fw.close();
        } catch (Exception ignored) {
        }
    }

    /** 统计: 滑动次数 / 成功次数 */
    public synchronized int[] stats() {
        int swipes = 0;
        int ok = 0;
        try {
            if (!file.exists()) return new int[]{0, 0};
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("[SWIPE]")) swipes++;
                if (line.contains("[SWIPE_OK]")) ok++;
            }
            br.close();
        } catch (Exception ignored) {
        }
        return new int[]{swipes, ok};
    }
}
