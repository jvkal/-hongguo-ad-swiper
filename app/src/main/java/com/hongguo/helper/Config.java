package com.hongguo.helper;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有可调参数集中在这里, 界面上改, 立即生效(不需要重编译) */
public class Config {

    private static final String SP_NAME = "hongguo_config";
    /** 默认参数版本: 升级后把仍停留在旧默认值的项刷新一次(用户手动调过的不动) */
    private static final int PARAMS_VERSION = 11;

    private final SharedPreferences sp;

    public Config(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        migrateDefaultsIfNeeded();
    }

    /**
     * 只有"当前值 == 旧默认值"时才覆盖成新默认值,
     * 用户自己调过的值保持不动。
     *
     * 历史版本: 2-3(滑动/节奏调参) 4(多应用配置) 5-8(规则引擎迭代, 已废弃)
     * 9(像素检测转正默认开) 10(规则引擎退役, 清理 profiles 里的 rules 字段)
     * 11(上滑距离 76%→18% 加大到 80%→10%, 旧默认值不足以一次划走部分广告页)
     */
    private void migrateDefaultsIfNeeded() {
        int v = sp.getInt("paramsVersion", 1);
        SharedPreferences.Editor e = sp.edit();
        if (v < 2) {
            if (sp.getInt("swipeDuration", 350) == 350) e.putInt("swipeDuration", 200);
            if (sp.getLong("checkCooldown", 400L) == 400L) e.putLong("checkCooldown", 300L);
        }
        if (v < 11) {
            // 旧默认滑动距离(76%→18%)对部分广告页力度不够, 只刷旧默认值的设备
            if (sp.getFloat("swipeFromY", 0.76f) == 0.76f && sp.getFloat("swipeToY", 0.18f) == 0.18f) {
                e.putFloat("swipeFromY", 0.80f);
                e.putFloat("swipeToY", 0.10f);
            }
        }
        if (v < 4) {
            // 多应用配置上线, 把旧的单包名迁成第一个应用配置
            if (!sp.contains("profiles")) {
                try {
                    AppProfile p = new AppProfile(
                            sp.getString("targetPkg", "com.phoenix.read"), "红果短剧");
                    org.json.JSONArray arr = new org.json.JSONArray();
                    arr.put(p.toJson());
                    e.putString("profiles", arr.toString());
                } catch (Exception ignored) {
                }
            }
        }
        if (v < 9) {
            // 像素检测升级为主判据(上滑模板/直播红标/动静反差), 默认打开
            if (!sp.contains("pixelDetectUserSet")) {
                e.putBoolean("pixelDetect", true);
            }
        }
        if (v < 10) {
            // 规则引擎退役(识别全部内置): 把 profiles 里残留的 rules 字段清掉
            try {
                org.json.JSONArray arr = new org.json.JSONArray(sp.getString("profiles", "[]"));
                org.json.JSONArray out = new org.json.JSONArray();
                boolean changed = false;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject row = arr.optJSONObject(i);
                    if (row == null) continue;
                    if (row.has("rules")) {
                        row.remove("rules");
                        changed = true;
                    }
                    out.put(row);
                }
                if (changed) e.putString("profiles", out.toString());
            } catch (Exception ignored) {
            }
        }
        if (v < PARAMS_VERSION) e.putInt("paramsVersion", PARAMS_VERSION);
        e.apply();
    }

    // ---------- 监控应用列表(每应用独立规则) ----------
    public java.util.ArrayList<AppProfile> loadProfiles() {
        java.util.ArrayList<AppProfile> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(sp.getString("profiles", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                AppProfile p = AppProfile.fromJson(arr.getJSONObject(i));
                if (p != null && !p.pkg.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void saveProfiles(java.util.ArrayList<AppProfile> list) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (AppProfile p : list) arr.put(p.toJson());
            sp.edit().putString("profiles", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------- 目标应用 ----------
    public String targetPkg() {
        return sp.getString("targetPkg", "com.phoenix.read");
    }

    public void setTargetPkg(String v) {
        sp.edit().putString("targetPkg", v).apply();
    }

    // ---------- 检测区域(相对屏幕, 0~1) ----------
    // 红果的提示文字位置不固定(竖屏在底部, 横屏在右上角), 所以分四块区域检测取最大值

    /** 上条带的高度范围 */
    public float bandTopStart() {
        return sp.getFloat("bandTopStart", 0.00f);
    }

    public float bandTopEnd() {
        return sp.getFloat("bandTopEnd", 0.25f);
    }

    /** 下条带的高度范围 */
    public float bandBottomStart() {
        return sp.getFloat("bandBottomStart", 0.75f);
    }

    public float bandBottomEnd() {
        return sp.getFloat("bandBottomEnd", 1.00f);
    }

    public void setBands(float ts, float te, float bs, float be) {
        sp.edit()
                .putFloat("bandTopStart", ts)
                .putFloat("bandTopEnd", te)
                .putFloat("bandBottomStart", bs)
                .putFloat("bandBottomEnd", be)
                .apply();
    }

    /** 兼容旧调用 */
    public float regionTopStart() {
        return bandTopStart();
    }

    public float regionTopEnd() {
        return bandTopEnd();
    }

    public float regionBottomStart() {
        return bandBottomStart();
    }

    public float regionBottomEnd() {
        return bandBottomEnd();
    }

    // ---------- 对比度阈值 ----------
    /**
     * 对比度阈值: 区域内"明显偏离中位数亮度"的像素占比。
     * 用中位数做基准 → 屏幕整体调暗/调亮时中位数跟着变, 该比例不变, 所以不受亮度设置影响。
     */
    public float contrastThreshold() {
        return sp.getFloat("contrastThreshold", 0.16f);
    }

    /** 区域内"几乎不变"的像素占比下限: 广告提示条是静止的, 剧集画面一直在动 */
    public float stableThreshold() {
        return sp.getFloat("stableThreshold", 0.55f);
    }

    public void setStableThreshold(float v) {
        sp.edit().putFloat("stableThreshold", v).apply();
    }

    /** 模板匹配阈值: ZNCC 相关系数大于它才算命中提示文字(越大越严格) */
    public float tplThreshold() {
        return sp.getFloat("tplThreshold", 0.45f);
    }

    public void setTplThreshold(float v) {
        sp.edit().putFloat("tplThreshold", v).apply();
    }

    /**
     * 文字极性阈值: 模板里"文字位"平均亮度减"背景位"平均亮度, 低于它不算命中(防误触)。
     * 默认 50: 剧集页"上滑"提示实测极性 49(0.458) 会被挡住, 防止误滑跳集。
     */
    public float polarityThreshold() {
        return sp.getFloat("polarityThreshold", 50f);
    }

    public void setPolarityThreshold(float v) {
        sp.edit().putFloat("polarityThreshold", v).apply();
    }

    /**
     * 广告详情按钮文字(如"查看详情"): 与"上滑提示"或"广告"标签同时出现才判定为广告页。
     * 这是区分 广告页/剧集页 的最强特征, 不要求两词相邻。
     */
    public String detailKeyword() {
        return sp.getString("detailKeyword", "查看详情");
    }

    public void setDetailKeyword(String v) {
        sp.edit().putString("detailKeyword", v).apply();
    }

    public void setContrastThreshold(float v) {
        sp.edit().putFloat("contrastThreshold", v).apply();
    }

    /** 偏离量(0~255): 像素亮度与中位数相差多少才算"文字像素" */
    public int contrastDelta() {
        return sp.getInt("contrastDelta", 45);
    }

    public void setContrastDelta(int v) {
        sp.edit().putInt("contrastDelta", v).apply();
    }

    // ---------- 滑动参数(相对屏幕高度) ----------
    // 80%→10% 共 70% 屏高: 一次到位划走广告页; 旧的 76%→18% 只有 58%, 实测部分广告页划不走。
    // 起点仍在底部手势条之上, 终点避开了状态栏/挖孔区域。
    public float swipeFromY() {
        return sp.getFloat("swipeFromY", 0.80f);
    }

    public float swipeToY() {
        return sp.getFloat("swipeToY", 0.10f);
    }

    public void setSwipe(float fromY, float toY) {
        sp.edit().putFloat("swipeFromY", fromY).putFloat("swipeToY", toY).apply();
    }

    /**
     * 单次上滑时长(ms)。默认 200: 更快更像“甩一下”, 红果判定为一次有效翻页。
     * 注意距离仍要 >半屏(见 swipeFromY/swipeToY), 否则红果不认。
     */
    public int swipeDuration() {
        return sp.getInt("swipeDuration", 200);
    }

    public void setSwipeDuration(int v) {
        sp.edit().putInt("swipeDuration", v).apply();
    }

    // ---------- 节奏控制 ----------
    public long swipeCooldown() {
        return sp.getLong("swipeCooldown", 2000L);
    }

    public void setSwipeCooldown(long v) {
        sp.edit().putLong("swipeCooldown", v).apply();
    }

    public long checkCooldown() {
        return sp.getLong("checkCooldown", 300L);
    }

    public void setCheckCooldown(long v) {
        sp.edit().putLong("checkCooldown", v).apply();
    }

    /**
     * 空闲检测间隔(ms): 最近 3 秒没读到任何规则词/倒计时/广告迹象时用这个。
     * 有动静会自动切回 checkCooldown 的快速频率。默认 600。
     */
    public long idleCheckCooldown() {
        return sp.getLong("idleCheckCooldown", 600L);
    }

    public void setIdleCheckCooldown(long v) {
        sp.edit().putLong("idleCheckCooldown", v).apply();
    }

    // ---------- 开关 ----------
    public boolean textDetectEnabled() {
        return sp.getBoolean("textDetect", true);
    }

    public void setTextDetectEnabled(boolean v) {
        sp.edit().putBoolean("textDetect", v).apply();
    }

    // ---------- 检测方式 ----------
    /** 截图像素检测: 主判据(上滑模板/直播红标/动静反差), 默认开 */
    public boolean pixelDetectEnabled() {
        return sp.getBoolean("pixelDetect", true);
    }

    public void setPixelDetectEnabled(boolean v) {
        sp.edit().putBoolean("pixelDetect", v).putBoolean("pixelDetectUserSet", true).apply();
    }

    // ---------- 首页信息流广告 ----------
    /**
     * 首页信息流广告是否也自动滑过(默认开)。
     * 首页普通内容由「不滑」规则挡住, 但当页面同时出现「广告」标签 + 商业按钮文字
     * (了解详情/查看详情/点击链接)时放行, 只对首页类不滑规则生效。
     */
    public boolean feedAdSkipEnabled() {
        return sp.getBoolean("feedAdSkip", true);
    }

    public void setFeedAdSkipEnabled(boolean v) {
        sp.edit().putBoolean("feedAdSkip", v).apply();
    }

    /** 信息流广告的「广告」标签文字 */
    public String feedAdLabel() {
        return sp.getString("feedAdLabel", "广告");
    }

    /** 信息流广告的商业按钮文字(逗号分隔, 命中任意一个 + 广告标签 = 信息流广告) */
    public String feedAdKeywords() {
        return sp.getString("feedAdKeywords", "了解详情,查看详情,点击链接,获取更多");
    }

    // ---------- 文字检测规则 ----------
    /**
     * 每行一条规则; 行内用 + 连接 = 且(所有词都出现才算); 多行之间 = 或(任一条命中就滑)。
     * 单独一行一个词 = 出现即算(慎用, 容易误判剧集页)。
     */
    public String textRules() {
        return sp.getString("textRules",
                "上滑继续观看+查看详情\n广告+查看详情\n上滑继续+广告");
    }

    public void setTextRules(String v) {
        sp.edit().putString("textRules", v).apply();
    }

    // ---------- 提示文字关键词(旧版, 已被 textRules 取代, 保留兼容) ----------
    /** 出现任一关键词 → 说明广告结束, 可以滑了 */
    public String hintKeywords() {
        return sp.getString("hintKeywords", "上滑继续观看,上滑继续看,上滑观看,继续上滑,上滑继续");
    }

    public void setHintKeywords(String v) {
        sp.edit().putString("hintKeywords", v).apply();
    }

    /** 倒计时标志: 读到提示文字但还含这个字 → 说明在读秒, 不滑 */
    public String countdownKeyword() {
        return sp.getString("countdownKeyword", "秒");
    }

    public void setCountdownKeyword(String v) {
        sp.edit().putString("countdownKeyword", v).apply();
    }

    // ---------- 配置导入/导出(拷给别人用) ----------
    /**
     * 导出整份配置(应用+规则+关键参数)为可读 JSON。
     * 复制给别人的手机, 在「导入配置」里粘贴即可, 不需要重新录规则。
     */
    public String exportConfigJson() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("type", "hongguo-helper-config");
            root.put("version", 1);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (AppProfile p : loadProfiles()) arr.put(p.toJson());
            root.put("profiles", arr);

            org.json.JSONObject p = new org.json.JSONObject();
            p.put("swipeFromY", swipeFromY());
            p.put("swipeToY", swipeToY());
            p.put("swipeDuration", swipeDuration());
            p.put("swipeCooldown", swipeCooldown());
            p.put("checkCooldown", checkCooldown());
            p.put("idleCheckCooldown", idleCheckCooldown());
            p.put("tplThreshold", tplThreshold());
            p.put("polarityThreshold", polarityThreshold());
            p.put("contrastThreshold", contrastThreshold());
            p.put("countdownKeyword", countdownKeyword());
            p.put("textDetect", textDetectEnabled());
            p.put("pixelDetect", pixelDetectEnabled());
            p.put("feedAdSkip", feedAdSkipEnabled());
            p.put("feedAdKeywords", feedAdKeywords());
            root.put("params", p);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 导入配置 JSON(支持完整导出格式, 也兼容只含 profiles 数组的旧格式)。
     *
     * @return null = 成功; 其它 = 错误说明
     */
    public String importConfigJson(String text) {
        if (text == null || text.trim().isEmpty()) return "内容为空";
        try {
            String s = text.trim();
            org.json.JSONObject root = null;
            org.json.JSONArray arr = null;
            if (s.startsWith("[")) {
                arr = new org.json.JSONArray(s);
            } else {
                root = new org.json.JSONObject(s);
                arr = root.optJSONArray("profiles");
            }
            if (arr == null) return "没有找到 profiles 配置";
            java.util.ArrayList<AppProfile> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject row = arr.optJSONObject(i);
                if (row == null) continue;
                AppProfile p = AppProfile.fromJson(row);
                if (p != null && !p.pkg.isEmpty()) list.add(p);
            }
            if (list.isEmpty()) return "profiles 里没有有效的应用配置";
            saveProfiles(list);

            if (root != null) {
                org.json.JSONObject p = root.optJSONObject("params");
                if (p != null) {
                    SharedPreferences.Editor e = sp.edit();
                    if (p.has("swipeFromY")) e.putFloat("swipeFromY", (float) p.optDouble("swipeFromY", swipeFromY()));
                    if (p.has("swipeToY")) e.putFloat("swipeToY", (float) p.optDouble("swipeToY", swipeToY()));
                    if (p.has("swipeDuration")) e.putInt("swipeDuration", p.optInt("swipeDuration", swipeDuration()));
                    if (p.has("swipeCooldown")) e.putLong("swipeCooldown", p.optLong("swipeCooldown", swipeCooldown()));
                    if (p.has("checkCooldown")) e.putLong("checkCooldown", p.optLong("checkCooldown", checkCooldown()));
                    if (p.has("idleCheckCooldown")) e.putLong("idleCheckCooldown", p.optLong("idleCheckCooldown", idleCheckCooldown()));
                    if (p.has("tplThreshold")) e.putFloat("tplThreshold", (float) p.optDouble("tplThreshold", tplThreshold()));
                    if (p.has("polarityThreshold")) e.putFloat("polarityThreshold", (float) p.optDouble("polarityThreshold", polarityThreshold()));
                    if (p.has("contrastThreshold")) e.putFloat("contrastThreshold", (float) p.optDouble("contrastThreshold", contrastThreshold()));
                    if (p.has("countdownKeyword")) e.putString("countdownKeyword", p.optString("countdownKeyword", countdownKeyword()));
                    if (p.has("textDetect")) e.putBoolean("textDetect", p.optBoolean("textDetect", true));
                    if (p.has("pixelDetect")) e.putBoolean("pixelDetect", p.optBoolean("pixelDetect", false));
                    if (p.has("feedAdSkip")) e.putBoolean("feedAdSkip", p.optBoolean("feedAdSkip", true));
                    if (p.has("feedAdKeywords")) e.putString("feedAdKeywords", p.optString("feedAdKeywords", feedAdKeywords()));
                    e.apply();
                }
            }
            return null;
        } catch (Exception e) {
            return "JSON 解析失败: " + e.getMessage();
        }
    }

    // ---------- 首次启动权限申请标记 ----------
    public boolean permAsked() {
        return sp.getBoolean("permAsked", false);
    }

    public void markPermAsked() {
        sp.edit().putBoolean("permAsked", true).apply();
    }
}
