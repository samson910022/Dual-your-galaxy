// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook;

// Test-only fixture mirroring a ROM holder variant whose static fields are
// non-public: patchFields must still read/patch the non-final one via
// controlled setAccessible (trySetAccessible is absent from compileSdk 34 stubs), while the final one stays observe-only
// (never force-written). Same package so tests can peek/reset.
final class NonPublicStaticHolder {
    private static String[] ARR = new String[] {"a"};

    private static final String[] FINAL_ARR = new String[] {"a"};

    private NonPublicStaticHolder() {}

    static String[] peekArr() {
        return ARR;
    }

    static String[] peekFinalArr() {
        return FINAL_ARR;
    }

    static void reset() {
        ARR = new String[] {"a"};
    }
}
