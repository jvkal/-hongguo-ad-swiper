package com.hongguo.helper;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 监听通知栏, 自动捕获系统"无线调试配对码"弹窗的信息。
 *
 * Android 弹出配对对话框时, 通知栏会显示类似:
 *   "无线调试配对码 482913"
 *   "Pairing code: 482913"
 * 抓到 6 位码和端口后直接回调。
 */
public class PairingNotificationListener extends NotificationListenerService {

    public static final String TAG = "HGHelper";

    /** 配对码: 6 位数字 */
    private static final Pattern CODE_6 = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    /** 配对端口: 5 位数字(配对端口通常 3xxxx-4xxxx) */
    private static final Pattern PORT_5 = Pattern.compile("(?<!\\d)(\\d{4,5})(?!\\d)");

    public interface Callback {
        void onPairingInfo(int port, String code);
    }

    private static volatile Callback callback;
    private static volatile long lastFiredAt = 0L;

    public static void setCallback(Callback cb) {
        callback = cb;
        lastFiredAt = 0L;
    }

    public static void clearCallback() {
        callback = null;
    }

    /** 是否已授权通知读取 */
    public static boolean isEnabled(Context ctx) {
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            if (TextUtils.isEmpty(enabled)) return false;
            return enabled.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
        handle(sbn);
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) return;
        Callback cb = callback;
        if (cb == null) return;

        // 防抖: 3 秒内不重复触发
        long now = System.currentTimeMillis();
        if (now - lastFiredAt < 3000L) return;

        String pkg = sbn.getPackageName();
        // 系统/开发者选项/无线调试相关的包名(不同 ROM 不一样)
        if (pkg != null && (pkg.contains("systemui")
                || pkg.contains("development")
                || pkg.contains("settings")
                || pkg.contains("wirelesssettings")   // ColorOS/realme 无线调试
                || pkg.contains("wireless adb")
                || pkg.contains("adbd")
                || pkg.contains("android"))) {
            // 命中白名单
        } else {
            return;
        }

        String text = extractText(sbn.getNotification());
        if (TextUtils.isEmpty(text)) return;
        Log.i(TAG, "[PAIR-NOTICE] 抓到通知: " + text);

        Matcher cm = CODE_6.matcher(text);
        if (!cm.find()) return;
        String code = cm.group(1);
        // 放宽关键词: 只要来自系统包名且含 6 位数字, 就认为是配对码
        // (不同 ROM 文案差异大, 靠白名单包名已经够安全)
        Log.i(TAG, "[PAIR-NOTICE] 命中 6 位码: " + code + " 全文: " + text);

        // 端口: 从通知里再找 4-5 位数字; 找不到就用 -1 让配对流程用 mDNS
        int port = -1;
        Matcher pm = PORT_5.matcher(text.replace(code, "      "));
        if (pm.find()) port = Integer.parseInt(pm.group(1));

        lastFiredAt = now;
        Log.i(TAG, "[PAIR-NOTICE] 解析结果: 配对码=" + code + " 端口=" + port);
        cb.onPairingInfo(port, code);
    }

    private String extractText(Notification n) {
        if (n == null) return null;
        StringBuilder sb = new StringBuilder();
        try {
            if (n.extras != null) {
                CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence b = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence bt = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                CharSequence st = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                if (t != null) sb.append(t).append(' ');
                if (b != null) sb.append(b).append(' ');
                if (bt != null) sb.append(bt).append(' ');
                if (st != null) sb.append(st).append(' ');
            }
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }
}
