package com.local.xiaomiadshield;

import java.util.List;

public final class DiagnosticSelfTest {
    public static void main(String[] args) {
        DnsActivityLog.clear();
        DnsActivityLog.beginDiagnostic(60_000L);
        DnsActivityLog.record("api.example.com", false);
        DnsActivityLog.record("api.example.com", false);
        DnsActivityLog.record("api.example.com", false);
        DnsActivityLog.record("ads.example.com", false);
        DnsActivityLog.record("blocked.example.com", true);

        List<DnsActivityLog.Candidate> candidates = DnsActivityLog.diagnosticCandidates();
        if (candidates.size() != 1 || !"ads.example.com".equals(candidates.get(0).domain)) {
            throw new AssertionError("candidate ranking failed: " + candidates.size());
        }
        if (DnsActivityLog.diagnosticQueryCount() != 5) {
            throw new AssertionError("query count failed");
        }
        List<DnsActivityLog.Entry> recent = DnsActivityLog.recent(2);
        if (recent.size() != 2 || !recent.get(0).blocked) {
            throw new AssertionError("recent order failed");
        }
        DnsActivityLog.clear();
        if (DnsActivityLog.hasDiagnostic() || !DnsActivityLog.recent(1).isEmpty()) {
            throw new AssertionError("clear failed");
        }
        System.out.println("DiagnosticSelfTest passed");
    }
}
