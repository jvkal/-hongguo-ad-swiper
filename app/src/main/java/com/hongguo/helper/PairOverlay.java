package com.hongguo.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 配对浮窗: 悬浮在系统"配对码"弹窗之上, 边看边输, 不用切走应用。
 * 需要"显示在其他应用上层"权限。
 */
public class PairOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private View root;
    private final java.util.function.Consumer<String[]> onPair;

    public PairOverlay(Context ctx, java.util.function.Consumer<String[]> onPair) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.onPair = onPair;
    }

    private EditText portInput;
    private EditText codeInput;

    /** 自动填入配对信息 */
    public void fillPairing(int port, String code) {
        try {
            if (root == null) return;
            if (port > 0 && portInput != null) portInput.setText(String.valueOf(port));
            if (codeInput != null) codeInput.setText(code);
        } catch (Exception e) {
            android.util.Log.e("HGHelper", "填入失败: " + e);
        }
    }

    public boolean canShow() {
        return android.provider.Settings.canDrawOverlays(ctx);
    }

    public void show() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#EE212121"));
        box.setPadding(24, 20, 24, 20);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(ctx);
        title.setText("红果助手 · 配对");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        box.addView(title);

        TextView tip = new TextView(ctx);
        tip.setText("把上面弹窗里的端口和6位码填进来");
        tip.setTextColor(Color.parseColor("#AAAAAA"));
        tip.setTextSize(12);
        box.addView(tip);

        final EditText port = new EditText(ctx);
        port.setHint("端口(可留空,自动识别)");
        port.setTextColor(Color.WHITE);
        port.setHintTextColor(Color.parseColor("#888888"));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(port);
        this.portInput = port;

        final EditText code = new EditText(ctx);
        code.setHint("6位配对码(必填)");
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(Color.parseColor("#888888"));
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(code);
        this.codeInput = code;

        LinearLayout row = new LinearLayout(ctx);
        Button doPair = new Button(ctx);
        doPair.setText("配对");
        doPair.setOnClickListener(v -> {
            String p = port.getText().toString().trim();
            String c = code.getText().toString().trim();
            if (!c.isEmpty()) {
                onPair.accept(new String[]{p, c});
                hide();
            }
        });
        row.addView(doPair);
        Button close = new Button(ctx);
        close.setText("关闭");
        close.setOnClickListener(v -> hide());
        row.addView(close);
        box.addView(row);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.y = 200;

        try {
            wm.addView(box, lp);
            root = box;
        } catch (Exception e) {
            android.util.Log.e("HGHelper", "浮窗失败: " + e);
        }
    }

    public void hide() {
        try {
            if (root != null) {
                wm.removeView(root);
                root = null;
            }
        } catch (Exception ignored) {
        }
    }

    public boolean showing() {
        return root != null;
    }
}
