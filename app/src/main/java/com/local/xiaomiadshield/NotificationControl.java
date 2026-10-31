package com.local.xiaomiadshield;

import android.annotation.SuppressLint;
import android.content.Context;

final class NotificationControl {
    private static final String PREFS = "notification_control";
    private static final String KEY_PERSISTENT_DETAILS = "persistent_details";

    private NotificationControl() { }

    static boolean showPersistentDetails(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PERSISTENT_DETAILS, true);
    }

    @SuppressLint("ApplySharedPref")
    static void setPersistentDetails(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PERSISTENT_DETAILS, enabled).commit();
    }
}
