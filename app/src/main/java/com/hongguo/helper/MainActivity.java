package com.hongguo.helper;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 主界面 = 两步向导(大按钮) + 高级设置(默认折叠)。
 *
 * ① 开启无障碍模式 : 通道就绪时一键开启(ADB写系统设置), 否则跳系统设置
 * ② ADB 无线调试   : 未配对→填6位码; 已配对未连接→点重连; 已连接→✅
 * 两步全绿 = 自动跳广告完全可用(Shizuku 只是备用, 不需要)。
 */
public class MainActivity extends Activity {

    private Config cfg;
    private LogStore logStore;
    private TextView statusView;
    private TextView liveView;
    private TextView logView;
    private TextView step1, step2;
    private TextView channelView;      // ① 下方的动态说明文字
    private TextView wifiNote;         // Wi-Fi 前置提示(通道未连接时显示)
    private LinearLayout pairArea;     // 未配对: 码输入区
    private EditText codeInput;
    private Button btnPair, btnReconnect, btnDevOpts, btnAcc, btnTest;
    private LinearLayout advPanel;     // 高级设置(默认折叠)
    private Button advToggle;
    private LinearLayout appsList;     // 监控应用列表(每应用独立规则)
    private TextView regionLabel, thresholdLabel, swipeLabel;
    private PairOverlay overlay;

    /** A/B 测试: 让 SkipService 在广告页执行对照滑动 */
    private void startAbTest() {
        android.content.Intent i = new android.content.Intent(SkipService.ACTION_AB_TEST);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        statusView.setText("已触发 A/B 测试, 请切到红果广告页守着");
    }

    /** 通道测试: 5 种无障碍通道 */
    private void startAltTest() {
        try {
            SkipService svc = SkipService.instance;
            if (svc != null) {
                svc.runAltTestPublic();
                statusView.setText("已触发, 请切到红果广告页守着");
                return;
            }
        } catch (Throwable ignored) {
        }
        android.content.Intent i = new android.content.Intent(SkipService.ACTION_ALT_TEST);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        statusView.setText("已触发(兜底通道), 请切到红果广告页守着");
    }

    /** 浮窗提交配对 */
    private void doPair(String code) {
        if (code == null || code.length() != 6) {
            statusView.setText("请填 6 位配对码");
            return;
        }
        statusView.setText("配对中(请保持系统配对弹窗开着)...");
        new Thread(() -> {
            String r;
            try {
                r = AdbShell.pairCodeOnly(this, code);
            } catch (Throwable t) {
                r = "fail:异常 " + t;
            }
            final boolean ok = r != null && r.startsWith("ok");
            final String msg = r;
            runOnUiThread(() -> {
                statusView.setText(ok ? "✅ 配对成功! 正在连接..." : ("❌ " + msg));
                if (ok) AdbShell.reconnect(this, ok2 -> runOnUiThread(() -> {
                    statusView.setText(ok2 ? "🎉 配对+连接成功!" : "已配对但连接失败: 确认无线调试开着, 点「重连」");
                    refreshSteps();
                }));
                refreshSteps();
            });
        }).start();
    }

