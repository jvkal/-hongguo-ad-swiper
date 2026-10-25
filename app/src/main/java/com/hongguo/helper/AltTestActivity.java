package com.hongguo.helper;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 隐藏的测试触发入口(必须在 onCreate 里立刻 finish: activity 是 noHistory + NoDisplay)。
 *
 * adb shell am start -n com.hongguo.helper/.AltTestActivity --es mode exporter
 * adb shell am start -n com.hongguo.helper/.AltTestActivity --es mode pair --es code 123456
 * adb shell am start -n com.hongguo.helper/.AltTestActivity --es mode swipe
 * adb shell am start -n com.hongguo.helper/.AltTestActivity --es mode alt
 *
 * 结果: logcat 里看 [SELFTEST] 标记(纯 ASCII, PowerShell 过滤不乱码), 同时 Toast 一下。
 */
public class AltTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String mode = getIntent().getStringExtra("mode") == null
                ? "alt" : getIntent().getStringExtra("mode");
        final String code = getIntent().getStringExtra("code");
        final int port = getIntent().getIntExtra("port", -1);
        final Context app = getApplicationContext();

        Log.i("HGHelper", "[ALT] AltTestActivity mode=" + mode
                + " instance=" + (SkipService.instance != null ? "yes" : "no"));

        if ("alt".equals(mode)) {
            SkipService.triggerAltTest();
            finish();
            return;
        }
        if ("ab".equals(mode)) {
            SkipService.triggerAbTest();
            finish();
            return;
        }

        new Thread(() -> {
            String r;
            switch (mode) {
                case "exporter":
                    r = TlsSelfTest.run(app);
                    break;
                case "pair":
                    if (code == null || code.length() != 6) {
                        r = "fail:need --es code <6digit>";
                    } else if (port > 0) {
                        r = AdbShell.pair(app, port, code);   // 显式端口(弹窗里显示的)
                    } else {
                        r = AdbShell.pairCodeOnly(app, code); // mDNS 自动发现
                    }
                    break;
                case "swipe":
                    r = swipeTest(app);
                    break;
                default:
                    r = "fail:unknown mode=" + mode;
            }
            Log.i("HGHelper", "[SELFTEST] mode=" + mode + " result=" + r);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> Toast.makeText(app, mode + ": " + r, Toast.LENGTH_LONG).show());
        }, "selftest").start();

        finish();
    }

    /** 走内嵌 ADB 真执行一次上滑, 证明 shell 通道可用 */
    public static String swipeTest(Context app) {
        if (!AdbShell.connected()) {
            CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = {false};
            AdbShell.reconnect(app, b -> {
                ok[0] = Boolean.TRUE.equals(b);
                latch.countDown();
            });
            try {
                latch.await(12, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            if (!ok[0]) return "fail:not-connected(pair first)";
        }
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        int cx = dm.widthPixels / 2;
        int y1 = (int) (dm.heightPixels * 0.76);
        int y2 = (int) (dm.heightPixels * 0.18);
        String out = AdbShell.exec(app,
                "input swipe " + cx + " " + y1 + " " + cx + " " + y2 + " 350");
        return out == null
                ? "fail:exec-null"
                : "ok swipe " + cx + "," + y1 + "->" + cx + "," + y2;
    }
}
