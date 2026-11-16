// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook;

// Test-only fixture: static non-final field declared on a SUPERCLASS. The
// hasPatchableFieldShape probe walks the hierarchy (a superset of
// patchFields's target-only resolution), so the subclass reports a patchable
// shape; patchFields itself would report "field not found" for the subclass
// (safe direction: one discarded policy IPC at most, never a lost patch).
public class SuperclassStaticListBase {
    public static java.util.List<String> DUAL_APP_WHITELIST_PACKAGES = new java.util.ArrayList<>();
}
