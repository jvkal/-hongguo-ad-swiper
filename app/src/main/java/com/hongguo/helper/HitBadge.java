package com.hongguo.helper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 命中角标: 服务判定为广告页并上滑时, 在屏幕右上角闪一个白色小圆点,
 * 2.5 秒后自动消失。供用户肉眼确认"刚才那次识别划走了", 不用翻日志。
 * 需要"显示在其他应用上层"权限; 没授权时静默不显示(不影响功能)。
 */
public class HitBadge {

    /** 角标显示时长(毫秒) */
    private static final long SHOW_MS = 2500L;

    private final Context ctx;
    private final WindowManager wm;
    private View current;
    private final Runnable hideRun = this::hide;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    public HitBadge(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 右上角显示白色小圆点, 已在显示则重新计时 */
    public void flash() {
        try {
            if (!android.provider.Settings.canDrawOverlays(ctx)) return;
            main.removeCallbacks(hideRun);
            if (current == null) {
                current = buildBadge();
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = 40;
                lp.y = 120;
                wm.addView(current, lp);
            }
            main.postDelayed(hideRun, SHOW_MS);
        } catch (Throwable ignored) {
            current = null;
        }
    }

    private void hide() {
        try {
            if (current != null) {
                wm.removeView(current);
                current = null;
            }
        } catch (Throwable ignored) {
            current = null;
        }
    }

    /** 白色实心圆 + 描边, 半透明背景, 尺寸约 36dp */
    private View buildBadge() {
        int size = (int) (ctx.getResources().getDisplayMetrics().density * 36);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageDrawable(new Drawable() {
            @Override
            public void draw(Canvas c) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.WHITE);
                c.drawCircle(c.getWidth() / 2f, c.getHeight() / 2f, c.getWidth() * 0.32f, p);
                p.setColor(Color.argb(70, 255, 255, 255));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(c.getWidth() * 0.06f);
                c.drawCircle(c.getWidth() / 2f, c.getHeight() / 2f, c.getWidth() * 0.42f, p);
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        });
        iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        return iv;
    }
}
