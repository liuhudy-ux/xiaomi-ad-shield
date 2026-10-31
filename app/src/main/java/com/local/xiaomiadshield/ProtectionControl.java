package com.local.xiaomiadshield;

import android.annotation.SuppressLint;
import android.content.Context;

final class ProtectionControl {
    private static final String PREFS = "protection";
    private static final String KEY_ENABLED = "master_enabled";

    private ProtectionControl() { }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    @SuppressLint("ApplySharedPref")
    static void setEnabled(Context context, boolean enabled) {
        // 总开关必须在停止服务前同步落盘，避免进程随即被系统回收后恢复旧状态。
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).commit();
    }
}
