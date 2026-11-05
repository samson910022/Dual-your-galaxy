// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.core

// One Android user/profile that may host a dual clone.
data class DualProfile(
    // Android user id, e.g. 0 for owner, 95 for a dual app profile.
    val userId: Int,
    // Human readable label parsed from pm output, e.g. "Owner" or "DUAL_APP".
    val label: String,
    // Raw flag/type field from UserInfo{<id>:<label>:<flags>}, e.g. "13" or "400".
    // Defaults to "" so existing callers constructing DualProfile(userId, label)
    // keep compiling; parsers always populate it (see parseUsersDetailed).
    val flags: String = "",
)

// Structured entry parsed from pm/dumpsys output for one dual app.
// Kept for backward compatibility with existing callers and docs.
data class DualAppInfo(
    // Base package name, e.g. com.example.chat.
    val packageName: String,
    // Android user id hosting the clone, e.g. 150.
    val userId: Int,
    // Whether dual instance is currently enabled.
    val dualEnabled: Boolean,
)

// Tri-state result for `pm list packages` ground truth (PR-2 stage 1,
// issue #22, additive only: no existing verify/list path reads this yet).
// Distinguishes a genuinely empty profile (TrustedEmpty) from an
// uncheckable channel (Unknown), so a later stage can stop folding
// "confirmed absent" and "cannot check" together.
// - TrustedFull(packages): clean listing containing these exact packages.
// - TrustedEmpty: clean listing with zero package: lines (confirmed empty).
// - Unknown(reason): truncated capture, non-zero exit, or error-semantic
//   text (same line-level C2 rule as isSuccess, minus the install-only
//   ALREADY_EXISTS exception: any Failure line in a listing is untrusted).
sealed interface TrustedList {
    // Clean listing with at least one legal package: line.
    data class TrustedFull(val packages: List<String>) : TrustedList

    // Clean listing with zero package: lines: confirmed empty profile.
    data object TrustedEmpty : TrustedList

    // Uncheckable: truncated output, non-zero exit, or error-semantic text.
    // `detail` aliases `reason` for readers used to VerifyState.Unknown.
    data class Unknown(val reason: String = "") : TrustedList {
        val detail: String get() = reason
    }
}

// Tolerant parser and command builder for pm-style outputs; pure Kotlin, no Android deps.
object PmOutputParser {
    // Matches lines like "UserInfo{0:Owner:13} running" or "UserInfo{95:DUAL_APP:400}".
    // Group 3 (flag/type field) is retained, never dropped (C1 safety audit, issue #2).
    private val USER_INFO = Regex("UserInfo\\{(\\d+):([^:}]*):([^}]*)\\}")

