package com.local.xiaomiadshield;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.IDN;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class DomainBlocker {
    static final String RULE_FILE = "dns_rules.txt";
    private static final String PREFS = "protection";
    private static final String KEY_CUSTOM_BLOCK = "custom_block";
    private static final String KEY_CUSTOM_ALLOW = "custom_allow";
    private static final String KEY_DIAGNOSTIC_BLOCK = "diagnostic_block";
    private static final Pattern DOMAIN = Pattern.compile("(?i)^[a-z0-9](?:[a-z0-9._-]{0,251}[a-z0-9])?$");
    private static final Set<String> BUILTIN = new HashSet<>(Arrays.asList(
            "ad.mi.com", "api.ad.xiaomi.com", "sdkconfig.ad.xiaomi.com",
            "tracking.miui.com", "data.mistat.xiaomi.com",
            "adservice.google.com", "pagead2.googlesyndication.com",
            "googleads.g.doubleclick.net", "securepubads.g.doubleclick.net",
            "admob.com",
            "pangolin-sdk-toutiao.com", "pangolin.snssdk.com",
            "pglstatp-toutiao.com", "pangle.io", "ad.toutiao.com",
            "ads.tiktok.com", "ads-twitter.com", "unityads.unity3d.com",
            "ads.unity3d.com", "applovin.com", "applvn.com",
            "adcolony.com", "mopub.com", "inmobi.com", "vungle.com",
            "gdtimg.com", "rdelivery.qq.com"
    ));
    private static volatile Set<String> downloaded = Collections.emptySet();
    private static volatile Set<String> customBlocked = Collections.emptySet();
    private static volatile Set<String> customAllowed = Collections.emptySet();
    private static volatile Set<String> diagnosticBlocked = Collections.emptySet();

    static void initialize(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        customBlocked = parseCustom(prefs.getString(KEY_CUSTOM_BLOCK, ""));
        customAllowed = parseCustom(prefs.getString(KEY_CUSTOM_ALLOW, ""));
        diagnosticBlocked = parseCustom(prefs.getString(KEY_DIAGNOSTIC_BLOCK, ""));
        File file = new File(context.getFilesDir(), RULE_FILE);
        if (!file.isFile()) {
            downloaded = Collections.emptySet();
            return;
        }
        HashSet<String> rules = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null && rules.size() < 350_000) {
                String domain = line.trim().toLowerCase(Locale.ROOT);
                if (!domain.isEmpty()) rules.add(domain);
            }
            downloaded = Collections.unmodifiableSet(rules);
        } catch (Exception ignored) { }
    }

    static int downloadedCount() { return downloaded.size(); }
    static int customBlockedCount() { return customBlocked.size(); }
    static int customAllowedCount() { return customAllowed.size(); }
    static int diagnosticBlockedCount() { return diagnosticBlocked.size(); }

    static String customBlockText(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_BLOCK, "");
    }

    static String customAllowText(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_ALLOW, "");
    }

    static int[] saveCustom(Context context, String blockText, String allowText) {
        Set<String> blocked = parseCustom(blockText);
        Set<String> allowed = parseCustom(allowText);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CUSTOM_BLOCK, join(blocked))
                .putString(KEY_CUSTOM_ALLOW, join(allowed)).apply();
        customBlocked = blocked;
        customAllowed = allowed;
        return new int[]{blocked.size(), allowed.size()};
    }

    static int saveDiagnosticBlocks(Context context, Collection<String> domains) {
        StringBuilder raw = new StringBuilder();
        if (domains != null) {
            for (String domain : domains) {
                if (raw.length() > 0) raw.append('\n');
                raw.append(domain);
            }
        }
        Set<String> parsed = parseCustom(raw.toString());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DIAGNOSTIC_BLOCK, join(parsed)).apply();
        diagnosticBlocked = parsed;
        return parsed.size();
    }

    static void clearDiagnosticBlocks(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DIAGNOSTIC_BLOCK).apply();
        diagnosticBlocked = Collections.emptySet();
    }

    static boolean isBlocked(String domain) {
        if (domain == null || domain.isEmpty()) return false;
        String d = domain.toLowerCase(Locale.ROOT);
        if (matches(customAllowed, d)) return false;
        return matches(diagnosticBlocked, d) || matches(customBlocked, d)
                || matches(BUILTIN, d) || matches(downloaded, d);
    }

    private static boolean matches(Set<String> rules, String domain) {
        String candidate = domain;
        while (true) {
            if (rules.contains(candidate)) return true;
            int dot = candidate.indexOf('.');
            if (dot < 0) return false;
            candidate = candidate.substring(dot + 1);
        }
    }

    private static Set<String> parseCustom(String text) {
        TreeSet<String> rules = new TreeSet<>();
        if (text == null) return Collections.emptySet();
        for (String raw : text.split("[\\r\\n,;\\s]+")) {
            String domain = normalize(raw);
            if (domain != null && rules.size() < 2_000) rules.add(domain);
        }
        return Collections.unmodifiableSet(rules);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String d = value.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("||")) d = d.substring(2);
        int marker = d.indexOf('^');
        if (marker >= 0) d = d.substring(0, marker);
        while (d.startsWith(".")) d = d.substring(1);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        try { d = IDN.toASCII(d); } catch (Exception e) { return null; }
        if (!d.contains(".") || d.length() > 253 || !DOMAIN.matcher(d).matches()) return null;
        return d;
    }

    private static String join(Set<String> rules) {
        StringBuilder out = new StringBuilder();
        for (String rule : rules) {
            if (out.length() > 0) out.append('\n');
            out.append(rule);
        }
        return out.toString();
    }
}
