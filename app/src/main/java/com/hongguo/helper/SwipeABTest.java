package com.hongguo.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;

/**
 * 测试用: 同一广告页上交替执行
 *   A) dispatchGesture(无障碍注入)
 *   B) adb input swipe(shell 注入)
 * 参数完全一致, 统计成功率。
 * 由 SkipService 触发(testSwipeAB), 结果打到 logcat + 文件。
 */
public class SwipeABTest {

    private static final String TAG = "HGHelper";

    public interface Callback {
        void onRound(int round, String mode, boolean dispatched);
    }

    /**
     * 执行一轮测试: 先 A 后 B, 每轮间隔 intervalMs
     */
    public static void run(AccessibilityService svc, AdbShellRef adbRef, int rounds, long intervalMs,
                           int fromY, int toY, int duration, final Callback cb) {
        final int w = svc.getResources().getDisplayMetrics().widthPixels;
        final int h = svc.getResources().getDisplayMetrics().heightPixels;
        final int x1 = w / 2;
        final int y1 = (int) (h * fromY / 100f);
        final int y2 = (int) (h * toY / 100f);

        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] round = {0};

        Runnable task = new Runnable() {
            @Override
            public void run() {
                int r = round[0];
                if (r >= rounds) {
                    Log.i(TAG, "[ABTEST] 测试结束, 共 " + rounds + " 轮");
                    return;
                }
                boolean useA = (r % 2 == 0); // 偶数轮 A, 奇数轮 B
                String mode = useA ? "A-无障碍" : "B-ADB";
                boolean ok;
                if (useA) {
                    Path path = new Path();
                    path.moveTo(x1, y1);
                    path.lineTo(x1, y2);
                    GestureDescription g = new GestureDescription.Builder()
                            .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                            .build();
                    ok = svc.dispatchGesture(g, null, null);
                } else {
                    String cmd = String.format(java.util.Locale.US,
                            "input swipe %d %d %d %d %d", x1, y1, x1, y2, duration);
                    String res = adbRef == null ? null : adbRef.exec(cmd);
                    ok = res != null;
                }
                Log.i(TAG, "[ABTEST] 第" + (r + 1) + "轮 " + mode + " 发送=" + ok
                        + " 坐标(" + x1 + "," + y1 + ")->(" + x1 + "," + y2 + ") 时长" + duration + "ms");
                if (cb != null) cb.onRound(r + 1, mode, ok);
                round[0]++;
                handler.postDelayed(this, intervalMs);
            }
        };
        handler.post(task);
    }

    /** ADB 执行器抽象, 避免直接依赖 Kotlin object */
    public interface AdbShellRef {
        String exec(String cmd);
    }
}
