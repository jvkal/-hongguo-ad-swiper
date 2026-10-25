package com.hongguo.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import android.app.RemoteInput;

/**
 * 通知栏填配对码: 用户在任何界面(包括系统配对弹窗)下拉通知 → 点"填写配对码"
 * → 系统弹出输入框 → 输入 6 位码 → 回传 → 自动 mDNS 找端口并配对。
 */
public class PairInputReceiver extends BroadcastReceiver {

    private static final String KEY_CODE = "pair_code";
    private static final String ACTION_INPUT = "com.hongguo.helper.PAIR_INPUT";
    private static final String CH = "hg_pair";
    private static final int ID = 2001;

    /** 发出"填配对码"通知(带直接回复输入框) */
    public static void showNotification(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CH, "配对",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }

            RemoteInput ri = new RemoteInput.Builder(KEY_CODE)
                    .setLabel("输入6位配对码")
                    .build();

            Intent i = new Intent(ctx, PairInputReceiver.class);
            i.setAction(ACTION_INPUT);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            Notification.Action action = new Notification.Action.Builder(null, "填写配对码", pi)
                    .addRemoteInput(ri)
                    .build();

            Notification n = new Notification.Builder(ctx,
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ? CH : null)
                    .setContentTitle("红果助手 · 配对")
                    .setContentText("去开发者选项点「使用配对码配对设备」, 然后回来点下方按钮填码")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .addAction(action)
                    .build();
            nm.notify(ID, n);
        } catch (Throwable t) {
            Log.e("HGHelper", "配对通知失败: " + t);
        }
    }

    static void cancel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_INPUT.equals(intent.getAction())) return;
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;
        CharSequence cs = results.getCharSequence(KEY_CODE);
        if (cs == null) return;
        String code = cs.toString().trim();
        Log.i("HGHelper", "[PAIR] 通知栏收到配对码: " + code);

        Context ctx = context.getApplicationContext();
        // 后台线程: mDNS 找端口 + 配对 + 连接, 结果更新通知
        new Thread(() -> {
            String r;
            try {
                r = AdbShell.pairCodeOnly(ctx, code);
            } catch (Throwable t) {
                r = "fail:" + t;
            }
            boolean ok = r.startsWith("ok");
            Log.i("HGHelper", "[PAIR] 通知栏配对结果: " + r);
            if (ok) {
                AdbShell.reconnect(ctx, ok2 -> updateResult(ctx, true, ok2));
            } else {
                updateResult(ctx, false, false);
            }
        }).start();
    }

    private static void updateResult(Context ctx, boolean paired, boolean connected) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String title = paired && connected ? "🎉 配对+连接成功, 全部完成!"
                    : paired ? "已配对, 但连接失败(检查无线调试开关)" : "❌ 配对失败(码过期或弹窗已关)";
            Notification n = new Notification.Builder(ctx,
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ? CH : null)
                    .setContentTitle(title)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID + 1, n);
        } catch (Throwable ignored) {
        }
    }
}
