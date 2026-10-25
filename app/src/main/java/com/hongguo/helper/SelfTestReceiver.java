package com.hongguo.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 无界面触发自检/配对。
 *
 * 关键点: AltTestActivity 那种“启动 Activity”的触发方式会把系统配对弹窗挤到后台,
 * 弹窗一消失配对端口就关闭, 于是报 “连不上端口”。广播不会改变前台窗口,
 * 因此可以在弹窗开着的时候直接配对。
 *
 * adb shell am broadcast -n com.hongguo.helper/.SelfTestReceiver --es mode pair --es code 123456 --ei port 37421
 * adb shell am broadcast -n com.hongguo.helper/.SelfTestReceiver --es mode exporter
 * adb shell am broadcast -n com.hongguo.helper/.SelfTestReceiver --es mode connect
 * adb shell am broadcast -n com.hongguo.helper/.SelfTestReceiver --es mode swipe
 */
public class SelfTestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final String mode = intent.getStringExtra("mode") == null
                ? "exporter" : intent.getStringExtra("mode");
        final String code = intent.getStringExtra("code");
        final int port = intent.getIntExtra("port", -1);

        Log.i("HGHelper", "[SELFTEST-START] mode=" + mode + " port=" + port);

        new Thread(() -> {
            String r;
            switch (mode) {
                case "exporter":
                    r = TlsSelfTest.run(app);
                    break;
                case "pair":
                    if (code == null || code.length() != 6) {
                        r = "fail:need code <6digit>";
                    } else if (port > 0) {
                        r = AdbShell.pair(app, port, code);
                    } else {
                        r = AdbShell.pairCodeOnly(app, code);
                    }
                    break;
                case "connect":
                    AdbShell.reconnect(app, ok -> Log.i("HGHelper",
                            "[SELFTEST] mode=connect result=" + (Boolean.TRUE.equals(ok) ? "ok" : "fail")));
                    return;
                case "swipe":
                    r = AltTestActivity.swipeTest(app);
                    break;
                case "acc":
                    r = AdbShell.enableAccessibility(app);
                    break;
                default:
                    r = "fail:unknown mode=" + mode;
            }
            Log.i("HGHelper", "[SELFTEST] mode=" + mode + " result=" + r);
        }, "selftest").start();
    }
}
