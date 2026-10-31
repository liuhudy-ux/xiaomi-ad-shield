package com.local.xiaomiadshield;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

final class RuleUpdater {
    interface Callback { void onFinished(boolean success, String message); }

    private static final String PREFS = "rule_update";
    private static final String KEY_SUCCESS = "last_success";
    private static final String KEY_ATTEMPT = "last_attempt";
    private static final String KEY_COUNT = "rule_count";
    private static final String KEY_ERROR = "last_error";
    private static final String PROTECTION_PREFS = "protection";
    private static final String KEY_AGGRESSIVE = "aggressive_popup";
    private static final String KEY_CHINA_BOOST = "china_mobile_boost";
    private static final String KEY_XIAOMI_BOOST = "xiaomi_system_boost";
    private static final String KEY_VIDEO_BOOST = "video_ad_boost";
    private static final String KEY_MAXIMUM = "maximum_global";
    private static final String KEY_PROFILE = "filter_profile";
    static final int PROFILE_STABLE = 0;
    static final int PROFILE_ENHANCED = 1;
    static final int PROFILE_MAXIMUM = 2;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long RETRY = 3L * 60 * 60 * 1000;
    private static final int MAX_RULES = 350_000;
    private static final Pattern DOMAIN = Pattern.compile("(?i)^[a-z0-9](?:[a-z0-9._-]{0,251}[a-z0-9])?$");
    private static final String[] MAIN_SOURCES = {
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/master/Filters/filter.txt"
    };
    private static final String[] POPUP_SOURCES = {
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/adguard_popup_filter.txt",
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_59.txt"
    };
    private static final String[] MOBILE_SDK_SOURCES = {
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_53.txt",
            "https://raw.githubusercontent.com/TG-Twilight/AWAvenue-Ads-Rule/main/AWAvenue-Ads-Rule.txt"
    };
    private static final String[] CHINA_REGION_SOURCES = {
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_29.txt",
            "https://raw.githubusercontent.com/Cats-Team/AdRules/main/dns.txt"
    };
    private static final String[] XIAOMI_SOURCES = {
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_60.txt",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/native.xiaomi.txt"
    };
    private static final String[] VIDEO_SOURCES = {
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_7.txt"
    };
    private static final String[] MAXIMUM_SOURCES = {
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_34.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt"
    };
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean UPDATING = new AtomicBoolean(false);

    static void updateAsync(Context context, boolean force, Callback callback) {
        Context app = context.getApplicationContext();
        if (!UPDATING.compareAndSet(false, true)) {
            if (callback != null) callback.onFinished(false, "规则正在同步中");
            return;
        }
        EXECUTOR.execute(() -> {
            boolean success = false;
            String message;
            try {
                if (!force && !isDue(app)) {
                    success = true;
                    message = "规则已是最新";
                } else {
                    int count = downloadAndSave(app);
                    success = true;
                    message = "已同步 " + count + " 条域名规则";
                }
            } catch (Exception e) {
                message = "同步失败，继续使用已有规则：" + safeMessage(e);
                prefs(app).edit().putLong(KEY_ATTEMPT, System.currentTimeMillis())
                        .putString(KEY_ERROR, message).apply();
            } finally {
                UPDATING.set(false);
            }
            if (callback != null) callback.onFinished(success, message);
        });
    }

