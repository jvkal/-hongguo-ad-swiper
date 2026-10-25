package com.hongguo.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍手势/滚动 通道实测。
 * 目的: 验证红果广告页到底是"按事件来源拦截"还是"按手势形状过滤"。
 * 全部纯无障碍, 不需要无线调试。
 */
public class AltSwipeTest {

    public static final String TAG = "HGHelper";
    public static final String ACTION = "com.hongguo.helper.ALTTEST";

    private static volatile boolean running = false;

    public interface Cb {
        void onStep(String name, boolean dispatched);
    }

    /** 受控测试: 先确认前台是红果, 每种方式后截图自验证 */
    public static void run(AccessibilityService svc, int fromY, int toY, long gapMs, Cb cb) {
        // 无状态: 每次触发都强制执行(服务可能被系统重启, 不留锁)

        // 前置检查: 前台必须是红果
        String fg = svc.getRootInActiveWindow() != null
                ? String.valueOf(svc.getRootInActiveWindow().getPackageName()) : "null";
        Log.i(TAG, "[ALT] === 受控测试开始, 前台包名=" + fg + " ===");
        if (!"com.phoenix.read".equals(fg)) {
            Log.e(TAG, "[ALT] !! 前台不是红果(是 " + fg + "), 测试终止。请先停在红果广告页再触发");
            if (cb != null) cb.onStep("已中止:前台不是红果(" + fg + ")", false);
            return;
        }

        final int w = svc.getResources().getDisplayMetrics().widthPixels;
        final int h = svc.getResources().getDisplayMetrics().heightPixels;
        final float x = w / 2f;
        final int y1 = (int) (h * fromY / 100f);
        final int y2 = (int) (h * toY / 100f);
        final Handler handler = new Handler(Looper.getMainLooper());

        final List<Runnable> steps = new ArrayList<>();

        // C1: 分3段连续滑动
        steps.add(() -> {
            GestureDescription.Builder b = new GestureDescription.Builder();
            float seg = (y1 - y2) / 3f;
            Path p1 = new Path(); p1.moveTo(x, y1); p1.lineTo(x, y1 - seg);
            Path p2 = new Path(); p2.moveTo(x, y1 - seg); p2.lineTo(x, y1 - seg * 2);
            Path p3 = new Path(); p3.moveTo(x, y1 - seg * 2); p3.lineTo(x, y2);
            b.addStroke(new GestureDescription.StrokeDescription(p1, 0, 150));
            b.addStroke(new GestureDescription.StrokeDescription(p2, 160, 150, true));
            b.addStroke(new GestureDescription.StrokeDescription(p3, 320, 150));
            boolean ok = svc.dispatchGesture(b.build(), null, null);
            Log.i(TAG, "[ALT] C1-三段连续滑动 发送=" + ok);
            if (cb != null) cb.onStep("C1-三段连续滑动", ok);
        });

        // C2: 长按400ms后拖动
        steps.add(() -> {
            Path p = new Path();
            p.moveTo(x, y1);
            p.lineTo(x, y1 + 20);   // 先按着不动
            p.lineTo(x, y1);        // 回到起点
            p.lineTo(x, y2);        // 再拖动
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 900))
                    .build();
            boolean ok = svc.dispatchGesture(g, null, null);
            Log.i(TAG, "[ALT] C2-长按后拖动 发送=" + ok);
            if (cb != null) cb.onStep("C2-长按后拖动", ok);
        });

        // C3: 慢速滑动 1200ms
        steps.add(() -> {
            Path p = new Path();
            p.moveTo(x, y1);
            p.lineTo(x, y2);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 1200))
                    .build();
            boolean ok = svc.dispatchGesture(g, null, null);
            Log.i(TAG, "[ALT] C3-慢速滑动1200ms 发送=" + ok);
            if (cb != null) cb.onStep("C3-慢速滑动", ok);
        });

        // D: ACTION_SCROLL_FORWARD
        steps.add(() -> {
            boolean ok = false;
            try {
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                AccessibilityNodeInfo target = findScrollable(root, 0);
                if (target != null) {
                    ok = target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    Log.i(TAG, "[ALT] D-节点滚动 发送=" + ok + " 节点=" + target.getClassName());
                } else {
                    Log.i(TAG, "[ALT] D-节点滚动 没有找到可滚动节点");
                }
            } catch (Exception e) {
                Log.w(TAG, "[ALT] D 异常: " + e);
            }
            if (cb != null) cb.onStep("D-节点滚动", ok);
        });

        // E: 对可滚动节点做普通滑动(排除"节点本身拒绝手势"的情况)
        steps.add(() -> {
            Path p = new Path();
            p.moveTo(x, y1);
            p.lineTo(x, y2);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 300))
                    .build();
            boolean ok = svc.dispatchGesture(g, null, null);
            Log.i(TAG, "[ALT] E-普通滑动对照 发送=" + ok);
            if (cb != null) cb.onStep("E-普通滑动(对照)", ok);
        });

        // 顺序执行: 每种方式后等 2.5 秒截图自验证
        final android.os.Handler h2 = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int i = 0; i < steps.size(); i++) {
            final int idx = i;
            final String nm = names.get(i);
            h2.postDelayed(() -> {
                boolean wasAd = isAdShowing(svc);
                Log.i(TAG, "[ALT] >>> " + nm + " 执行前: 广告页=" + wasAd);
                steps.get(idx).run();
                h2.postDelayed(() -> {
                    boolean nowAd = isAdShowing(svc);
                    String verdict = wasAd && !nowAd ? "✅ 有效! 广告被划走"
                            : (!wasAd ? "⚠️ 执行前就不是广告页(可能已划走或不在广告页)" : "❌ 无效(广告还在)");
                    Log.i(TAG, "[ALT] <<< " + nm + " 结果: " + verdict
                            + " (执行前=" + wasAd + ", 执行后=" + nowAd + ")");
                    if (cb != null) cb.onStep(nm + " → " + verdict, nowAd);
                }, 2500L);
            }, idx * gapMs);
        }

        h2.postDelayed(() -> Log.i(TAG, "[ALT] === 全部通道测试结束 ==="),
                steps.size() * gapMs + 4000);
    }

    /**
     * 当前是否是广告页: 截屏 + 跑一次 ZNCC 模板匹配。
     * 不依赖服务自检状态, 每种方式都能准确判定。
     */
    private static boolean isAdShowing(AccessibilityService svc) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final boolean[] ad = {false};
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, svc.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult r) {
                            try {
                                android.graphics.Bitmap hw = android.graphics.Bitmap
                                        .wrapHardwareBuffer(r.getHardwareBuffer(), null);
                                android.graphics.Bitmap soft = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                hw.recycle();
                                ad[0] = com.hongguo.helper.SkipService.matchAdIn(soft);
                                soft.recycle();
                            } catch (Throwable t) {
                                Log.w(TAG, "[ALT] 截图判读异常: " + t);
                            } finally {
                                try {
                                    r.getHardwareBuffer().close();
                                } catch (Throwable ignored) {
                                }
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int e) {
                            Log.w(TAG, "[ALT] 截图失败 code=" + e);
                            latch.countDown();
                        }
                    });
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            return ad[0];
        } catch (Throwable t) {
            Log.w(TAG, "[ALT] isAdShowing 异常: " + t);
            return false;
        }
    }

    private static final List<String> names = java.util.Arrays.asList(
            "C1-三段连续滑动", "C2-长按后拖动", "C3-慢速滑动1200ms",
            "D-节点滚动", "E-普通滑动(对照)");

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 30) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo r = findScrollable(c, depth + 1);
            if (r != null) return r;
        }
        return null;
    }
}
