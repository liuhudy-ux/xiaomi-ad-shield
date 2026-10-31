package com.local.xiaomiadshield;

import java.util.HashSet;

public final class CompatibilitySelfTest {
    public static void main(String[] args) {
        if (CompatibilityControl.GROUPS.length != 15) {
            throw new AssertionError("expected 15 groups");
        }
        HashSet<String> ids = new HashSet<>();
        HashSet<String> packages = new HashSet<>();
        int defaultBypass = 0;
        for (CompatibilityControl.AppGroup group : CompatibilityControl.GROUPS) {
            if (!ids.add(group.id)) throw new AssertionError("duplicate id: " + group.id);
            if (group.defaultBypass) defaultBypass++;
            if (group.packages.length == 0) throw new AssertionError("empty group: " + group.id);
            for (String packageName : group.packages) {
                if (!packages.add(packageName)) throw new AssertionError("duplicate package: " + packageName);
            }
        }
        if (defaultBypass != 1 || !CompatibilityControl.GROUPS[0].defaultBypass
                || !"meituan".equals(CompatibilityControl.GROUPS[0].id)) {
            throw new AssertionError("unsafe defaults");
        }
        System.out.println("CompatibilitySelfTest passed: " + packages.size() + " packages");
    }
}