    static boolean isUpdating() { return UPDATING.get(); }
    static long lastSuccess(Context context) { return prefs(context).getLong(KEY_SUCCESS, 0); }
    static int savedCount(Context context) { return prefs(context).getInt(KEY_COUNT, DomainBlocker.downloadedCount()); }
    static String lastError(Context context) { return prefs(context).getString(KEY_ERROR, ""); }
    static boolean isAggressive(Context context) {
        return context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AGGRESSIVE, false);
    }
    static void setAggressive(Context context, boolean enabled) {
        context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AGGRESSIVE, enabled).apply();
    }
    static boolean isChinaBoost(Context context) {
        return context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CHINA_BOOST, false);
    }
    static void setChinaBoost(Context context, boolean enabled) {
        context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_CHINA_BOOST, enabled).apply();
    }
    static boolean isXiaomiBoost(Context context) { return protectionFlag(context, KEY_XIAOMI_BOOST); }
    static void setXiaomiBoost(Context context, boolean enabled) { setProtectionFlag(context, KEY_XIAOMI_BOOST, enabled); }
    static boolean isVideoBoost(Context context) { return protectionFlag(context, KEY_VIDEO_BOOST); }
    static void setVideoBoost(Context context, boolean enabled) { setProtectionFlag(context, KEY_VIDEO_BOOST, enabled); }
    static boolean isMaximum(Context context) { return protectionFlag(context, KEY_MAXIMUM); }
    static void setMaximum(Context context, boolean enabled) { setProtectionFlag(context, KEY_MAXIMUM, enabled); }

    static int getProfile(Context context) {
        SharedPreferences p = context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE);
        if (p.contains(KEY_PROFILE)) {
            int value = p.getInt(KEY_PROFILE, PROFILE_STABLE);
            return Math.max(PROFILE_STABLE, Math.min(PROFILE_MAXIMUM, value));
        }
        int migrated;
        if (p.getBoolean(KEY_MAXIMUM, false)) migrated = PROFILE_MAXIMUM;
        else if (p.getBoolean(KEY_AGGRESSIVE, false) || p.getBoolean(KEY_CHINA_BOOST, false)
                || p.getBoolean(KEY_XIAOMI_BOOST, false)) migrated = PROFILE_ENHANCED;
        else migrated = PROFILE_STABLE;
        p.edit().putInt(KEY_PROFILE, migrated).apply();
        return migrated;
    }

    static void setProfile(Context context, int profile) {
        int safe = Math.max(PROFILE_STABLE, Math.min(PROFILE_MAXIMUM, profile));
        context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PROFILE, safe).apply();
    }

    private static boolean isDue(Context context) {
        SharedPreferences p = prefs(context);
        long success = p.getLong(KEY_SUCCESS, 0);
        long attempt = p.getLong(KEY_ATTEMPT, 0);
        long now = System.currentTimeMillis();
        if (success == 0) return attempt == 0 || now - attempt >= RETRY;
        return now - success >= DAY;
    }

    private static int downloadAndSave(Context context) throws Exception {
        int profile = getProfile(context);
        Parsed parsed = profile == PROFILE_MAXIMUM
                ? downloadFirst(MAXIMUM_SOURCES, 50_000)
                : downloadFirst(MAIN_SOURCES, 10_000);
        if (profile >= PROFILE_ENHANCED) {
            merge(parsed, downloadFirst(POPUP_SOURCES, 100));
            merge(parsed, downloadFirst(MOBILE_SDK_SOURCES, 100));
            merge(parsed, downloadFirst(CHINA_REGION_SOURCES, 1_000));
            merge(parsed, downloadFirst(XIAOMI_SOURCES, 10));
        }
        if (isVideoBoost(context)) merge(parsed, downloadFirst(VIDEO_SOURCES, 100));
        if (parsed.blocked.size() > MAX_RULES) throw new IllegalStateException("合并规则数量过大，请少开启一个加强选项");
        save(context, parsed.blocked, parsed.allowed);
        long now = System.currentTimeMillis();
        int count = parsed.blocked.size();
        prefs(context).edit().putLong(KEY_SUCCESS, now).putLong(KEY_ATTEMPT, now)
                .putInt(KEY_COUNT, count).remove(KEY_ERROR).apply();
        DomainBlocker.initialize(context);
        return count;
    }

    private static Parsed downloadFirst(String[] sources, int minimumRules) throws Exception {
        Exception last = null;
        for (String source : sources) {
            try {
                return download(source, minimumRules);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("没有可用规则源");
    }

    private static void merge(Parsed base, Parsed extra) {
        base.blocked.addAll(extra.blocked);
        base.allowed.addAll(extra.allowed);
        base.blocked.removeAll(base.allowed);
    }

    private static boolean protectionFlag(Context context, String key) {
        return context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE).getBoolean(key, false);
    }

    private static void setProtectionFlag(Context context, String key, boolean enabled) {
        context.getSharedPreferences(PROTECTION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply();
    }

    private static Parsed download(String source, int minimumRules) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "XiaomiAdShield/0.8");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

        HashSet<String> blocked = new HashSet<>();
        HashSet<String> allowed = new HashSet<>();
        long chars = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                chars += line.length();
                if (chars > 16_000_000L) throw new IllegalStateException("规则文件过大");
                parseLine(line, blocked, allowed);
                if (blocked.size() > MAX_RULES) throw new IllegalStateException("规则数量异常");
            }
        } finally {
            connection.disconnect();
        }
        blocked.removeAll(allowed);
        if (blocked.size() < minimumRules) throw new IllegalStateException("有效规则过少");
        return new Parsed(blocked, allowed);
    }

    private static void parseLine(String raw, Set<String> blocked, Set<String> allowed) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("#")) return;
        boolean exception = line.startsWith("@@");
        if (exception) line = line.substring(2);
        String domain = null;
        if (line.startsWith("||")) {
            int end = line.indexOf('^', 2);
            if (end < 0) end = line.indexOf('$', 2);
            if (end < 0) end = line.length();
            domain = line.substring(2, end);
        } else if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) domain = parts[1];
        } else if (!line.contains("/") && !line.contains("*") && !line.contains("$") && !line.contains(" ")) {
            domain = line;
        }
        domain = normalize(domain);
        if (domain == null) return;
        if (exception) allowed.add(domain); else blocked.add(domain);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String d = value.trim().toLowerCase(Locale.ROOT);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        try { d = IDN.toASCII(d); } catch (Exception e) { return null; }
        if (!d.contains(".") || d.length() > 253 || !DOMAIN.matcher(d).matches()) return null;
        return d;
    }

    private static void save(Context context, Set<String> blocked, Set<String> allowed) throws Exception {
        ArrayList<String> sorted = new ArrayList<>(blocked);
        sorted.removeAll(allowed);
        Collections.sort(sorted);
        AtomicFile atomic = new AtomicFile(new File(context.getFilesDir(), DomainBlocker.RULE_FILE));
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            for (String domain : sorted) {
                out.write(domain.getBytes(StandardCharsets.US_ASCII));
                out.write('\n');
            }
            atomic.finishWrite(out);
        } catch (Exception e) {
            if (out != null) atomic.failWrite(out);
            throw e;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static final class Parsed {
        final HashSet<String> blocked;
        final HashSet<String> allowed;
        Parsed(HashSet<String> blocked, HashSet<String> allowed) {
            this.blocked = blocked; this.allowed = allowed;
        }
    }
}
