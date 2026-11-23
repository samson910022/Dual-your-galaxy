// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// Package-identity migration (v0.1.0-beta.2): fail-closed JVM gate for the
// applicationId / namespace / version / LSPosed module metadata. Reads repo
// text only — no Android framework, no Gradle, no device, no CWD assumption.
package com.galaxydual.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApplicationIdentityTest {
    companion object {
        private const val TARGET_ID = "io.github.samson910022.galaxydualplus"
        private const val TARGET_VERSION = "0.1.0-beta.2"
        private const val TARGET_CODE = "3"
        private const val OLD_ID = "com.galaxydual.plus"
        private const val SCOPE_EXACT = "system\ncom.samsung.android.da.daagent"
        private const val JAVA_INIT_EXACT = "com.galaxydual.hook.DualHook"
        private const val GRADLE_REL = "app/build.gradle.kts"
        private const val MODULE_PROP_REL = "hook/src/main/resources/META-INF/xposed/module.prop"
        private const val SCOPE_REL = "hook/src/main/resources/META-INF/xposed/scope.list"
        private const val JAVA_INIT_REL = "hook/src/main/resources/META-INF/xposed/java_init.list"
    }

    /** Walk parents of [start] until a directory containing app/build.gradle.kts is found. */
    private fun findFrom(start: File): File? {
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, GRADLE_REL).isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Robust to CWD: try the JVM working dir first, then the test class's code
     * source (works when Gradle runs tests from the module dir or the root, and
     * when the test classes are a jar or a directory).
     */
    private fun findRepoRoot(): File {
        val starts = mutableListOf<File>()
        System.getProperty("user.dir")?.let { starts.add(File(it)) }
        try {
            javaClass.protectionDomain?.codeSource?.location?.let { starts.add(File(it.toURI())) }
        } catch (_: Throwable) {
            // A non-file code source is fine — user.dir is the primary anchor.
        }
        for (start in starts) {
            findFrom(start)?.let { return it }
        }
        throw AssertionError("cannot locate repo root (app/build.gradle.kts) from ${starts.map { it.path }}")
    }

    private val repoRoot: File by lazy { findRepoRoot() }

    private fun read(relative: String): String = File(repoRoot, relative).readText()

    private fun gradleString(key: String): String {
        val regex = Regex("""^[ \t]*$key[ \t]*=[ \t]*"([^"]*)"""", RegexOption.MULTILINE)
        return regex.find(read(GRADLE_REL))?.groupValues?.get(1)
            ?: throw AssertionError("missing $key assignment in $GRADLE_REL")
    }

    private fun gradleVersionCode(): String {
        val regex = Regex("""^[ \t]*versionCode[ \t]*=[ \t]*([0-9]+)""", RegexOption.MULTILINE)
        return regex.find(read(GRADLE_REL))?.groupValues?.get(1)
            ?: throw AssertionError("missing versionCode in $GRADLE_REL")
    }

    private fun moduleProp(key: String): String {
        val regex = Regex("""^[ \t]*$key[ \t]*=[ \t]*([^\r\n]*)""", RegexOption.MULTILINE)
        return regex.find(read(MODULE_PROP_REL))?.groupValues?.get(1)?.trim()
            ?: throw AssertionError("missing $key in $MODULE_PROP_REL")
    }

    @Test
    fun applicationId_namespace_and_version_match_beta2_target() {
        assertEquals("applicationId", TARGET_ID, gradleString("applicationId"))
        // applicationId-only migration: namespace stays the Kotlin source package
        // so relative manifest component names resolve to the real classes.
        assertEquals("namespace", OLD_ID, gradleString("namespace"))
        assertEquals("versionName", TARGET_VERSION, gradleString("versionName"))
        assertEquals("versionCode", TARGET_CODE, gradleVersionCode())
    }

    @Test
    fun moduleProp_id_equals_applicationId_and_version_code_synced() {
        val applicationId = gradleString("applicationId")
        val versionName = gradleString("versionName")
        val versionCode = gradleVersionCode()
        assertEquals("module.prop id must equal applicationId", applicationId, moduleProp("id"))
        assertEquals("module.prop version must equal versionName", versionName, moduleProp("version"))
        assertEquals("module.prop versionCode must equal app versionCode", versionCode, moduleProp("versionCode"))
        assertEquals("module.prop id target", TARGET_ID, moduleProp("id"))
        assertEquals("module.prop version target", TARGET_VERSION, moduleProp("version"))
        assertEquals("module.prop versionCode target", TARGET_CODE, moduleProp("versionCode"))
    }

    @Test
    fun old_id_line_is_absent() {
        val propLines = read(MODULE_PROP_REL).lineSequence().map { it.trim() }.toList()
        assertFalse("module.prop must not carry an id=$OLD_ID line", "id=$OLD_ID" in propLines)
        val gradle = read(GRADLE_REL)
        assertFalse("applicationId must not be $OLD_ID", gradle.contains("applicationId = \"$OLD_ID\""))
        assertTrue("namespace must stay the source package $OLD_ID", gradle.contains("namespace = \"$OLD_ID\""))
    }

    @Test
    fun scopeList_is_exactly_the_two_canonical_scopes() {
        val actual = read(SCOPE_REL).replace("\r\n", "\n").trimEnd('\n')
        assertEquals("scope.list must stay exactly the two canonical targets", SCOPE_EXACT, actual)
    }

    @Test
    fun javaInitList_still_points_at_DualHook() {
        val actual = read(JAVA_INIT_REL).replace("\r\n", "\n").trim()
        assertEquals("java_init.list must still point at DualHook", JAVA_INIT_EXACT, actual)
    }
}