    // Keywords indicating failure even when the caller reports exit 0.
    // Phase C2 (issue #8): these are LINE-LEVEL error-semantic patterns, never
    // plain whole-output substrings. The old code did
    // `output.lowercase().contains("failure")`, so a success payload echoing a
    // package name/path containing "failure" or "exception" (e.g.
    // "Package com.example.failureapp installed for user: 95" or
    // "package:/data/app/com.exception.app/base.apk") was misjudged as failure.
    // Now isSuccess() first skips exempt lines (package:/path echoes and success
    // message lines containing the package name) and only then matches the
    // patterns below per line. Do NOT add bare substrings here without a
    // boundary guard plus an exempt-line test.
    //
    // Boundary convention: (?<![A-Za-z0-9]) ... (?![A-Za-z0-9]) counts as a word
    // boundary. An underscore/dot counts as a boundary, so SCREAMING_SNAKE pm
    // codes (INSTALL_FAILED_*, DELETE_FAILED_*, PERMISSION_DENIED) still match,
    // while "failureapp"/"errorapp" (keyword followed by a letter) do not.
    // "exception" intentionally has only a RIGHT guard so CamelCase stack
    // headers (SecurityException, IllegalArgumentException) match; package
    // names are lowercase dotted tokens that only ever appear on exempt lines,
    // so this cannot introduce a new false positive there.
    private val EXEMPT_LINE = listOf(
        // `pm path` / `pm list packages` echoes, e.g.
        // "package:/data/app/com.failure.app/base.apk".
        Regex("^\\s*package:", RegexOption.IGNORE_CASE),
        // Uninstall success ("Success") and variants ("Success: ...").
        Regex("^\\s*success(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // Install-existing success echo containing the package name, e.g.
        // "Package com.example.failureapp installed for user: 95".
        // Guarded against negation: the canonical not-installed error
        // ("Package X is not installed for user 95") contains the same tail
        // words, so any line carrying not/n't/never is NOT exempt and falls
        // through to the error patterns ("not installed" matches there).
        Regex(
            "^(?!.*\\bnot\\b)(?!.*n['\u2019]t)(?!.*\\bnever\\b)(?=.*installed\\s+for\\s+user).*$",
            RegexOption.IGNORE_CASE,
        ),
    )

    // True pm error signals (all case-insensitive unless noted). Each must be
    // error-semantic on its own line: Failure [...] lines, Error: lines,
    // Exception headers/stack lines, unknown-package/not-installed statements,
    // and denied/cannot/abort/invalid operation failures.
    private val ERROR_LINE = listOf(
        // "Failure [INSTALL_FAILED_...]" / "Failure: ..." / "FAILURE ...".
        Regex("(?<![A-Za-z0-9])failures?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // "Failed to ...", "INSTALL_FAILED_*", "DELETE_FAILED_*".
        Regex("(?<![A-Za-z0-9])failed(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // "Exception occurred ...", "java.lang.SecurityException: ...".
        // Right guard only (see note above).
        Regex("exceptions?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // "Error: ..." as a whole word ("com.error.app" on a non-exempt line
        // must not match: both guards required).
        Regex("(?<![A-Za-z0-9])error(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // CamelCase framework errors ("AssertionError:", "SecurityError: ...").
        // Package names are lowercase, so a capital-E "Error" suffix can only
        // be a class name, never a package token.
        Regex("Error(?![A-Za-z0-9])"),
        // "Permission denied" / "Permission Denial: ..." / "PERMISSION_DENIED".
        Regex("(?<![A-Za-z0-9])deni(?:ed|al)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // "cannot ...", "can't ...", "can not ...", "could not ...".
        Regex(
            "(?<![A-Za-z0-9])(?:cannot|can\\s+not|can['\u2019]t|could\\s+not)(?![A-Za-z0-9])",
            RegexOption.IGNORE_CASE,
        ),
        // "aborted", "Install aborted", "aborting".
        Regex("(?<![A-Za-z0-9])abort(?:s|ed|ing)?(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // "Invalid APK ...", "invalid user ...", "INSTALL_FAILED_INVALID_APK".
        Regex("(?<![A-Za-z0-9])invalid(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        // Unknown-package / not-installed statements.
        Regex("unknown\\s+package", RegexOption.IGNORE_CASE),
        Regex("not\\s+installed", RegexOption.IGNORE_CASE),
        Regex("not\\s+found", RegexOption.IGNORE_CASE),
        Regex("no\\s+such\\s+package", RegexOption.IGNORE_CASE),
        // Java stack continuation lines ("\tat com.android.server.pm...") and
        // "Caused by: ..." headers: only meaningful next to an exception, and
        // pm success output never contains them.
        Regex("^\\s*at\\s+[\\w\$/.-]+\\s*\\("),
        Regex("^\\s*caused\\s+by\\s*:", RegexOption.IGNORE_CASE),
    )

    // Idempotent-success marker: the clone goal state ("package installed for
    // the user") already holds, so re-cloning is success, not failure.
    // Policy (Phase C2, explicit): INSTALL_FAILED_ALREADY_EXISTS is treated as
    // SUCCESS regardless of exit code, checked before anything else. Rationale:
    // `pm install-existing` reports this code with exit 0 on some ROMs and
    // non-zero on others; callers (clone/install) want idempotency, and the
    // verify() second-check independently confirms the install. Scope note:
    // only this code is idempotent-success; uninstall-side "Unknown package"
    // (already removed) stays a failure by design -- its callers interpret the
    // message themselves.
    private val ALREADY_EXISTS =
        Regex("install_failed_already_exists", RegexOption.IGNORE_CASE)

    // Shared dual-profile safety boundary (Phase C1, issue #2, P0).
    // Single source of truth used by DualRepository.resolveDualUserId() and both
    // Elevator probes; previously each had its own copy with an unsafe fallback.
    // Policy (refuse writes rather than guess the target):
    // - Only an explicit DUAL label hit counts; there is NO "first non-zero user"
    //   fallback (that could pick a Work profile or any unrelated user).
    // - A DUAL-labeled user 0 is never selected (owner profile is off-limits).
    // - Zero or ambiguous (>1) candidates resolve to -1 (unknown/refused).
    // Pure function: no I/O, no Android dependency.
    //
    // DEPRECATED (PR-1 stage 2, issue #21): label-only identification is unsafe.
    // A user-renamed profile or a ROM-named Work/Secondary/Secure Folder entry
    // can carry a DUAL substring without being a clone profile, and a renamed
    // clone can drop the substring. All production paths now resolve via
    // resolveDualUserIdVerified() (dual-source type evidence via
    // SamsungProfileAdapter.resolveDual); this entry is kept only so existing
    // callers keep compiling and must not be used for new authorization.
    @Deprecated(
        message = "Label-only identification is unsafe (issue #21): use " +
            "resolveDualUserIdVerified(userListVerbose, dumpsysUser, propertyId) " +
            "which requires cross-source CLONE type agreement.",
        ReplaceWith(
            "resolveDualUserIdVerified(userListVerbose, dumpsysUser, propertyId)",
        ),
    )
    fun resolveDualUserId(profiles: List<DualProfile>): Int {
        val candidates = profiles.filter {
            it.userId != 0 && it.label.contains("DUAL", ignoreCase = true)
        }
        return if (candidates.size == 1) candidates[0].userId else -1
    }

    // Verified dual-profile resolution (PR-1 stage 2, issue #21).
    // Parses both verbose channels and requires cross-source CLONE agreement
    // via SamsungProfileAdapter.resolveDual: Confirmed(HIGH) maps to its userId,
    // Unknown/Refused both map to -1 (fail closed, never guess a target).
    // The pm-list label is never read here (display-only by contract).
    //
    // @param userListVerbose raw `cmd user list -v` output.
    // @param dumpsysUser raw `dumpsys user` output.
    // @param propertyId optional system-property dual id (e.g. parsed from
    //   `getprop sys.dualapp.profile_id` via parseDualPropertyId); null means
    //   "no property claim" (missing/unreadable), which still allows a
    //   two-channel agreement to confirm (single-source never confirms).
    fun resolveDualUserIdVerified(
        userListVerbose: String,
        dumpsysUser: String,
        propertyId: Int? = null,
    ): Int {
        val candidates =
            SamsungProfileAdapter.parseUserListVerbose(userListVerbose) +
                SamsungProfileAdapter.parseDumpsysUser(dumpsysUser)
        return when (val resolution = SamsungProfileAdapter.resolveDual(candidates, propertyId)) {
            is DualResolution.Confirmed -> resolution.userId
            is DualResolution.Unknown -> -1
            is DualResolution.Refused -> -1
        }
    }

    // Serial-bound verified dual target (PR-33 R2, P1). Same channels and
    // fail-closed mapping as resolveDualUserIdVerified, but returns the
    // (userId, serial) target via SamsungProfileAdapter.resolveDualTarget:
    // null when the id is not Confirmed or when the two verbose channels do
    // not agree on one non-null serial for it. The pm-list label is never
    // read here (display-only by contract).
    //
    // @param userListVerbose raw `cmd user list -v` output.
    // @param dumpsysUser raw `dumpsys user` output.
    // @param propertyId optional system-property dual id (same contract as
    //   resolveDualUserIdVerified: null means "no property claim").
    fun resolveDualTargetVerified(
        userListVerbose: String,
        dumpsysUser: String,
        propertyId: Int? = null,
    ): VerifiedDualTarget? {
        val candidates =
            SamsungProfileAdapter.parseUserListVerbose(userListVerbose) +
                SamsungProfileAdapter.parseDumpsysUser(dumpsysUser)
        return SamsungProfileAdapter.resolveDualTarget(candidates, propertyId)
    }

    // Parses `getprop sys.dualapp.profile_id` output into a property claim.
    // Returns the numeric id on a clean integer payload, null when the property
    // is missing, empty, or in an unknown format (no claim, never throws).
    // Negative values mean "no claim" and are normalized to null so callers
    // never mistake them for a target.
    fun parseDualPropertyId(propOutput: String): Int? {
        val token = propOutput.trim()
        if (token.isEmpty()) return null
        // getprop echoes a single line; take the first line only.
        val first = token.lineSequence().first().trim()
        val id = first.toIntOrNull() ?: return null
        return if (id >= 0) id else null
    }

    // Parses `pm list users` output into one entry per UserInfo{...} line.
    // Returns all profiles found; resolve the dual one via resolveDualUserId().
    fun parseUsers(output: String): List<DualProfile> = parseUsersDetailed(output)

    // Full parse of `pm list users` output retaining the flag/type field of each
    // UserInfo{<id>:<label>:<flags>} entry (C1 safety audit, issue #2).
    // New function; parseUsers() above delegates to it so existing callers
    // keep working unchanged (they simply ignore the extra flags field).
    fun parseUsersDetailed(output: String): List<DualProfile> {
        val out = mutableListOf<DualProfile>()
        for (raw in output.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = USER_INFO.find(line) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val label = match.groupValues[2].trim()
            val flags = match.groupValues[3].trim()
            out.add(DualProfile(userId = id, label = label, flags = flags))
        }
        return out
    }

    // Parses `pm list packages [--user N]` output into package names.
    // Accepts canonical "package:com.foo" lines and skips anything else.
    fun parsePackages(output: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in output.lines()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val pkg = line.removePrefix("package:").trim()
            if (pkg.isNotEmpty()) out.add(pkg)
        }
        return out
    }

    // Trusted tri-state parse for `pm list packages` (PR-2 stage 1, issue
    // #22, additive only: existing parsePackages/isSuccess/verify paths are
    // untouched and keep their exact behavior).
    // Rules, in order:
    // 1. truncated flag (or a "[truncated]" marker surviving from a caller
    //    that still builds CmdResult without the flag) -> Unknown: the
    //    listing may have been cut off, so "no package: line" proves nothing.
    // 2. exit != 0 -> Unknown (detail keeps the code; retry/backoff policy
    //    belongs to the upper layer, never to this pure parser).
    // 3. error-semantic text in out/err -> Unknown. Same line-level C2 rule
    //    as isSuccess (EXEMPT_LINE skips package: echoes and install-success
    //    echoes so failure/exception-named packages never misfire, then
    //    ERROR_LINE matches Failure/Error/exception/SecurityException,
    //    denied/cannot/abort/invalid, unknown-package/not-installed, stack
    //    lines). Unlike isSuccess there is NO ALREADY_EXISTS exception here:
    //    that code is install-only idempotent-success; any Failure line in
    //    a listing makes the snapshot untrusted.
    // 4. otherwise parsePackages(out): empty -> TrustedEmpty (confirmed
    //    empty, format clean), non-empty -> TrustedFull(packages).
    fun parsePackagesTrusted(
        out: String,
        err: String,
        exit: Int,
        truncated: Boolean = false,
    ): TrustedList {
        if (truncated || out.contains("[truncated]") || err.contains("[truncated]")) {
            return TrustedList.Unknown("truncated: listing may be incomplete")
        }
        if (exit != 0) {
            return TrustedList.Unknown("verify failed (exit=$exit)")
        }
        for (raw in (out + "\n" + err).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (EXEMPT_LINE.any { it.containsMatchIn(line) }) continue
            if (ERROR_LINE.any { it.containsMatchIn(line) }) {
                return TrustedList.Unknown("error-semantic output: $line")
            }
        }
        val packages = parsePackages(out)
        return if (packages.isEmpty()) TrustedList.TrustedEmpty else TrustedList.TrustedFull(packages)
    }

    // CmdResult convenience overload: forwards the capture flag so callers
    // cannot forget it. Pure delegation, same rules as above.
    fun parsePackagesTrusted(res: CmdResult): TrustedList =
        parsePackagesTrusted(res.out, res.err, res.exit, res.truncated)

    // Builds `pm install-existing --user <dualId> <pkg>` to clone into the dual profile.
    fun buildInstallExisting(pkg: String, dualId: Int): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        return "pm install-existing --user $u $p"
    }

    // Builds `pm uninstall --user <dualId> <pkg>` to remove the dual clone only.
    fun buildUninstall(pkg: String, dualId: Int): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        return "pm uninstall --user $u $p"
    }

    // Builds `pm grant --user <dualId> <pkg> <permission>`.
    fun buildGrant(pkg: String, dualId: Int, permission: String): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        val perm = ShellEscaper.escapePermission(permission)
        return "pm grant --user $u $p $perm"
    }

    // Builds `appops set --user <dualId> <pkg> <op> <mode>`, e.g. mode allow/deny.
    fun buildAppOpsSet(pkg: String, dualId: Int, op: String, mode: String): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        val opToken = ShellEscaper.escapeOpToken(op, "op")
        val modeToken = ShellEscaper.escapeOpToken(mode, "mode")
        return "appops set --user $u $p $opToken $modeToken"
    }

    // Builds `pm path --user <dualId> <pkg>` for second-check verification.
    // Retained as public API for compatibility even though verifyOnProfiles
    // no longer calls it (third round: the re-check uses `pm list packages`
    // ground truth because `pm path` is unreliable inside the post-uninstall
    // teardown window). Kept, not deleted, so external callers keep compiling.
    fun buildPathCheck(pkg: String, dualId: Int): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        return "pm path --user $u $p"
    }

    // Builds `pm list packages --user <dualId>` to enumerate the dual profile's
    // ground truth (Phase C3, issue #9). Single call: refresh rebuilds `cloned`
    // from this instead of trusting local state, so external installs/removals
    // sync and a fresh ViewModel discovers pre-existing clones. Per-package
    // `pm path` probing alone cannot do this: it only confirms already-known
    // names. Output is parsed with parsePackages() above; same user-0 guard as
    // every other builder via escapeUserId.
    fun buildListPackages(dualId: Int): String {
        val u = ShellEscaper.escapeUserId(dualId)
        return "pm list packages --user $u"
    }

    // Success means: idempotent ALREADY_EXISTS, else exit 0 plus no
    // error-semantic line in combined output. Line-level (Phase C2, issue #8):
    // exempt lines (package: echoes, Success lines, "installed for user"
    // success echoes containing the package name) never participate in keyword
    // matching, so success payloads echoing failure/exception-named packages
    // are not misjudged; every other line is tested against ERROR_LINE.
    fun isSuccess(output: String, exit: Int): Boolean {
        if (ALREADY_EXISTS.containsMatchIn(output)) return true
        if (exit != 0) return false
        for (raw in output.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (EXEMPT_LINE.any { it.containsMatchIn(line) }) continue
            if (ERROR_LINE.any { it.containsMatchIn(line) }) return false
        }
        return true
    }

    // Legacy entry point kept for compatibility: parses key=value token lines.
    // Example: "package:/data/app/xxx basePackage=com.foo userId=150 enabled=true".
    fun parse(lines: List<String>): List<DualAppInfo> {
        val out = mutableListOf<DualAppInfo>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val pkg = extractValue(line, "basePackage")
                ?: extractPackageFallback(line) ?: continue
            val user = extractValue(line, "userId")?.toIntOrNull() ?: 0
            val enabled = extractValue(line, "enabled")?.toBooleanStrictOrNull() ?: false
            out.add(DualAppInfo(pkg, user, enabled))
        }
        return out
    }

    // Extracts key=value tokens from a whitespace-separated line.
    private fun extractValue(line: String, key: String): String? {
        for (token in line.split(" ")) {
            if (token.startsWith("$key=")) return token.substringAfter("=")
        }
        return null
    }

    // Fallback for plain "package:com.foo" lines from pm list packages.
    private fun extractPackageFallback(line: String): String? {
        if (!line.startsWith("package:")) return null
        return line.removePrefix("package:").trim().takeIf { it.isNotEmpty() }
    }
}
