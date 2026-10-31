package com.local.xiaomiadshield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DnsActivityLog {
    static final class Entry {
        final long time;
        final String domain;
        final boolean blocked;

        Entry(long time, String domain, boolean blocked) {
            this.time = time;
            this.domain = domain;
            this.blocked = blocked;
        }
    }

    static final class Candidate {
        final String domain;
        final int requests;
        final int score;

        Candidate(String domain, int requests, int score) {
            this.domain = domain;
            this.requests = requests;
            this.score = score;
        }
    }

    private static final int MAX_ENTRIES = 500;
    private static final ArrayList<Entry> entries = new ArrayList<>();
    private static long diagnosticStart;
    private static long diagnosticEnd;

    private DnsActivityLog() { }

    static synchronized void record(String domain, boolean blocked) {
        if (domain == null || domain.isEmpty()) return;
        if (entries.size() >= MAX_ENTRIES) entries.remove(0);
        entries.add(new Entry(System.currentTimeMillis(), domain.toLowerCase(Locale.ROOT), blocked));
    }

    static synchronized void beginDiagnostic(long durationMs) {
        diagnosticStart = System.currentTimeMillis();
        diagnosticEnd = diagnosticStart + durationMs;
    }

    static synchronized boolean isDiagnosticActive() {
        return diagnosticStart > 0 && System.currentTimeMillis() < diagnosticEnd;
    }

    static synchronized long diagnosticRemainingMs() {
        return Math.max(0, diagnosticEnd - System.currentTimeMillis());
    }

    static synchronized boolean hasDiagnostic() { return diagnosticStart > 0; }

    static synchronized int diagnosticQueryCount() {
        int count = 0;
        for (Entry entry : entries) if (inDiagnostic(entry)) count++;
        return count;
    }

    static synchronized List<Entry> recent(int maximum) {
        ArrayList<Entry> result = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && result.size() < maximum; i--) {
            result.add(entries.get(i));
        }
        return result;
    }

    static synchronized List<Candidate> diagnosticCandidates() {
        HashMap<String, Integer> counts = new HashMap<>();
        for (Entry entry : entries) {
            if (inDiagnostic(entry) && !entry.blocked) {
                counts.put(entry.domain, counts.getOrDefault(entry.domain, 0) + 1);
            }
        }
        ArrayList<Candidate> result = new ArrayList<>();
        for (Map.Entry<String, Integer> item : counts.entrySet()) {
            int score = suspicionScore(item.getKey(), item.getValue());
            if (score >= 4) result.add(new Candidate(item.getKey(), item.getValue(), score));
        }
        result.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed()
                .thenComparing(Comparator.comparingInt((Candidate c) -> c.requests).reversed())
                .thenComparing(c -> c.domain));
        if (result.size() > 30) return new ArrayList<>(result.subList(0, 30));
        return Collections.unmodifiableList(result);
    }

    static synchronized void clear() {
        entries.clear();
        diagnosticStart = 0;
        diagnosticEnd = 0;
    }

    private static boolean inDiagnostic(Entry entry) {
        return diagnosticStart > 0 && entry.time >= diagnosticStart && entry.time <= diagnosticEnd;
    }

    private static int suspicionScore(String domain, int count) {
        String[] labels = domain.split("\\.");
        int score = Math.min(3, count);
        for (String label : labels) {
            if (label.equals("ad") || label.equals("ads") || label.equals("advert")
                    || label.equals("promo") || label.equals("splash") || label.equals("marketing")
                    || label.equals("track") || label.equals("tracking") || label.equals("analytics")
                    || label.equals("beacon") || label.equals("commercial")) score += 6;
            if (label.contains("adservice") || label.contains("adserver")
                    || label.contains("adunion") || label.contains("ad-sdk")) score += 5;
        }
        String d = domain.toLowerCase(Locale.ROOT);
        String[] known = {"doubleclick", "admob", "pangolin", "pangle", "gdt", "tanx",
                "alimama", "unityads", "applovin", "inmobi", "vungle", "tracking"};
        for (String marker : known) if (d.contains(marker)) score += 7;
        return score;
    }
}
