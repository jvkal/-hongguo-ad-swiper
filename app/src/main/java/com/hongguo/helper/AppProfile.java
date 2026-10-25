package com.hongguo.helper;

import org.json.JSONObject;

/**
 * 一个被监控应用的配置: 包名 + 备注。
 * 广告识别已全部内置在 SkipService(商业词/上滑提示/直播红标/动静反差),
 * 这里只负责"在哪些 App 前台时启用检测"。
 */
public class AppProfile {

    public String pkg;    // 应用包名, 如 com.phoenix.read
    public String name;   // 备注名(可选), 如 红果短剧

    public AppProfile(String pkg, String name) {
        this.pkg = pkg == null ? "" : pkg.trim();
        this.name = name == null ? "" : name.trim();
    }

    public String displayName() {
        return name.isEmpty() ? pkg : name;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("name", name);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 兼容旧格式(含 rules 数组): 只取 pkg/name, 规则字段丢弃 */
    public static AppProfile fromJson(JSONObject o) {
        try {
            return new AppProfile(o.optString("pkg"), o.optString("name"));
        } catch (Exception e) {
            return null;
        }
    }
}
