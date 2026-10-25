package com.hongguo.helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;

/**
 * 核心服务: 检测广告页的"可继续上滑观看短剧"提示, 自动上滑。
 *
 * 【两种广告形态】
 *   A 有读秒: "5秒后可继续上滑观看短剧" → 读到"秒"字, 不滑, 等倒计时走完
 *   B 无读秒: "可继续上滑观看短剧"     → 直接滑
 *
 * 【两条识别路径】
 *   1) 无障碍节点能读到文字 → 精确判断(有秒不滑, 无秒才滑)
 *   2) 读不到文字(图片渲染) → 截屏算"对比度", 用中位数做基准,
 *      不受屏幕亮度设置影响(调暗时整体变暗, 中位数跟着降, 比例不变)
 *
 * 【倒计时兜底】滑不动就重试, 倒计时结束那次自然成功。
 */
public class SkipService extends AccessibilityService {

    public static final String TAG = "HGHelper";
    public static final String ACTION_STAT = "com.hongguo.helper.STAT";
    public static final String ACTION_AB_TEST = "com.hongguo.helper.ABTEST";
    public static final String ACTION_ALT_TEST = "com.hongguo.helper.ALTTEST";
    public static final String EXTRA_MSG = "msg";

    /** 静态实例引用, 供 Activity/广播外触发 */
    public static volatile SkipService instance;

    /** 触发通道测试(纯无障碍) */
    public static void triggerAltTest() {
        SkipService s = instance;
        if (s == null) {
            Log.w(TAG, "[ALT] 服务未连接, 无法触发");
            return;
        }
        s.runAltTest();
    }

    /** 触发 A/B 测试 */
    public static void triggerAbTest() {
        SkipService s = instance;
        if (s == null) {
            Log.w(TAG, "[ABTEST] 服务未连接, 无法触发");
            return;
        }
        s.runAbTest();
    }

    /** 最近一次判定为"广告页"的时间戳(供测试判读) */
    public volatile long lastAdHitAt = 0L;

    /** 截图中是否存在广告提示(复用 ZNCC 模板匹配), 供测试判读 */
    public static boolean matchAdIn(android.graphics.Bitmap soft) {
        try {
            SkipService s = instance;
            if (s == null) return false;
            String hit = s.matchHintFine(soft);
            float polarity = s.lastPolarity;
            return hit != null && polarity >= s.cfg.polarityThreshold();
        } catch (Throwable t) {
            return false;
        }
    }

    public void runAltTestPublic() {
        runAltTest();
    }

    public void runAbTestPublic() {
        runAbTest();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;   // 最早的生命周期就赋值, 不等 onServiceConnected
    }

