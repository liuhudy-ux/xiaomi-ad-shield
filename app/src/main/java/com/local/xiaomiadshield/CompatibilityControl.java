package com.local.xiaomiadshield;

import android.content.Context;
import android.content.SharedPreferences;

final class CompatibilityControl {
    static final class AppGroup {
        final String id;
        final String label;
        final boolean defaultBypass;
        final String[] packages;

        AppGroup(String id, String label, boolean defaultBypass, String... packages) {
            this.id = id;
            this.label = label;
            this.defaultBypass = defaultBypass;
            this.packages = packages;
        }
    }

    private static final String PREFS = "compatibility";
    private static final String KEY_FILTER_MEITUAN = "filter_meituan_family";
    private static final String KEY_PREFIX = "bypass_";

    static final AppGroup[] GROUPS = {
            new AppGroup("meituan", "美团 / 外卖 / 大众点评", true,
                    "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.dianping.v1"),
            new AppGroup("jd", "京东", false, "com.jingdong.app.mall"),
            new AppGroup("taobao", "淘宝 / 天猫", false, "com.taobao.taobao", "com.tmall.wireless"),
            new AppGroup("pdd", "拼多多", false, "com.xunmeng.pinduoduo"),
            new AppGroup("alipay", "支付宝", false, "com.eg.android.AlipayGphone"),
            new AppGroup("wechat", "微信", false, "com.tencent.mm"),
            new AppGroup("douyin", "抖音", false, "com.ss.android.ugc.aweme"),
            new AppGroup("kuaishou", "快手", false, "com.smile.gifmaker"),
            new AppGroup("bilibili", "哔哩哔哩", false, "tv.danmaku.bili"),
            new AppGroup("video", "腾讯视频 / 爱奇艺 / 优酷", false,
                    "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone"),
            new AppGroup("xiaohongshu", "小红书", false, "com.xingin.xhs"),
            new AppGroup("zhihu", "知乎", false, "com.zhihu.android"),
            new AppGroup("maps", "高德地图 / 百度地图", false,
                    "com.autonavi.minimap", "com.baidu.BaiduMap"),
            new AppGroup("travel", "携程 / 去哪儿", false,
                    "ctrip.android.view", "com.Qunar"),
            new AppGroup("music", "QQ 音乐 / 网易云音乐", false,
                    "com.tencent.qqmusic", "com.netease.cloudmusic")
    };

    private CompatibilityControl() { }

    static boolean isBypassed(Context context, AppGroup group) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = KEY_PREFIX + group.id;
        if (prefs.contains(key)) return prefs.getBoolean(key, group.defaultBypass);
        // 从 v0.11 及更早版本的美团“过滤优先”开关迁移。
        if ("meituan".equals(group.id) && prefs.contains(KEY_FILTER_MEITUAN)) {
            return !prefs.getBoolean(KEY_FILTER_MEITUAN, false);
        }
        return group.defaultBypass;
    }

    static void setBypassed(Context context, AppGroup group, boolean bypassed) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PREFIX + group.id, bypassed).apply();
    }

    static int bypassedGroupCount(Context context) {
        int count = 0;
        for (AppGroup group : GROUPS) if (isBypassed(context, group)) count++;
        return count;
    }

    static void resetDefaults(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (AppGroup group : GROUPS) editor.remove(KEY_PREFIX + group.id);
        editor.remove(KEY_FILTER_MEITUAN).apply();
    }
}