    private final android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context ctx, Intent intent) {
            String msg = intent.getStringExtra(SkipService.EXTRA_MSG);
            if (msg != null) liveView.setText("最近动作: " + msg);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Config(this);
        logStore = new LogStore(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 80, 48, 48);
        scroll.addView(root);

        // ---------- 标题 ----------
        TextView title = new TextView(this);
        title.setText("红果助手");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(17);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 24, 0, 24);
        root.addView(statusView);

        // ================= ① ADB 无线调试 =================
        step2 = addStep(root, "① 连接 ADB 无线调试(先连 Wi-Fi)");
        channelView = note(root, "");
        wifiNote = note(root, "前置: 手机必须先连上 Wi-Fi(无线调试走 Wi-Fi 网络, 流量不行)。");
        wifiNote.setVisibility(View.GONE);

        // 未配对时: 码输入区
        pairArea = new LinearLayout(this);
        pairArea.setOrientation(LinearLayout.VERTICAL);
        btnDevOpts = new Button(this);
        btnDevOpts.setText("去开发者选项(无线调试)");
        btnDevOpts.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });
        pairArea.addView(btnDevOpts);

        codeInput = new EditText(this);
        codeInput.setHint("填弹窗上的 6 位配对码");
        codeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeInput.setTextSize(18);
        pairArea.addView(codeInput);

        btnPair = new Button(this);
        btnPair.setTextSize(18);
        btnPair.setText("配 对");
        btnPair.setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            doPair(code);
        });
        pairArea.addView(btnPair);
        root.addView(pairArea);

        // 已配对未连接时: 重连
        btnReconnect = new Button(this);
        btnReconnect.setTextSize(18);
        btnReconnect.setText("连接 / 重连");
        btnReconnect.setOnClickListener(v -> {
            statusView.setText("连接中...");
            AdbShell.reconnect(this, ok ->
                    runOnUiThread(() -> {
                        statusView.setText(ok ? "✅ ADB 通道已连接" : "❌ 连不上: 先去开发者选项确认无线调试开关是开的");
                        refreshSteps();
                    }));
        });
        root.addView(btnReconnect);

        // ================= ② 无障碍 =================
        step1 = addStep(root, "② 开启无障碍模式");
        TextView accTip = note(root, "作用: 读取画面判断广告页。走 ADB 时一键开启, 不用进系统设置翻菜单。");
        accTip.setVisibility(View.GONE);

        btnAcc = new Button(this);
        btnAcc.setTextSize(18);
        btnAcc.setOnClickListener(v -> enableAccessibility());
        root.addView(btnAcc);

        // ================= 就绪区 =================
        btnTest = new Button(this);
        btnTest.setText("测试: 上滑一次(会真的滑动屏幕)");
        btnTest.setOnClickListener(v -> {
            statusView.setText("测试中: 屏幕将被上滑一次...");
            new Thread(() -> {
                String r = AltTestActivity.swipeTest(getApplicationContext());
                runOnUiThread(() -> statusView.setText("测试结果: " + r));
            }).start();
        });
        root.addView(btnTest);

        // ================= 监控应用(每应用独立规则) =================
        root.addView(sectionLabel("监控应用(在这些App里自动跳广告)"));
        TextView appTip = new TextView(this);
        appTip.setText("在列表里的App前台时才会检测跳广告; 内置广告识别(商业词/上滑提示/直播红标), 无需手动配规则。");
        appTip.setTextSize(12);
        appTip.setPadding(8, 0, 8, 8);
        root.addView(appTip);

        appsList = new LinearLayout(this);
        appsList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appsList);

        Button addApp = new Button(this);
        addApp.setText("＋ 添加应用");
        addApp.setOnClickListener(v -> showAppDialog(-1));
        root.addView(addApp);

        liveView = new TextView(this);
        liveView.setText("最近动作: (暂无)");
        liveView.setTextSize(15);
        liveView.setPadding(20, 36, 20, 36);
        liveView.setBackgroundColor(0xFFF0F0F0);
        root.addView(liveView);

        // ================= 高级设置(默认折叠) =================
        advToggle = new Button(this);
        advToggle.setText("▼ 高级设置(参数/日志/备用通道)");
        advToggle.setOnClickListener(v -> {
            boolean show = advPanel.getVisibility() != View.VISIBLE;
            advPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText(show ? "▲ 收起高级设置" : "▼ 高级设置(参数/日志/备用通道)");
        });
        root.addView(advToggle);

        advPanel = new LinearLayout(this);
        advPanel.setOrientation(LinearLayout.VERTICAL);
        advPanel.setVisibility(View.GONE);
        buildAdvancedPanel(advPanel);
        root.addView(advPanel);

        // ---------- 权限说明 ----------
        TextView foot = new TextView(this);
        foot.setText("\n【需要的权限】\n"
                + "· Wi-Fi: 无线调试走 Wi-Fi 网络(第①步前置)\n"
                + "· 无线调试: 发送上滑指令(第①步, 配对一次终身有效)\n"
                + "· 无障碍: 读取画面识别广告页(第②步)\n"
                + "· 通知: 提示通道断线; 电池优化白名单: 防止后台被杀(第③步)\n"
                + "· 悬浮窗(可选): 显示命中角标 + 备用的配对码浮窗\n"
                + "· ColorOS 建议: 系统设置里允许本应用「自启动」和「完全后台行为」\n"
                + "本应用不联网上传任何数据, 只连手机自己的无线调试端口。");
        foot.setTextSize(13);
        foot.setPadding(0, 24, 0, 0);
        root.addView(foot);

        setContentView(scroll);

        requestEssentialPermissions();
        refreshApps();
        refreshSteps();
        refreshLog();
    }

    /** 高级设置面板内容: 备用通道 + 全部参数 + 日志 */
    private void buildAdvancedPanel(LinearLayout adv) {
        // ---------- 备用通道: Shizuku ----------
        adv.addView(sectionLabel("备用通道(一般不用管)"));
        Button b4 = new Button(this);
        b4.setText("授权 Shizuku(备用滑动通道)");
        b4.setOnClickListener(v -> requestShizukuPermission());
        adv.addView(b4);

        Button abBtn = new Button(this);
        abBtn.setText("A/B测试: 无障碍手势 vs ADB");
        abBtn.setOnClickListener(v -> startAbTest());
        adv.addView(abBtn);

        Button altBtn = new Button(this);
        altBtn.setText("通道测试: 5种无障碍滑动方式");
        altBtn.setOnClickListener(v -> startAltTest());
        adv.addView(altBtn);

        // ---------- 检测区域 ----------
        regionLabel = sectionLabel("检测区域(屏幕比例, 自动分四块)");
        adv.addView(regionLabel);
        addSlider(adv, "上条起点", 0.00f, 0.30f, cfg.bandTopStart(), val -> {
            cfg.setBands(val, cfg.bandTopEnd(), cfg.bandBottomStart(), cfg.bandBottomEnd());
            updateRegionLabel();
        });
        addSlider(adv, "上条终点", 0.05f, 0.45f, cfg.bandTopEnd(), val -> {
            cfg.setBands(cfg.bandTopStart(), val, cfg.bandBottomStart(), cfg.bandBottomEnd());
            updateRegionLabel();
        });
        addSlider(adv, "下条起点", 0.55f, 0.98f, cfg.bandBottomStart(), val -> {
            cfg.setBands(cfg.bandTopStart(), cfg.bandTopEnd(), val, cfg.bandBottomEnd());
            updateRegionLabel();
        });
        addSlider(adv, "下条终点", 0.70f, 1.00f, cfg.bandBottomEnd(), val -> {
            cfg.setBands(cfg.bandTopStart(), cfg.bandTopEnd(), cfg.bandBottomStart(), val);
            updateRegionLabel();
        });

        // ---------- 阈值 ----------
        thresholdLabel = sectionLabel("阈值(误滑→调大, 漏滑→调小)");
        adv.addView(thresholdLabel);
        addSlider(adv, "ZNCC阈值", 0.20f, 0.80f, cfg.tplThreshold(), cfg::setTplThreshold);
        addSlider(adv, "极性阈值", 20f, 80f, cfg.polarityThreshold(), cfg::setPolarityThreshold);
        addSlider(adv, "对比度阈值", 0.001f, 0.3f, cfg.contrastThreshold(), val -> {
            cfg.setContrastThreshold(val);
            updateThresholdLabel();
        });
        addSlider(adv, "偏离量(0~1)", 0.1f, 0.6f, cfg.contrastDelta() / 255f, val ->
                cfg.setContrastDelta((int) (val * 255)));

        // ---------- 滑动 ----------
        swipeLabel = sectionLabel("滑动参数");
        adv.addView(swipeLabel);
        addSlider(adv, "起点(屏高%)", 0.3f, 0.95f, cfg.swipeFromY(), val -> {
            cfg.setSwipe(val, cfg.swipeToY());
            updateSwipeLabel();
        });
        addSlider(adv, "终点(屏高%)", 0.05f, 0.7f, cfg.swipeToY(), val -> {
            cfg.setSwipe(cfg.swipeFromY(), val);
            updateSwipeLabel();
        });
        addSlider(adv, "时长(秒)", 0.1f, 1.0f, cfg.swipeDuration() / 1000f, val ->
                cfg.setSwipeDuration((int) (val * 1000)));

        // ---------- 节奏 ----------
        adv.addView(sectionLabel("节奏"));
        addSlider(adv, "检测间隔(秒)", 0.2f, 3.0f, cfg.checkCooldown() / 1000f, val ->
                cfg.setCheckCooldown((long) (val * 1000)));
        addSlider(adv, "空闲检测间隔(秒)", 0.3f, 3.0f, cfg.idleCheckCooldown() / 1000f, val ->
                cfg.setIdleCheckCooldown((long) (val * 1000)));
        addSlider(adv, "滑动冷却(秒)", 0.5f, 10.0f, cfg.swipeCooldown() / 1000f, val ->
                cfg.setSwipeCooldown((long) (val * 1000)));

        // ---------- 倒计时 ----------
        adv.addView(sectionLabel("倒计时标志(提示文案含它就不滑, 对所有应用生效)"));
        EditText cd = new EditText(this);
        cd.setText(cfg.countdownKeyword());
        cd.setTextSize(14);
        cd.addTextChangedListener(simpleWatcher(cfg::setCountdownKeyword));
        adv.addView(cd);

        // ---------- 开关 ----------
        adv.addView(sectionLabel("检测方式"));
        addToggle(adv, "读无障碍文字(商业词/提示词)", cfg.textDetectEnabled(), cfg::setTextDetectEnabled);
        addToggle(adv, "截图像素检测(上滑模板/直播红标)", cfg.pixelDetectEnabled(), cfg::setPixelDetectEnabled);

        // ---------- 配置导入/导出(拷给别人用) ----------
        adv.addView(sectionLabel("配置导入/导出(拷贝给别人用)"));
        LinearLayout cfgRow = new LinearLayout(this);
        cfgRow.setOrientation(LinearLayout.HORIZONTAL);
        Button exp = new Button(this);
        exp.setText("导出配置");
        exp.setOnClickListener(v -> showExportDialog());
        cfgRow.addView(exp);
        Button imp = new Button(this);
        imp.setText("导入配置");
        imp.setOnClickListener(v -> showImportDialog());
        cfgRow.addView(imp);
        adv.addView(cfgRow);

        // ---------- 日志 ----------
        adv.addView(sectionLabel("诊断日志(最近30条)"));
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setPadding(16, 16, 16, 16);
        logView.setBackgroundColor(0xFF1E1E1E);
        logView.setTextColor(0xFFCCCCCC);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setMinLines(8);
        adv.addView(logView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("刷新日志");
        refresh.setOnClickListener(v -> refreshLog());
        btnRow.addView(refresh);
        Button clear = new Button(this);
        clear.setText("清空日志");
        clear.setOnClickListener(v -> {
            logStore.clear();
            refreshLog();
        });
        btnRow.addView(clear);
        Button statsBtn = new Button(this);
        statsBtn.setText("统计");
        statsBtn.setOnClickListener(v -> {
            int[] s = logStore.stats();
            liveView.setText("累计滑动 " + s[0] + " 次");
        });
        btnRow.addView(statsBtn);
        adv.addView(btnRow);
    }

    // ---------- 配置导入/导出 ----------

    /** 导出配置: 弹窗显示 JSON, 一键复制到剪贴板 */
    private void showExportDialog() {
        final String json = cfg.exportConfigJson();
        EditText et = new EditText(this);
        et.setText(json);
        et.setTextSize(11);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(10);
        et.setMaxLines(18);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setPadding(16, 16, 16, 16);
        ScrollView sc = new ScrollView(this);
        sc.addView(et);

        new android.app.AlertDialog.Builder(this)
                .setTitle("导出配置(应用 + 规则 + 参数)")
                .setView(sc)
                .setPositiveButton("复制到剪贴板", (d, w) -> {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "红果助手配置", json));
                            statusView.setText("✅ 已复制, 发给别人后让对方在「导入配置」里粘贴");
                        }
                    } catch (Throwable t) {
                        statusView.setText("复制失败: " + t);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 导入配置: 粘贴 JSON → 覆盖应用/规则/参数 */
    private void showImportDialog() {
        EditText et = new EditText(this);
        et.setHint("在这里粘贴别人发来的配置 JSON(以 { 或 [ 开头)");
        et.setTextSize(12);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(8);
        et.setMaxLines(14);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setPadding(16, 16, 16, 16);
        ScrollView sc = new ScrollView(this);
        sc.addView(et);

        new android.app.AlertDialog.Builder(this)
                .setTitle("导入配置")
                .setView(sc)
                .setPositiveButton("导入", (d, w) -> {
                    String err = cfg.importConfigJson(et.getText().toString());
                    if (err == null) {
                        refreshApps();
                        refreshSteps();
                        statusView.setText("✅ 配置已导入(应用/规则/参数), 约2秒后生效");
                    } else {
                        statusView.setText("❌ 导入失败: " + err);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 监控应用: 增删改查 ----------

    /** 刷新应用列表显示 */
    private void refreshApps() {
        if (appsList == null) return;
        appsList.removeAllViews();
        java.util.ArrayList<AppProfile> list = cfg.loadProfiles();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("  (还没有应用。新装时已内置「红果短剧」, 若被删除可点下面重新添加)");
            empty.setTextSize(13);
            appsList.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            AppProfile p = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 6, 0, 6);

            TextView tv = new TextView(this);
            tv.setText(p.displayName() + "\n" + p.pkg);
            tv.setTextSize(14);
            tv.setPadding(16, 8, 8, 8);
            tv.setOnClickListener(v -> showAppDialog(idx));
            row.addView(tv, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button edit = new Button(this);
            edit.setText("编辑");
            edit.setOnClickListener(v -> showAppDialog(idx));
            row.addView(edit);

            Button del = new Button(this);
            del.setText("删除");
            del.setOnClickListener(v -> confirmDelete(idx));
            row.addView(del);

            appsList.addView(row);
        }
    }

    /** 添加/编辑应用弹窗(index<0 = 新增) */
    private void showAppDialog(int index) {
        java.util.ArrayList<AppProfile> list = cfg.loadProfiles();
        AppProfile old = (index >= 0 && index < list.size()) ? list.get(index) : null;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(48, 24, 48, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("备注名(可选), 如: 红果短剧 / 抖音");
        nameInput.setText(old == null ? "" : old.name);
        nameInput.setTextSize(15);
        panel.addView(nameInput);

        final EditText pkgInput = new EditText(this);
        pkgInput.setHint("应用包名(必填), 如: com.phoenix.read");
        pkgInput.setText(old == null ? "" : old.pkg);
        pkgInput.setTextSize(15);
        pkgInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        panel.addView(pkgInput);

        TextView tip = new TextView(this);
        tip.setText("广告识别已内置(商业词/上滑提示/直播红标/动静反差), 只需填包名, 无需配规则。");
        tip.setTextSize(12);
        tip.setPadding(0, 16, 0, 4);
        panel.addView(tip);

        ScrollView sc = new ScrollView(this);
        sc.addView(panel);

        new android.app.AlertDialog.Builder(this)
                .setTitle(old == null ? "添加应用" : "编辑应用")
                .setView(sc)
                .setPositiveButton("保存", (d, w) -> {
                    String pkg = pkgInput.getText().toString().trim();
                    String name = nameInput.getText().toString().trim();
                    if (pkg.isEmpty()) {
                        statusView.setText("❌ 包名不能为空");
                        return;
                    }
                    AppProfile p = new AppProfile(pkg, name);
                    if (old == null) {
                        // 同包名去重: 已存在就覆盖
                        int dup = -1;
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i).pkg.equals(pkg)) {
                                dup = i;
                                break;
                            }
                        }
                        if (dup >= 0) list.set(dup, p);
                        else list.add(p);
                    } else {
                        list.set(index, p);
                    }
                    cfg.saveProfiles(list);
                    refreshApps();
                    refreshSteps();
                    statusView.setText("✅ 已保存 " + p.displayName() + ", 约2秒后生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 删除应用(带确认) */
    private void confirmDelete(int index) {
        java.util.ArrayList<AppProfile> list = cfg.loadProfiles();
        if (index < 0 || index >= list.size()) return;
        AppProfile p = list.get(index);
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除应用")
                .setMessage("确定删除「" + p.displayName() + "(" + p.pkg + ")」吗?")
                .setPositiveButton("删除", (d, w) -> {
                    list.remove(index);
                    cfg.saveProfiles(list);
                    refreshApps();
                    refreshSteps();
                    statusView.setText("已删除 " + p.displayName());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- ① 一键开启无障碍 ----------

    private void enableAccessibility() {
        if (AdbShell.connected()) {
            statusView.setText("正在通过 ADB 自动开启无障碍...");
            new Thread(() -> {
                String r;
                try {
                    r = AdbShell.enableAccessibility(this);
                } catch (Throwable t) {
                    r = "fail:" + t;
                }
                final String msg = r;
                runOnUiThread(() -> {
                    statusView.setText("ok".equals(msg)
                            ? "✅ 无障碍已开启"
                            : ("自动开启失败(" + msg + "), 改为手动: 已跳转系统设置"));
                    refreshSteps();
                    if (!"ok".equals(msg)) {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    }
                });
            }).start();
        } else {
            statusView.setText("② 还没连接, 先完成②再回来点这里就能一键开; 也可以直接去系统设置手动开");
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 权限: 装完即申请 ----------

    private void requestEssentialPermissions() {
        if (cfg.permAsked()) return;
        cfg.markPermAsked();
        // 通知权限(Android 13+)
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        // 电池优化白名单(后台保活)
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 状态刷新 ----------

    private void refreshSteps() {
        boolean s1 = isServiceEnabled();
        boolean s2 = AdbShell.paired(this);
        boolean s3 = AdbShell.connected();
        boolean sApps = !cfg.loadProfiles().isEmpty();

        // 步骤顺序: ① 无线调试(前置 Wi-Fi) → ② 无障碍 → ③ 监控应用
        step2.setText((s3 ? "✅" : (s2 ? "🟡" : "⏳")) + " ① 连接 ADB 无线调试(先连 Wi-Fi)");
        step1.setText((s1 ? "✅" : "⏳") + " ② 开启无障碍模式");

        // Wi-Fi 提示: 未连接通道时显示, 提醒先连 Wi-Fi 再开无线调试
        wifiNote.setVisibility(s3 ? View.GONE : View.VISIBLE);

        btnAcc.setText(s1 ? "已开启 ✓(点可重开)" : (s3 ? "一键开启" : "去系统设置开启"));

        if (!s2) {
            channelView.setText("一次配对终身有效, 三步:\n1. 手机连上 Wi-Fi\n2. 点「去开发者选项」→ 无线调试 → 使用配对码配对设备\n3. 弹窗出现后, 把 6 位码填到下面点「配对」(不用填端口, 自动识别)");
            pairArea.setVisibility(View.VISIBLE);
            btnReconnect.setVisibility(View.GONE);
        } else if (!s3) {
            channelView.setText("已配对(终身有效)。通道未连接: 手机重启或无线调试重开后, 点下面按钮即可恢复。");
            pairArea.setVisibility(View.GONE);
            btnReconnect.setVisibility(View.VISIBLE);
        } else {
            channelView.setText("🟢 ADB 通道已连接, 上滑指令就绪");
            pairArea.setVisibility(View.GONE);
            btnReconnect.setVisibility(View.GONE);
        }

        btnTest.setVisibility(s1 && s3 ? View.VISIBLE : View.GONE);

        if (s1 && s3 && sApps) {
            statusView.setText("🎉 全绿就绪, 去刷剧吧\n广告倒计时一结束就会自动上滑");
        } else if (!s3) {
            statusView.setText("下一步: 完成 ① 配对(先连 Wi-Fi, 终身一次)");
        } else if (!s1) {
            statusView.setText("下一步: ② 开启无障碍");
        } else if (!sApps) {
            statusView.setText("下一步: 在「监控应用」里添加要监控的 App");
        } else {
            statusView.setText("检查中...");
        }
    }

    private boolean isServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            return enabled.contains(getPackageName() + "/") && enabled.contains("SkipService");
        } catch (Exception e) {
            return false;
        }
    }

    private void requestShizukuPermission() {
        try {
            if (rikka.shizuku.Shizuku.pingBinder()) {
                if (rikka.shizuku.Shizuku.checkSelfPermission()
                        != PackageManager.PERMISSION_GRANTED) {
                    rikka.shizuku.Shizuku.requestPermission(1001);
                    statusView.setText("请在弹窗中允许");
                } else {
                    statusView.setText("✅ Shizuku 已授权(备用通道就绪)");
                }
            } else {
                statusView.setText("⚠️ Shizuku 未运行: 一般用不到, 可忽略");
            }
        } catch (Exception e) {
            statusView.setText("Shizuku 异常: " + e.getMessage());
        }
    }

    // ---------- 辅助 ----------

    private TextView addStep(LinearLayout parent, String name) {
        TextView tv = new TextView(this);
        tv.setText("⏳ " + name);
        tv.setTextSize(18);
        tv.setPadding(8, 30, 8, 6);
        parent.addView(tv);
        return tv;
    }

    private TextView note(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(8, 0, 8, 8);
        parent.addView(tv);
        return tv;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText("\n" + text);
        tv.setTextSize(15);
        tv.setPadding(0, 20, 0, 10);
        return tv;
    }

    private TextWatcher simpleWatcher(java.util.function.Consumer<String> setter) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                setter.accept(s.toString());
            }

            public void afterTextChanged(Editable s) {
            }
        };
    }

    private void updateRegionLabel() {
        if (regionLabel == null) return;
        regionLabel.setText(String.format("\n检测区域: 上条带 %.0f%%~%.0f%%  下条带 %.0f%%~%.0f%%",
                cfg.bandTopStart() * 100, cfg.bandTopEnd() * 100,
                cfg.bandBottomStart() * 100, cfg.bandBottomEnd() * 100));
    }

    private void updateThresholdLabel() {
        if (thresholdLabel == null) return;
        thresholdLabel.setText(String.format("\n阈值: ZNCC %.2f / 极性 %.0f / 对比度 %.3f",
                cfg.tplThreshold(), cfg.polarityThreshold(), cfg.contrastThreshold()));
    }

    private void updateSwipeLabel() {
        if (swipeLabel == null) return;
        float dist = Math.abs(cfg.swipeFromY() - cfg.swipeToY());
        swipeLabel.setText(String.format("\n滑动: %.0f%% → %.0f%% (距离 %.0f%% 屏高, %dms)",
                cfg.swipeFromY() * 100, cfg.swipeToY() * 100, dist * 100, cfg.swipeDuration()));
    }

    interface OnValue {
        void on(float v);
    }

    private void addSlider(LinearLayout parent, String name, float min, float max,
                           float current, OnValue onChange) {
        TextView label = new TextView(this);
        label.setTextSize(14);
        label.setText(String.format("%s: %.3f", name, current));
        parent.addView(label);

        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setProgress((int) ((current - min) / (max - min) * 1000));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float v = min + (max - min) * progress / 1000f;
                label.setText(String.format("%s: %.3f", name, v));
                if (fromUser) {
                    onChange.on(v);
                    updateRegionLabel();
                    updateThresholdLabel();
                    updateSwipeLabel();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        parent.addView(bar);
    }

    private void addToggle(LinearLayout parent, String name, boolean checked, ToggleCb cb) {
        android.widget.CheckBox box = new android.widget.CheckBox(this);
        box.setText(name);
        box.setChecked(checked);
        box.setOnCheckedChangeListener((v, isChecked) -> cb.on(isChecked));
        parent.addView(box);
    }

    interface ToggleCb {
        void on(boolean b);
    }

    private void refreshLog() {
        if (logView == null) return;
        String all = logStore.readAll();
        String[] lines = all.split("\n");
        int from = Math.max(0, lines.length - 30);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        logView.setText(sb.length() == 0 ? "(暂无日志)" : sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSteps();
        refreshApps();
        refreshLog();

        // 从断线通知点进来: 自动重连
        if ("com.hongguo.helper.RECONNECT".equals(getIntent().getAction())) {
            statusView.setText("正在重连 ADB 通道...");
            AdbShell.reconnect(this, ok -> runOnUiThread(() -> {
                statusView.setText(ok ? "✅ ADB 通道已恢复" : "❌ 还是连不上: 请检查无线调试开关");
                refreshSteps();
            }));
        }

        IntentFilter f = new IntentFilter(SkipService.ACTION_STAT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }
}