    /** 断线通知: 点一下直接重连 */
    private void showDisconnectNotice() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        NOTICE_CHANNEL, "滑动通道状态", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("ADB 通道断开/恢复提醒");
                nm.createNotificationChannel(ch);
            }
            android.content.Intent intent = new android.content.Intent(this, MainActivity.class);
            intent.setAction("com.hongguo.helper.RECONNECT");
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification n = new android.app.Notification.Builder(this,
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ? NOTICE_CHANNEL : null)
                    .setContentTitle("红果助手: 滑动通道已断开")
                    .setContentText("点我自动重连(需开着无线调试)")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTICE_ID, n);
        } catch (Throwable t) {
            Log.w(TAG, "通知失败: " + t);
        }
    }

    private void cancelDisconnectNotice() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTICE_ID);
        } catch (Throwable ignored) {
        }
    }

    private void runAltTest() {
        Log.i(TAG, "[ALT] 通道测试开始, 5 种方式各间隔 6 秒");
        AltSwipeTest.run(this, 80, 10, 6000L, (name, ok) -> {
            logStore.add("[ALT] " + name + " 发送=" + ok);
            report("ALT测试 " + name);
        });
    }

    private void runAbTest() {
        Log.i(TAG, "[ABTEST] A/B 测试开始, 6 轮交替执行(每轮间隔 6 秒)");
        SwipeABTest.run(this, cmd -> AdbShell.exec(this, cmd), 6, 6000L, 80, 10, 350,
                (round, mode, ok) -> {
                    logStore.add("[ABTEST] 第" + round + "轮 " + mode + " 发送=" + ok);
                    report("AB测试 " + mode);
                });
    }

    /** A/B 测试接收器 */
    private final android.content.BroadcastReceiver abReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            String act = intent.getAction();
            if (ACTION_ALT_TEST.equals(act)) {
                Log.i(TAG, "[ALT] 收到通道测试指令, 5 种方式各间隔 6 秒");
                AltSwipeTest.run(SkipService.this, 80, 10, 6000L, (name, ok) -> {
                    logStore.add("[ALT] " + name + " 发送=" + ok);
                    report("ALT测试 " + name);
                });
                return;
            }
            Log.i(TAG, "[ABTEST] 收到测试指令, 6 轮交替执行(每轮间隔 6 秒)");
            SwipeABTest.run(SkipService.this, cmd -> AdbShell.exec(SkipService.this, cmd),
                    6, 6000L, 80, 10, 350,
                    (round, mode, ok) -> {
                        logStore.add("[ABTEST] 第" + round + "轮 " + mode + " 发送=" + ok);
                        report("AB测试 " + mode);
                    });
        }
    };

    private Config cfg;
    private LogStore logStore;
    /** 命中角标: 右上角白色小圆点, 用户肉眼确认"识别并划走了" */
    private HitBadge badge;
    private long lastSwipeAt = 0L;
    private long lastCheckAt = 0L;
    private long lastNoAdLogAt = 0L;
    private long lastAdbReconnectAt = 0L;
    private boolean wasAdbConnected = false;
    private boolean noticeShown = false;
    private static final String NOTICE_CHANNEL = "hg_status";
    private static final int NOTICE_ID = 1001;
    private android.os.Handler loopHandler;
    private boolean looping = false;

    /** 同一条广告已尝试滑动的次数(防连滑, 但允许重试) */
    private int swipeTimes = 0;
    /** 一次广告最多尝试几次 */
    private static final int MAX_SWIPE_TIMES = 8;
    /** 连续多久检测不到提示, 就认为广告已离开, 重置计数 */
    private static final long RESET_AFTER_GONE = 3000L;
    private long lastSeenAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        cfg = new Config(this);
        logStore = new LogStore(this);
        Log.i(TAG, "服务已连接");
        java.util.ArrayList<AppProfile> profiles = cfg.loadProfiles();
        StringBuilder pkgs = new StringBuilder();
        for (AppProfile p : profiles) pkgs.append(p.displayName()).append("(").append(p.pkg).append(") ");
        logStore.add("[启动] 监控已就绪, 目标: " + (profiles.isEmpty()
                ? "(还没配置应用, 去主界面「监控应用」添加)"
                : pkgs.toString().trim()) + " (在目标App前台时每0.3秒检查一次广告页)");
        report("服务已连接");
        startLoop();
        bindShell();
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(ACTION_AB_TEST);
            f.addAction(ACTION_ALT_TEST);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(abReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(abReceiver, f);
            }
        } catch (Exception ignored) {
        }
        // 启动时自动尝试 ADB 直连重连(配对过的设备)
        try {
            if (AdbShell.paired(this)) {
                AdbShell.reconnect(this, ok -> Log.i(TAG, "ADB 自动重连: " + ok));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Shizuku UserService 连接: shell 权限执行 input swipe */
    private IShellService shell = null;
    private final android.content.ServiceConnection shellConn = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
            shell = IShellService.Stub.asInterface(service);
            Log.i(TAG, "Shizuku Shell 服务已连接");
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
            shell = null;
        }
    };

    private void bindShell() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) return;
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku 未授权, 无法绑定 shell 服务");
                return;
            }
            rikka.shizuku.Shizuku.UserServiceArgs args =
                    new rikka.shizuku.Shizuku.UserServiceArgs(
                            new android.content.ComponentName(this, ShellService.class))
                            .processNameSuffix("shell")
                            .debuggable(false)
                            .version(1);
            rikka.shizuku.Shizuku.bindUserService(args, shellConn);
            Log.i(TAG, "正在绑定 Shizuku Shell 服务...");
        } catch (Throwable t) {
            Log.w(TAG, "bindShell 失败: " + t);
        }
    }

    /**
     * 每秒自检循环。
     * 不能只依赖无障碍事件: 红果播放器静止播放时(视频是 SurfaceView 渲染)
     * 几乎不产生事件, 纯事件驱动会永远不触发。
     */
    private void startLoop() {
        if (looping) return;
        looping = true;
        ensureAnalysisThread();
        loopHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        loopHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                int tick = 250;
                try {
                    // 省电调度:
                    //   息屏 → 截图 API 不可用, 完全跳过检测, 1.5s 低频保活
                    //   目标 App 在前台(5秒内出现过) → 250ms 快速节奏
                    //   目标 App 不在前台 → 1s 低频(无障碍事件仍会即时唤醒检测)
                    boolean interactive = isScreenInteractive();
                    if (!interactive) {
                        tick = 1500;
                    } else if (System.currentTimeMillis() - lastMonitoredFgAt < 5000L) {
                        tick = 250;
                    } else {
                        tick = 1000;
                    }
                    if (interactive) {
                        // 检测全在后台线程跑(截图+ZNCC很重, 不能压主线程, 否则会把服务搞崩)
                        analysisHandler.post(SkipService.this::performCheck);
                    }
                    // ADB 断线时每 15 秒静默重连一次(配对过的设备)
                    long now = System.currentTimeMillis();
                    if (!AdbShell.connected() && AdbShell.paired(SkipService.this)
                            && now - lastAdbReconnectAt > 15000L) {
                        lastAdbReconnectAt = now;
                        AdbShell.reconnect(SkipService.this, ok -> {
                            if (ok) {
                                Log.i(TAG, "ADB 静默重连成功");
                                wasAdbConnected = true;
                                noticeShown = false;
                                cancelDisconnectNotice();
                            } else if (!noticeShown) {
                                noticeShown = true;
                                showDisconnectNotice();
                            }
                        });
                    }
                    if (AdbShell.connected()) {
                        wasAdbConnected = true;
                        noticeShown = false;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "循环异常: " + e);
                }
                if (looping) loopHandler.postDelayed(this, tick);
            }
        }, 500);
        Log.i(TAG, "自检循环已启动(自适应 tick: 息屏1.5s/前台250ms/后台1s, 检测在后台线程)");
    }

    /** 屏幕是否亮着(息屏时截图 API 拿不到内容, 检测纯属空转) */
    private boolean isScreenInteractive() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    /** 分析专用线程: 截图回调/ZNCC/节点遍历都在这里, 主线程只做调度 */
    private android.os.HandlerThread analysisThread;
    private android.os.Handler analysisHandler;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void ensureAnalysisThread() {
        if (analysisThread == null) {
            analysisThread = new android.os.HandlerThread("hg-analysis");
            analysisThread.start();
            analysisHandler = new android.os.Handler(analysisThread.getLooper());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 事件只用来"立即响应"(比循环最多快 1 秒), 循环保底; 检测统一在后台线程跑
        if (event == null || event.getPackageName() == null) return;
        if (cfg == null) cfg = new Config(this);
        String pkg = event.getPackageName().toString();
        // 记录最近一次事件携带的包名: 部分页面(直播推广/全屏广告)getWindows() 里
        // 找不到聚焦窗口且 getRootInActiveWindow() 也失败, 用它做前台判断兜底
        lastEventPkg = pkg;
        lastEventAt = System.currentTimeMillis();
        refreshProfiles();
        boolean monitored = false;
        synchronized (profileCache) {
            for (AppProfile p : profileCache) {
                if (p.pkg.equals(pkg)) {
                    monitored = true;
                    break;
                }
            }
        }
        if (!monitored) return;
        ensureAnalysisThread();
        analysisHandler.post(this::performCheck);
    }

    /**
     * 无障碍连接看门狗。
     * ColorOS 冻结/热更新后, 服务进程活着但无障碍通道死掉
     * (getWindows 全空, fg=- 空转, 日志刷 AccessibilityInteractionClient No connection)。
     * 症状: 连续 60 秒找不到任何聚焦窗口。正常前后台轮换不会这么久。
     * 自愈: 通过 ADB shell 把无障碍开关"关→开", 系统会真正解绑→重绑。
     * 一轮修复最多试 2 次, 间隔 90 秒, 防止死循环刷写 settings。
     */
    private long fgDeadSince = 0L;
    private long lastAccHealAt = 0L;
    private int accHealTries = 0;

    private void watchdog(long now, AppProfile fg) {
        if (fg != null) {
            fgDeadSince = 0L;
            return;
        }
        if (fgDeadSince == 0L) {
            fgDeadSince = now;
            return;
        }
        if (now - fgDeadSince < 60000L) return;
        // 冷却 90 秒; 一轮(2次)后放弃, 等用户手动处理
        if (accHealTries >= 2 || now - lastAccHealAt < 90000L) return;
        if (!AdbShell.connected()) return;
        lastAccHealAt = now;
        accHealTries++;
        Log.w(TAG, "[WATCHDOG] fg=- 已持续 " + (now - fgDeadSince) / 1000 + "s, 无障碍通道疑似僵死, 第"
                + accHealTries + "次自动关→开重绑");
        logStore.add("[看门狗] 无障碍通道僵死(fg=-超60秒), 自动关→开重绑(第" + accHealTries + "次)");
        final android.content.Context ctx = this;
        swipeExecutor.execute(() -> {
            try {
                String svc = ctx.getPackageName() + "/com.hongguo.helper.SkipService";
                String cur = AdbShell.exec(ctx, "settings get secure enabled_accessibility_services");
                if (cur == null) return;
                // 关: 把自己从列表摘掉
                String off = cur.trim().replace(svc, "").replace("::", ":")
                        .replaceAll("^:+|:+$", "");
                if (off.equals(cur.trim())) {
                    // 列表里没有自己(异常状态), 直接写入带上自己
                    AdbShell.exec(ctx, "settings put secure enabled_accessibility_services " + cur.trim() + ":" + svc);
                } else {
                    AdbShell.exec(ctx, "settings put secure enabled_accessibility_services " + off);
                    Thread.sleep(1500);
                    // 开: 加回来
                    AdbShell.exec(ctx, "settings put secure enabled_accessibility_services " + cur.trim());
                }
                AdbShell.exec(ctx, "settings put secure accessibility_enabled 1");
                Log.i(TAG, "[WATCHDOG] 重绑指令已发送, 等待系统重建连接");
            } catch (Throwable t) {
                Log.w(TAG, "[WATCHDOG] 自愈失败: " + t);
            }
        });
    }

    /** 统一的检测入口: 前台校验 → 冷却 → 文字识别 → 像素识别 */
    private void performCheck() {
        if (cfg == null) cfg = new Config(this);
        if (logStore == null) logStore = new LogStore(this);
        if (badge == null) badge = new HitBadge(this);

        // 息屏时截图 API 拿不到内容, 无障碍节点也处于冻结态:
        // 检测纯属空转还会积累 fg=- 触发看门狗误自愈, 直接跳过(省电)
        if (!isScreenInteractive()) return;

        // 关键: 前台必须是监控中的应用, 不是就跳过
        refreshProfiles();
        AppProfile fg = foregroundProfile();
        long now0 = System.currentTimeMillis();
        if (now0 - lastLoopLogAt > 5000L) {
            lastLoopLogAt = now0;
            Log.i(TAG, "[LOOP] tick fg=" + (fg == null ? "-" : fg.pkg) + " adb=" + AdbShell.connected());
        }
        // 看门狗: fg=- 持续超 60 秒 → 无障碍通道疑似僵死, 自动关→开重绑
        watchdog(now0, fg);
        if (fg == null) return;
        accHealTries = 0;   // 通道正常, 重置自愈计数

        long now = System.currentTimeMillis();
        // 自适应频率: 最近3秒页面里出现过提示/倒计时迹象 → 快检(checkCooldown);
        // 一直安静 → 降到 idleCheckCooldown 省电(遍历本身不贵, 但少一次是一点)
        boolean active = now - lastActivityAt < 3000L;
        long cooldown = active ? cfg.checkCooldown() : cfg.idleCheckCooldown();
        // 检测冷却(只限检测频率); 滑动冷却只在真正滑动时检查, 不阻塞检测
        if (now - lastCheckAt < cooldown) return;
        lastCheckAt = now;

        // ───── 回归最初验证过的识别方案, 不再依赖规则/黑名单配置 ─────
        // (黑名单被底层窗口残留文字误触发, 会把确凿的广告页永久卡住, 已整体退出主流程)

        // 一次遍历收集页面文字(倍速守卫/商业词/提示词共用)
        java.util.ArrayList<String> segs = collectVisibleText();

        // 路径0: 倍速面板守卫 —— 屏上出现「锁定倍速/倍速快进/快进中」就禁止一切滑动。
        // 倍速面板的「上滑锁定倍速」和"上滑"像素模板同形, 不挡住会被像素路径误滑
        // (实测滑到了 2.0 倍速快进)。实时判定: 字在屏上挡多久都行, 字消失立即恢复,
        // 不像旧黑名单会因残留文字永久卡死。
        if (isSpeedPanelVisible(segs)) {
            if (now - lastSpeedLogAt > 5000L) {
                lastSpeedLogAt = now;
                Log.i(TAG, "[倍速] 面板/快进在屏, 暂停自动滑动");
                logStore.add("[倍速] 检测到倍速面板或快进中, 暂停自动滑动");
            }
            return;
        }

        // 路径0.5: 商业广告词组合 —— 「进入直播间」是直播推广专属(剧集页不会有), 单词即滑;
        // 「预约/咨询/查看详情」等商业词同屏出现两个不同词(如 预约+咨询), 或一个商业词
        // +严格「广告」标签 → 确认是广告页, 上滑跳过(覆盖首页体检卡等推广内容)。
        String commerce = matchCommerce(segs);
        if (commerce != null) {
            lastAdHitAt = System.currentTimeMillis();
            lastActivityAt = now;
            badge.flash();
            if (allowSwipe()) {
                Log.i(TAG, "商业词命中(" + commerce + ") → 上滑");
                logStore.add("[商业词] 确认是广告(" + commerce + ") → 上滑");
                report("识别到广告页 → 上滑");
                if (swipeUp()) markSwiped();
                return;
            }
        }

        // 路径1: 文字识别 —— 读到"上滑继续观看"类提示且不含"秒" → 滑; 含"秒" → 等倒计时
        if (cfg.textDetectEnabled()) {
            int r = checkTextHint();
            if (r == TEXT_HINT_READY) {
                lastAdHitAt = System.currentTimeMillis();
                badge.flash();
                if (allowSwipe()) {
                    Log.i(TAG, "文字命中(" + lastTextReason + ") → 上滑");
                    logStore.add("[文字] 确认是广告页(" + lastTextReason + ") → 上滑");
                    report("识别到广告页 → 上滑");
                    if (swipeUp()) markSwiped();
                    return;
                } else {
                    logStore.add("[文字] 这条广告已滑 " + swipeTimes + " 次, 暂停等它自己消失");
                    report("已滑过该广告, 等待它消失");
                    return;
                }
            } else if (r == TEXT_HINT_COUNTING) {
                // 倒计时未结束, 不滑; 落到像素路径维持 ZNCC 更新(如果开着)
                lastCountingAt = System.currentTimeMillis();
            } else {
                // 未命中任何规则 → 重置计数
                resetSwipeStateIfGone(now);
            }
        }

        // 路径2: 截图像素识别(ZNCC "上滑"模板 + 极性校验) —— 文字读不到时的兜底。
        // 截图在途时 checkByScreenshot 内部会跳过, 不阻塞文字路径。
        if (cfg.pixelDetectEnabled()) {
            checkByScreenshot();
        }
    }

    /**
     * 找出当前前台应用对应的监控配置。
     * 无障碍事件可能携带旧包名(尤其是从目标App切到别的 App 时),
     * 所以要用 getWindows() 里 focused 的那个窗口再确认一次。
     * 兜底: 有些全屏播放/广告页 getWindows() 里没有 focused 的应用窗口,
     * 这时用 getRootInActiveWindow() 的包名再判断一次(否则会一直 fg=- 检测不到)。
     *
     * @return 命中的应用配置; 前台不是任何监控应用时返回 null
     */
    private AppProfile foregroundProfile() {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                    if (w == null) continue;
                    if (!w.isFocused()) continue;
                    if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    try {
                        CharSequence pkg = root.getPackageName();
                        String p = pkg == null ? "?" : pkg.toString();
                        AppProfile hit = matchProfile(p);
                        if (hit != null) return hit;
                    } finally {
                        root.recycle();
                    }
                }
            }

            // 兜底: 活动窗口(视频/广告页常见)
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) {
                try {
                    CharSequence pkg = active.getPackageName();
                    if (pkg != null) {
                        AppProfile hit = matchProfile(pkg.toString());
                        if (hit != null) return hit;
                    }
                } finally {
                    active.recycle();
                }
            }

            // 再兜底: 最近 30 秒内无障碍事件流携带过监控应用的包名。
            // 实测直播推广页/全屏广告页 getWindows() 无聚焦窗口且
            // getRootInActiveWindow() 也拿不到 —— 服务一直 fg=- 空转, 什么都不检测。
            // 5 秒太短: ColorOS 冻结恢复后事件通道可能延迟, 放宽到 30 秒;
            // 事件包名只会来自监控应用(别的包名在 matchProfile 就被过滤), 不会误触。
            long evtNow = System.currentTimeMillis();
            if (lastEventPkg != null && evtNow - lastEventAt < 30000L) {
                AppProfile hit = matchProfile(lastEventPkg);
                if (hit != null) return hit;
            }

            long now = System.currentTimeMillis();
            if (now - lastFgLogAt > 5000L) {
                lastFgLogAt = now;
                Log.d(TAG, "没有找到聚焦的应用窗口(5秒内只记一次)");
            }
            return null;
        } catch (Exception e) {
            Log.d(TAG, "foregroundProfile 异常: " + e);
            return null;
        }
    }

    private long lastFgLogAt = 0L;
    /** 最近一次无障碍事件携带的包名/时间(foregroundProfile 的第三级兜底) */
    private volatile String lastEventPkg = null;
    private volatile long lastEventAt = 0L;

    /** 包名 → 监控配置(没有返回 null) */
    private AppProfile matchProfile(String pkg) {
        if (pkg == null || pkg.isEmpty() || "?".equals(pkg)) return null;
        synchronized (profileCache) {
            for (AppProfile ap : profileCache) {
                if (ap.pkg.equals(pkg)) {
                    lastMonitoredFgAt = System.currentTimeMillis();
                    return ap;
                }
            }
        }
        return null;
    }

    /** 最近一次确认监控应用在前台的时刻(自适应 tick 降频用) */
    private volatile long lastMonitoredFgAt = 0L;

    /** 应用配置缓存: 每 2 秒重新读一次(界面增删改后最多 2 秒生效) */
    private final java.util.ArrayList<AppProfile> profileCache = new java.util.ArrayList<>();
    private long profileLoadedAt = 0L;

    private void refreshProfiles() {
        long now = System.currentTimeMillis();
        if (now - profileLoadedAt < 2000L) return;
        profileLoadedAt = now;
        java.util.ArrayList<AppProfile> list = cfg.loadProfiles();
        synchronized (profileCache) {
            profileCache.clear();
            profileCache.addAll(list);
        }
    }

    /** 本次还能不能再滑: 同一条广告最多 MAX_SWIPE_TIMES 次 */
    private boolean allowSwipe() {
        return swipeTimes < MAX_SWIPE_TIMES;
    }

    /** 记录一次滑动, 并标记"提示仍在屏幕上"的时间 */
    private void markSwiped() {
        swipeTimes++;
        lastSeenAt = System.currentTimeMillis();
        lastActivityAt = lastSeenAt;   // 刚滑过 → 快检确认广告是否已被划走
        Log.d(TAG, "已滑 " + swipeTimes + "/" + MAX_SWIPE_TIMES + " 次");
    }

    /** 提示已经消失一段时间 → 下一条广告重新计数 */
    private void resetSwipeStateIfGone(long now) {
        if (swipeTimes == 0) return;
        if (lastSeenAt == 0L) lastSeenAt = now;
        if (now - lastSeenAt > RESET_AFTER_GONE) {
            swipeTimes = 0;
            lastSeenAt = 0L;
            Log.d(TAG, "提示已消失, 重置滑动计数");
        }
    }

    // ---------- 文字识别 ----------

    private static final int TEXT_NONE = 0;
    private static final int TEXT_HINT_READY = 1;    // 命中一条规则 → 确认广告页
    private static final int TEXT_HINT_COUNTING = 2; // 倒计时未结束
    private static final int TEXT_HINT_NOAD = 3;     // 预留(规则引擎下不会返回)

    // ---------- 页面文字收集 + 倍速守卫 + 商业词组合 ----------

    /** 倍速面板日志节流 */
    private long lastSpeedLogAt = 0L;
    /** 倍速面板特征: 出现任意一个就暂停自动滑动(实时判定, 字消失即恢复) */
    private static final String[] SPEED_PANEL_WORDS = {"锁定倍速", "倍速快进", "快进中", "倍速中"};
    /** 直播推广专属词: 剧集页/普通内容绝不会出现, 单词命中即判定广告 */
    private static final String LIVE_WORD = "进入直播间";
    /** 严格「广告」标签: 系统渲染的广告角标, 单独出现不算(防误判) */
    private static final String AD_LABEL = "广告";
    /**
     * 通用商业行动词: 同屏出现两个不同的词(如 预约+咨询、查看详情+立即领取)
     * 或一个词+严格「广告」标签 → 判定广告页。单词不判, 防止普通内容误触。
     * 注意: 「立即领取」是金币挂件的全局文案, 每个页面都在, 不能单独当广告特征,
     * 也不把它计入组合(否则普通页面带一个"预约"就会被它凑数误滑)。
     */
    private static final String[] COMMERCE_WORDS = {
            "预约", "咨询", "查看详情", "了解详情", "点击链接", "获取更多",
            "免费领取", "立即购买", "立即抢购", "一键下单",
            "限时低价", "免费咨询", "点击下方", "优选服务", "进入直播间"
    };

    /**
     * 收集当前所有可见应用窗口的文字片段(一次遍历, 供倍速守卫/商业词/提示词共用)。
     * 只收非空片段, 上限 400 条防极端页面拖慢循环。
     */
    private java.util.ArrayList<String> collectVisibleText() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        java.util.ArrayList<AccessibilityNodeInfo> roots = appWindowRoots();
        try {
            for (AccessibilityNodeInfo root : roots) {
                collectNodes(root, 0, out);
            }
        } finally {
            recycleRoots(roots);
        }
        return out;
    }

    private void collectNodes(AccessibilityNodeInfo node, int depth, java.util.ArrayList<String> out) {
        if (node == null || depth > 25 || out.size() >= 400) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String st = ((text != null ? text.toString() : "") + " "
                + (desc != null ? desc.toString() : "")).trim();
        if (!st.isEmpty()) out.add(st);
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child != null) collectNodes(child, depth + 1, out);
            } catch (Exception ignored) {
            } finally {
                if (child != null) child.recycle();
            }
        }
    }

    /** 屏上是否看得到倍速面板/快进字样 */
    private boolean isSpeedPanelVisible(java.util.ArrayList<String> segs) {
        for (String seg : segs) {
            for (String w : SPEED_PANEL_WORDS) {
                if (seg.contains(w)) return true;
            }
        }
        return false;
    }

    /**
     * 商业广告词组合判定。
     * @return null = 不是广告; 其它 = 命中说明(供日志)
     */
    private String matchCommerce(java.util.ArrayList<String> segs) {
        boolean adLabel = false;
        int commerceHit = 0;
        String firstHit = null;
        java.util.ArrayList<String> hitWords = new java.util.ArrayList<>();
        for (String seg : segs) {
            // 严格「广告」标签: 片段恰好是"广告"或"广告"开头带分隔(如 "广告 | ..."),
            // 避免"医疗器械广告审批号"这类长文案误判成标签
            if (seg.equals(AD_LABEL) || seg.startsWith(AD_LABEL + " ")
                    || seg.startsWith(AD_LABEL + "|") || seg.startsWith(AD_LABEL + " | ")
                    || seg.startsWith(AD_LABEL + "｜")) {
                adLabel = true;
            }
            if (seg.contains(LIVE_WORD)) {
                // 直播推广专属词: 单词即判定(剧集页/普通内容绝不会出现)
                return "直播推广「" + LIVE_WORD + "」";
            }
            for (String w : COMMERCE_WORDS) {
                if (seg.contains(w) && !hitWords.contains(w)) {
                    hitWords.add(w);
                    commerceHit++;
                    if (firstHit == null) firstHit = w;
                    break;
                }
            }
        }
        // 「优选服务」在老配置里就是单词触发规则, 实测只出现在推广卡上 → 单词即滑
        if (hitWords.contains("优选服务")) return "推广卡「优选服务」";
        if (commerceHit >= 2) return "商业词组合「" + hitWords.get(0) + "+" + hitWords.get(1) + "」";
        if (commerceHit >= 1 && adLabel) {
            return "商业词「" + firstHit + "」+广告标签";
        }
        return null;
    }

    /**
     * 老方案的文字识别(不读规则配置, 内置提示词):
     * 在可见应用窗口里找"上滑继续观看"类提示词;
     * 提示词出现在带倒计时标志("秒")的文案里 → 读秒中, 不滑;
     * 提示词单独出现 → 广告页确认。
     */
    private int checkTextHint() {
        java.util.ArrayList<AccessibilityNodeInfo> roots = appWindowRoots();
        try {
            if (roots.isEmpty()) return TEXT_NONE;

            java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
            for (String t : cfg.hintKeywords().split(",")) {
                String k = t.trim();
                if (!k.isEmpty()) terms.add(k);
            }
            if (terms.isEmpty()) return TEXT_NONE;

            java.util.HashMap<String, Boolean> present = new java.util.HashMap<>();
            for (String t : terms) present.put(t, false);
            boolean counting = false;
            for (AccessibilityNodeInfo root : roots) {
                if (scanNodes(root, 0, terms, present, new StringBuilder())) counting = true;
            }

            if (counting) {
                lastTextReason = "读秒中(倒计时未结束)";
                return TEXT_HINT_COUNTING;
            }
            for (String t : terms) {
                if (Boolean.TRUE.equals(present.get(t))) {
                    lastTextReason = "提示词「" + t + "」";
                    return TEXT_HINT_READY;
                }
            }
            return TEXT_NONE;
        } catch (Exception e) {
            return TEXT_NONE;
        } finally {
            recycleRoots(roots);
        }
    }

    /** 取所有应用窗口的根节点(广告页常把提示/按钮放在独立覆盖窗口里, 只看活动窗口会漏) */
    private java.util.ArrayList<AccessibilityNodeInfo> appWindowRoots() {
        java.util.ArrayList<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                    if (w == null) continue;
                    if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    // 只要当前活跃/聚焦的窗口: 广告页背后还留着播放器窗口,
                    // 把后台窗口一起扫会读到「选集/简介」等无关文字, 造成误判
                    if (!w.isActive() && !w.isFocused()) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) roots.add(r);
                }
            }
        } catch (Exception ignored) {
        }
        if (roots.isEmpty()) {
            try {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r != null) roots.add(r);
            } catch (Exception ignored) {
            }
        }
        return roots;
    }

    private static void recycleRoots(java.util.ArrayList<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo r : roots) {
            try {
                if (r != null) r.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 遍历节点树: 记录每个规则词是否出现(词出现在带倒计时标志的节点里 → 返回 true 表示读秒中)。
     * dump 收集页面文本(调试用)。
     */
    private boolean scanNodes(AccessibilityNodeInfo node, int depth,
                              java.util.Set<String> terms,
                              java.util.HashMap<String, Boolean> present,
                              StringBuilder dump) {
        if (node == null || depth > 25) return false;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String s = (text != null ? text.toString() : "") + " " + (desc != null ? desc.toString() : "");
        String st = s.trim();

        boolean counting = false;
        if (!st.isEmpty()) {
            if (dump.length() < 600) dump.append(st).append(" | ");
            boolean hasCountdown = containsAny(st, cfg.countdownKeyword());
            for (String t : terms) {
                if (st.contains(t)) {
                    lastActivityAt = System.currentTimeMillis();   // 页面有相关文字 → 保持快检
                    if (hasCountdown) counting = true;   // 这个词出现在倒计时文案里 → 还在读秒
                    else present.put(t, true);
                }
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child != null && scanNodes(child, depth + 1, terms, present, dump)) counting = true;
            } catch (Exception ignored) {
            } finally {
                if (child != null) child.recycle();
            }
        }
        return counting;
    }

    private boolean containsAny(String src, String keywords) {
        if (src == null || src.isEmpty() || keywords == null) return false;
        for (String kw : keywords.split(",")) {
            String k = kw.trim();
            if (!k.isEmpty() && src.contains(k)) return true;
        }
        return false;
    }

    // ---------- 截图识别: 对比度 + 静止度 双条件 ----------

    /** 上一帧的亮度网格, 用于静止度计算 */
    private int[] prevGrid = null;
    private int gridW = 0;
    private int gridH = 0;

    /** "上滑" 两字模板(÷3 细网格亮度), 横屏/竖屏各一张 */
    private static final int FINE_STEP = 3;
    private int[] tplLand = null;
    private int tplLandW = 0, tplLandH = 0;
    private int[] tplPort = null;
    private int tplPortW = 0, tplPortH = 0;
    /** 最近一次细匹配的结果(供日志) */
    private float lastFineScore = -1f;
    private float winPolarity = -999f;

    /** 解码一张模板为 ÷3 亮度网格 */
    private int[] loadTpl(int resId, int[] sizeOut) {
        try {
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inScaled = false;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeResource(getResources(), resId, opt);
            if (bmp == null) return null;
            int w = Math.max(1, bmp.getWidth() / FINE_STEP);
            int h = Math.max(1, bmp.getHeight() / FINE_STEP);
            int[] g = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int c = bmp.getPixel(Math.min(bmp.getWidth() - 1, x * FINE_STEP),
                            Math.min(bmp.getHeight() - 1, y * FINE_STEP));
                    g[y * w + x] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                }
            bmp.recycle();
            sizeOut[0] = w;
            sizeOut[1] = h;
            return g;
        } catch (Exception e) {
            Log.e(TAG, "模板加载异常: " + e);
            return null;
        }
    }

    private void ensureTemplates() {
        if (tplLand == null) {
            int[] sz = new int[2];
            tplLand = loadTpl(R.drawable.tpl_up_land, sz);
            tplLandW = sz[0];
            tplLandH = sz[1];
        }
        if (tplPort == null) {
            int[] sz = new int[2];
            tplPort = loadTpl(R.drawable.tpl_up_port, sz);
            tplPortW = sz[0];
            tplPortH = sz[1];
        }
    }

    /**
     * 在截图的限定条带内做 ÷3 高分辨率 "上滑" 模板匹配。
     * 双条件: ZNCC > znccTh 且 文字极性差(文字位亮度-背景位亮度) >= 50。
     * 剧集页的"选集⌃"按钮结构与模板相似但极性差只有 ~36, 会被正确排除。
     *
     * @return 命中返回描述字符串, 未命中返回 null
     */
    private String matchHintFine(Bitmap soft) {
        ensureTemplates();
        int w = soft.getWidth();
        int h = soft.getHeight();
        boolean landscape = w > h;

        int[] tpl = landscape ? tplLand : tplPort;
        int tw = landscape ? tplLandW : tplPortW;
        int th = landscape ? tplLandH : tplPortH;
        if (tpl == null || tw < 3 || th < 3) return null;

        // 限定条带(相对坐标, 适配任何设备)
        float[][] bands = landscape
                ? new float[][]{{0.00f, 0.20f, 0.40f, 1.00f}}
                : new float[][]{{0.88f, 1.00f, 0.00f, 1.00f}, {0.00f, 0.15f, 0.50f, 1.00f}};

        float znccTh = cfg.tplThreshold();
        float bestScore = -1f;
        float bestPolarity = -999f;

        for (float[] band : bands) {
            int bx0 = (int) (w * band[2]);
            int by0 = (int) (h * band[0]);
            int bx1 = Math.min(w, (int) (w * band[3]));
            int by1 = Math.min(h, (int) (h * band[1]));

            // 条带细网格
            int gw = (bx1 - bx0) / FINE_STEP;
            int gh = (by1 - by0) / FINE_STEP;
            if (gw <= tw || gh <= th) continue;
            int[] grid = new int[gw * gh];
            for (int y = 0; y < gh; y++)
                for (int x = 0; x < gw; x++) {
                    int c = soft.getPixel(bx0 + x * FINE_STEP, by0 + y * FINE_STEP);
                    grid[y * gw + x] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                }

            // 多尺度匹配
            int[][] scales = {{tw, th},
                    {Math.max(3, (int) (tw * 0.85f)), Math.max(3, (int) (th * 0.85f))},
                    {(int) (tw * 1.2f), (int) (th * 1.2f)}};
            for (int[] sc : scales) {
                int sw = sc[0], sh = sc[1];
                if (sw >= gw || sh >= gh || sw < 3 || sh < 3) continue;
                int[] t = (sw == tw && sh == th) ? tpl : resample(tpl, tw, th, sw, sh);

                float r = znccSearch(grid, gw, gh, t, sw, sh);
                if (r > bestScore) {
                    bestScore = r;
                    bestPolarity = winPolarity;
                }
            }
        }

        lastFineScore = bestScore;
        lastPolarity = bestPolarity;
        return bestScore > znccTh ? String.format("ZNCC%.3f", bestScore) : null;
    }

    /** 最近邻重采样 */
    private int[] resample(int[] tpl, int tw, int th, int nw, int nh) {
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(th - 1, y * th / nh);
            for (int x = 0; x < nw; x++) {
                int sx = Math.min(tw - 1, x * tw / nw);
                out[y * nw + x] = tpl[sy * tw + sx];
            }
        }
        return out;
    }

    private float lastPolarity = -999f;

    /** ZNCC 滑窗搜索, 同时把最优窗口的文字极性差写入 winPolarity */
    private float znccSearch(int[] grid, int gw, int gh, int[] tpl, int tw, int th) {
        long tplSum = 0;
        for (int v : tpl) tplSum += v;
        float tplMean = tplSum / (float) (tw * th);
        double tplVar = 0;
        for (int v : tpl) tplVar += (v - tplMean) * (v - tplMean);
        tplVar /= (tw * th);
        if (tplVar < 1e-4) return -1f;

        // 模板文字位/背景位索引
        int nBright = 0, nDark = 0;
        for (int v : tpl) {
            if (v > tplMean + 30) nBright++;
            else if (v < tplMean - 30) nDark++;
        }

        float best = -1f;
        int bestOx = -1, bestOy = -1;
        for (int oy = 0; oy + th <= gh; oy++) {
            for (int ox = 0; ox + tw <= gw; ox++) {
                long winSum = 0;
                for (int y = 0; y < th; y++)
                    for (int x = 0; x < tw; x++)
                        winSum += grid[(oy + y) * gw + (ox + x)];
                float winMean = winSum / (float) (tw * th);
                double cov = 0, winVar = 0;
                for (int y = 0; y < th; y++)
                    for (int x = 0; x < tw; x++) {
                        float g = grid[(oy + y) * gw + (ox + x)] - winMean;
                        float t = tpl[y * tw + x] - tplMean;
                        cov += g * t;
                        winVar += g * g;
                    }
                cov /= (tw * th);
                winVar /= (tw * th);
                if (winVar < 1e-4) continue;
                double d = Math.sqrt(winVar * tplVar);
                if (d < 1e-6) continue;
                float z = (float) (cov / d);
                if (z > best) {
                    best = z;
                    bestOx = ox;
                    bestOy = oy;
                }
            }
        }

        // 极性差: 最优窗口里, 模板文字位的平均亮度 - 模板背景位的平均亮度
        if (bestOx >= 0 && nBright > 0 && nDark > 0) {
            long brightSum = 0, darkSum = 0;
            for (int y = 0; y < th; y++)
                for (int x = 0; x < tw; x++) {
                    int tv = tpl[y * tw + x];
                    int sv = grid[(bestOy + y) * gw + (bestOx + x)];
                    if (tv > tplMean + 30) brightSum += sv;
                    else if (tv < tplMean - 30) darkSum += sv;
                }
            winPolarity = (brightSum / (float) nBright) - (darkSum / (float) nDark);
        } else {
            winPolarity = -999f;
        }
        return best;
    }

    private void checkByScreenshot() {
        // 倍速守卫在 performCheck 里已判过(segs 复用), 不再重复全树遍历
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            report("系统低于 Android 11, 无截图能力");
            return;
        }
        // 截屏是异步的; 上一张还没分析完就跳过本轮, 防止高频循环下堆积
        // (回调可能永远不来: 5 秒后强制放行, 避免像素路径静默死掉)
        if (screenshotInFlight) {
            if (System.currentTimeMillis() - shotStartedAt > 5000L) {
                Log.w(TAG, "[SHOT] callback timeout, reset inflight");
                screenshotInFlight = false;
            } else {
                return;
            }
        }
        screenshotInFlight = true;
        shotStartedAt = System.currentTimeMillis();
        ensureAnalysisThread();
        java.util.concurrent.Executor bg = command -> analysisHandler.post(command);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, bg,
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                Bitmap hw = null;
                                Bitmap soft = null;
                                try {
                                    hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), null);
                                    if (hw == null) return;
                                    // HARDWARE 位图不能 getPixel, 必须复制成软件位图
                                    soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    if (soft == null) return;

                                    analyze(soft);
                                } catch (Exception e) {
                                    Log.e(TAG, "分析异常: " + e);
                                } finally {
                                    if (soft != null) soft.recycle();
                                    if (hw != null) hw.recycle();
                                }
                            } finally {
                                screenshotInFlight = false;
                                try {
                                    result.getHardwareBuffer().close();
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            screenshotInFlight = false;
                            Log.w(TAG, "[SHOT] fail code=" + errorCode);
                            report("截图失败 code=" + errorCode);
                        }
                    });
        } catch (Exception e) {
            screenshotInFlight = false;
            Log.w(TAG, "[SHOT] exception: " + e);
            report("截图异常: " + e.getMessage());
        }
    }

    /** 截屏在途标志(异步回调置回) */
    private volatile boolean screenshotInFlight = false;
    private long shotStartedAt = 0L;
    private long lastLoopLogAt = 0L;

    /**
     * 核心分析:
     * 1) 把整屏降采样成亮度网格
     * 2) 对四个区域分别算 [对比度] 和 [静止度(与上一帧比)]
     * 3) 某区域 对比度>阈值 且 静止度>阈值 → 认定是"静止的提示文字" → 上滑
     * 剧集画面虽然对比度也可能高, 但它在连续变化(静止度低), 不会误触。
     */
    private void analyze(Bitmap soft) {
        int w = soft.getWidth();
        int h = soft.getHeight();

        // 降采样步长: 保证网格约 100x200 规模, 计算量小
        int stepX = Math.max(4, w / 100);
        int stepY = Math.max(4, h / 100);
        int gw = w / stepX;
        int gh = h / stepY;
        if (gw < 4 || gh < 4) return;

        int[] grid = new int[gw * gh];
        int idx = 0;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int px = x * stepX;
                int py = y * stepY;
                if (px >= w) px = w - 1;
                if (py >= h) py = h - 1;
                int c = soft.getPixel(px, py);
                grid[idx++] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            }
        }

        boolean hasPrev = prevGrid != null && prevGrid.length == grid.length;

        // ★ 核心判定: ÷3 细网格 "上滑" 模板匹配 + 文字极性校验
        String hitDesc = matchHintFine(soft);
        float tplScore = lastFineScore;
        float polarity = lastPolarity;
        float tplTh = cfg.tplThreshold();
        float polTh = cfg.polarityThreshold();
        boolean hit = hitDesc != null && polarity >= polTh;

        // ★ 补充判定: 全屏推广页(直播带货等)整页是视频渲染, 文字读不到,
        //   "上滑继续看短剧"横幅半透明叠在亮色画面上, ZNCC/极性也够不着。
        //   铁证是动静反差: 底部横幅静止(稳≥0.7) + 中部全屏视频大动(稳≤0.5),
        //   实测直播页 底0.85/中0.30; 小播放器页 中0.76(画面只占中间一小块)被条件2排除;
        //   全屏剧集页 底部字幕在动 不满足条件1。需要上一帧做对比。
        //   注意: 安静直播(主播坐着不动)中部稳定度可达 0.79, 动静反差不成立,
        //   由下面的「直播中」红标判据兜底。
        boolean feedPromo = false;
        if (hasPrev) {
            float botStable = regionStable(grid, prevGrid, gw, gh, 0.05f, 0.88f, 0.95f, 1.00f);
            float midStable = regionStable(grid, prevGrid, gw, gh, 0.05f, 0.30f, 0.95f, 0.70f);
            feedPromo = botStable >= 0.70f && midStable <= 0.50f;
            if (feedPromo && !hit) {
                long fpNow = System.currentTimeMillis();
                if (fpNow - lastFeedPromoLogAt > 5000L) {
                    lastFeedPromoLogAt = fpNow;
                    String m = String.format("[推广页] 底部稳%.2f(需≥0.70) 中部稳%.2f(需≤0.50) → 全屏推广页, 上滑", botStable, midStable);
                    Log.i(TAG, m);
                    logStore.add(m);
                }
                hit = true;
            }
        }

        // ★ 补充判定: 「直播中」红标 —— 直播推广页左下角的圆角红底白字标签
        //   (饱和红横条, 宽约 8.5% 屏宽)。实测两个直播推广页均为 0.085,
        //   普通剧集页/首页/小播放器页最高 0.015, 阈值 0.05 干净分离。
        //   安静直播页面动静反差检测不到, 靠这个锚点兜底。
        if (!hit) {
            float redRun = liveRedBadgeRatio(soft);
            if (redRun >= 0.05f) {
                hit = true;
                long rbNow = System.currentTimeMillis();
                if (rbNow - lastFeedPromoLogAt > 5000L) {
                    lastFeedPromoLogAt = rbNow;
                    String m = String.format("[推广页] 直播中红标 width=%.3f(需≥0.05) → 直播推广页, 上滑", redRun);
                    Log.i(TAG, m);
                    logStore.add(m);
                }
            }
        }

        // 四个区域: [左上, 右上, 左下, 右下]
        float[][] regions = {
                {0.02f, cfg.bandTopStart(), 0.55f, cfg.bandTopEnd()},
                {0.45f, cfg.bandTopStart(), 0.98f, cfg.bandTopEnd()},
                {0.02f, cfg.bandBottomStart(), 0.55f, cfg.bandBottomEnd()},
                {0.45f, cfg.bandBottomStart(), 0.98f, cfg.bandBottomEnd()},
        };
        String[] names = {"左上", "右上", "左下", "右下"};

        float th = cfg.contrastThreshold();
        float sth = cfg.stableThreshold();
        float maxContrast = 0f, maxStable = 0f;
        String bestName = "-";

        // 对比度+静止度仅作参考日志(不再作为触发条件, 防止字幕误触)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            float c = regionContrast(grid, gw, gh, regions[i][0], regions[i][1], regions[i][2], regions[i][3]);
            float s = hasPrev ? regionStable(grid, prevGrid, gw, gh,
                    regions[i][0], regions[i][1], regions[i][2], regions[i][3]) : 0f;
            sb.append(String.format("%s对%.2f稳%.2f ", names[i], c, s));
            if (c > maxContrast) {
                maxContrast = c;
                bestName = names[i];
                maxStable = s;
            }
        }

        // 日志: ZNCC 命中必记; 普通播放画面每 5 秒汇总一条(避免刷屏)。
        // "接近阈值"观察行已删: 每 0.3 秒刷一条没有分析价值。
        long nowMs = System.currentTimeMillis();
        String msg;
        if (hit && hitDesc != null) {
            // ZNCC 模板路径命中(推广页/红标辅助判据命中时 hitDesc 为 null,
            // 数值行已在各自的 [推广页] 日志里打过了, 不在这里重复误导)
            msg = String.format("[像素] 确认是广告页 ZNCC=%.2f(需>%.2f) 极性=%.0f(需≥%.0f) → 上滑",
                    tplScore, tplTh, polarity, polTh);
        } else if (!hit && nowMs - lastPixelLogAt > 5000L) {
            msg = String.format("[像素] 播放中, 未发现广告 ZNCC=%.2f 极性=%.0f (每5秒汇总一条)",
                    tplScore, polarity);
        } else {
            msg = null;
        }
        if (msg != null) {
            lastPixelLogAt = nowMs;
            Log.d(TAG, msg);
            logStore.add(msg);
        }

        // 保存本帧供下一帧比对
        prevGrid = grid;
        gridW = gw;
        gridH = gh;

        // 快速重拍: 本帧接近阈值但没命中 → 广告页很可能刚出现(动画渐入中),
        // 立即安排下一次截图(跳过 idle 冷却), 不用等下一轮循环, 感知上快一倍。
        if (!hit && hitDesc != null && polarity < polTh) {
            lastActivityAt = nowMs;   // 保持快检节奏
        }

        if (hit) {
            lastAdHitAt = System.currentTimeMillis();
            badge.flash();
            lastSeenAt = System.currentTimeMillis();
            report("识别到广告页 → 上滑");
            // 刚读到过读秒提示 → 广告还没放完, 不滑(防提前滑无效)
            boolean countingRecently = System.currentTimeMillis() - lastCountingAt < 4000L;
            if (countingRecently) {
                logStore.add("[像素] 像是广告页, 但倒计时还没走完, 先不滑");
            } else if (allowSwipe()) {
                if (swipeUp()) markSwiped();
            } else {
                logStore.add("[像素] 这条广告已滑 " + swipeTimes + " 次仍未离开, 等它自己结束");
            }
        } else {
            resetSwipeStateIfGone(System.currentTimeMillis());
        }
    }

    /** 最近一次"推广页"判定日志时间(节流) */
    private long lastFeedPromoLogAt = 0L;

    /**
     * 「直播中」红标检测: 在屏幕下半部(y 50%~95%)找最长的饱和红横条
     * (R>180, G<100, B<100), 返回其宽度占屏宽比。
     * 直播推广页左下角的圆角标签约 0.085; 普通页面 ≤0.015。
     */
    private float liveRedBadgeRatio(Bitmap soft) {
        try {
            int w = soft.getWidth();
            int h = soft.getHeight();
            int y0 = (int) (h * 0.50f);
            int y1 = (int) (h * 0.95f);
            int maxRun = 0;
            for (int y = y0; y < y1; y += 3) {
                int run = 0;
                for (int x = 0; x < w * 0.6f; x += 2) {
                    int c = soft.getPixel(x, y);
                    int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                    if (r > 180 && g < 100 && b < 100) {
                        run++;
                        if (run > maxRun) maxRun = run;
                    } else {
                        run = 0;
                    }
                }
            }
            return maxRun * 2f / w;
        } catch (Exception e) {
            return 0f;
        }
    }
    /** 最近一次"提示在读秒"的时间(像素命中时用它防止提前滑) */
    private volatile long lastCountingAt = 0L;
    /** 最近一次文字判定依据(供日志说明为什么滑/不滑) */
    private String lastTextReason = "";
    private long lastPixelLogAt = 0L;
    /** 最近一次页面出现规则相关文字的时刻(自适应检测频率用) */
    private volatile long lastActivityAt = 0L;

    /** 区域对比度: 偏离中位数的像素占比(亮度免疫) */
    private float regionContrast(int[] grid, int gw, int gh,
                                 float leftX, float topY, float rightX, float bottomY) {
        int x1 = (int) (gw * leftX);
        int y1 = (int) (gh * topY);
        int x2 = (int) (gw * rightX);
        int y2 = (int) (gh * bottomY);
        if (x1 < 0) x1 = 0;
        if (y1 < 0) y1 = 0;
        if (x2 > gw) x2 = gw;
        if (y2 > gh) y2 = gh;
        int n = (x2 - x1) * (y2 - y1);
        if (n <= 0) return 0f;

        int[] lumas = new int[n];
        int i = 0;
        for (int y = y1; y < y2; y++)
            for (int x = x1; x < x2; x++)
                lumas[i++] = grid[y * gw + x];

        int[] sorted = Arrays.copyOf(lumas, n);
        Arrays.sort(sorted);
        int median = sorted[n / 2];

        int delta = cfg.contrastDelta();
        int deviated = 0;
        for (int v : lumas) if (Math.abs(v - median) >= delta) deviated++;
        return (float) deviated / n;
    }

    /** 区域静止度: 与上一帧亮度差小于阈值的像素占比(1=完全没变) */
    private float regionStable(int[] grid, int[] prev, int gw, int gh,
                               float leftX, float topY, float rightX, float bottomY) {
        int x1 = (int) (gw * leftX);
        int y1 = (int) (gh * topY);
        int x2 = (int) (gw * rightX);
        int y2 = (int) (gh * bottomY);
        if (x1 < 0) x1 = 0;
        if (y1 < 0) y1 = 0;
        if (x2 > gw) x2 = gw;
        if (y2 > gh) y2 = gh;
        int n = (x2 - x1) * (y2 - y1);
        if (n <= 0) return 0f;

        int same = 0;
        for (int y = y1; y < y2; y++)
            for (int x = x1; x < x2; x++) {
                int k = y * gw + x;
                if (Math.abs(grid[k] - prev[k]) < 25) same++;
            }
        return (float) same / n;
    }

    /**
     * 对比度占比: 统计区域内"明显偏离中位数亮度"的像素比例。
     * 用中位数做基准 → 屏幕整体调暗/调亮时中位数同步变化, 比例基本不变。
     */
    private float contrastRatio(Bitmap bmp, float leftX, float startY, float rightX, float endY) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        Rect r = new Rect(
                (int) (w * leftX),
                (int) (h * startY),
                (int) (w * rightX),
                (int) (h * endY));

        if (r.left < 0) r.left = 0;
        if (r.top < 0) r.top = 0;
        if (r.right > w) r.right = w;
        if (r.bottom > h) r.bottom = h;
        if (r.width() <= 0 || r.height() <= 0) return 0f;

        int step = 2;
        int sampleW = r.width() / step;
        int sampleH = r.height() / step;
        if (sampleW <= 0 || sampleH <= 0) return 0f;

        int[] lumas = new int[sampleW * sampleH];
        int idx = 0;
        for (int y = r.top; y < r.bottom; y += step) {
            for (int x = r.left; x < r.right; x += step) {
                if (idx >= lumas.length) break;
                int c = bmp.getPixel(x, y);
                lumas[idx++] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            }
        }
        int n = idx;
        if (n == 0) return 0f;

        // 中位数作为基准亮度
        int[] sorted = Arrays.copyOf(lumas, n);
        Arrays.sort(sorted);
        int median = sorted[n / 2];

        int delta = cfg.contrastDelta();
        int deviated = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(lumas[i] - median) >= delta) deviated++;
        }
        return (float) deviated / n;
    }

    // ---------- 动作 ----------

    /** 执行上滑; 冷却期内或无可用手势返回 false */
    private boolean swipeUp() {
        long now = System.currentTimeMillis();
        if (now - lastSwipeAt < cfg.swipeCooldown()) return false;
        lastSwipeAt = now;

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;

        Path path = new Path();
        String desc;

        // 实测: 横屏广告页的退出手势也是"上滑"(竖直方向), 横滑无效
        // 提示文字写的就是"上滑继续观看剧集", 两个方向统一用竖直上滑
        path.moveTo(w / 2f, h * cfg.swipeFromY());
        path.lineTo(w / 2f, h * cfg.swipeToY());
        desc = String.format("上滑 %.0f%%→%.0f%% 屏高 (%s)",
                cfg.swipeFromY() * 100, cfg.swipeToY() * 100, w > h ? "横屏" : "竖屏");

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, cfg.swipeDuration()))
                .build();

        // 滑动执行优先级: ①ADB直连(无需Shizuku) ②Shizuku ③dispatchGesture(被红果拦)
        final int fw = w, fh = h;
        final String fdesc = desc;
        final float x1 = w / 2f, y1 = h * cfg.swipeFromY(), y2 = h * cfg.swipeToY();
        final String cmd = String.format(java.util.Locale.US,
                "input swipe %d %d %d %d %d",
                (int) x1, (int) y1, (int) x1, (int) y2, cfg.swipeDuration());
        swipeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // ① ADB 直连
                String r = null;
                try {
                    r = AdbShell.exec(SkipService.this, cmd);
                } catch (Throwable t) {
                    Log.w(TAG, "AdbShell 异常: " + t);
                }
                if (r != null) {
                    Log.i(TAG, "ADB滑动: " + fdesc);
                    logStore.add("[滑动] 已通过 ADB 发送, " + fdesc);
                    report("已上滑(ADB)");
                    lastSwipeAt = System.currentTimeMillis() + 2500L;
                    return;
                }
                // ② Shizuku
                String r2 = null;
                try {
                    r2 = swipeViaShizuku(x1, y1, x1, y2);
                } catch (Throwable ignored) {
                }
                if (r2 != null) {
                    Log.i(TAG, "Shizuku滑动: " + r2 + " " + fdesc);
                    logStore.add("[滑动] 已通过 Shizuku 发送, " + fdesc);
                    report("已上滑(Shizuku)");
                    lastSwipeAt = System.currentTimeMillis() + 2500L;
                }
                // 都失败: dispatchGesture 已在主线程发过(见下), 无动作
            }
        });

        // 无障碍兜底手势回主线程发(swipeUp 现在跑在分析线程)
        mainHandler.post(() -> dispatchGesture(gesture, null, null));
        // [SWIPE] 用纯 ASCII, 供 PC 代理匹配(中文在管道里会乱码)
        Log.i(TAG, "[SWIPE] " + (fw > fh ? "land" : "port")
                + String.format(java.util.Locale.US, " %.2f->%.2f", cfg.swipeFromY(), cfg.swipeToY()));
        logStore.add("[滑动] 第" + (swipeTimes + 1) + "次尝试, " + desc);
        report("已上滑");
        return true;
    }

    /** 串行执行滑动的后台线程池 */
    private final java.util.concurrent.ExecutorService swipeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 通过 Shizuku UserService 执行 shell input swipe; 不可用返回 null */
    private String swipeViaShizuku(float x1, float y1, float x2, float y2) {
        if (shell == null) return null;
        try {
            String cmd = String.format(java.util.Locale.US,
                    "input swipe %d %d %d %d %d",
                    (int) x1, (int) y1, (int) x2, (int) y2, cfg.swipeDuration());
            return shell.exec(cmd);
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku exec 失败: " + t);
            shell = null;
            return null;
        }
    }

    private void report(String msg) {
        try {
            Intent i = new Intent(ACTION_STAT);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_MSG, msg);
            sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "服务中断");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        looping = false;
        if (loopHandler != null) loopHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "服务解绑, 自检循环停止");
        return super.onUnbind(intent);
    }
}
