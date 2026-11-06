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
// issue #22; stage 1 was additive-only, stage 2 consumers are
// verifyListProbe/listProbe via verifyState/listClonedPackages).
// Distinguishes a genuinely empty profile (TrustedEmpty) from an
// uncheckable channel (Unknown), so a later stage can stop folding
// "confirmed absent" and "cannot check" together.
// - TrustedFull(packages): clean listing containing these exact packages.
// - TrustedEmpty: clean listing with zero package: lines (confirmed empty).
// - Unknown(reason): truncated capture, non-zero exit, error-semantic
//   text, or unexpected-format/malformed-payload lines (same line-level
//   C2 rule as isSuccess, minus the install-only ALREADY_EXISTS
//   exception: any Failure line in a listing is untrusted).
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

    // Trusted-listing exempt subset (P22 fix m7): only package: echoes
    // and Success lines skip the error-keyword scan. The install-success
    // "installed for user" echo (EXEMPT_LINE[2]) is deliberately NOT exempt
    // on the listing path. Exemption is NOT trust: after the scan, the
    // step-4 structural whitelist still requires every non-blank stdout
    // line to be a legal package: line and stderr to be fully blank;
    // anything else is Unknown. isSuccess() keeps the full EXEMPT_LINE
    // unchanged.
    private val TRUSTED_EXEMPT_LINE = listOf(
        Regex("^\\s*package:", RegexOption.IGNORE_CASE),
        Regex("^\\s*success(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
    )

    // Trusted-path package payload allowlist (P22 fix M2): same charset as
    // ShellEscaper.SAFE_TOKEN. Checked only in parsePackagesTrusted;
    // parsePackages() itself stays tolerant for compatibility.
    private val PACKAGE_PAYLOAD = Regex("^[A-Za-z0-9._-]+$")

    // Line-level error scan shared by isSuccess() and parsePackagesTrusted()
    // (PR-2 Phase 2 dedup): skips blank lines and caller-chosen exempt lines,
    // then returns the first line matching ERROR_LINE, or null when clean.
    // The exempt set differs per path -- isSuccess() passes the full
    // EXEMPT_LINE (install-only "installed for user" echo included) and keeps
    // its ALREADY_EXISTS early-out; the trusted listing path passes
    // TRUSTED_EXEMPT_LINE and has NO ALREADY_EXISTS exception (any Failure
    // line in a listing is untrusted). Pure scan, no exit-code logic.
    private fun firstErrorLine(combined: String, exempt: List<Regex>): String? {
        for (raw in combined.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (exempt.any { it.containsMatchIn(line) }) continue
            if (ERROR_LINE.any { it.containsMatchIn(line) }) return line
        }
        return null
    }

    // True pm error signals (all case-insensitive unless noted). Each must be
    // error-semantic on its own line: Failure [...] lines, Error: lines,
    // Exception headers/stack lines, unknown-package/not-installed statements,
    // and denied/cannot/abort/invalid operation failures.
    //
    // Locale assumption (PR-2 Phase 3, R4): these patterns only match ENGLISH
    // pm output. On a non-English ROM an exit-0 dirty line in another language
    // can slip through as Trusted (known trade-off; the fail-closed direction
    // -- truncated / non-zero / malformed payload rules -- is unchanged, but
    // a foreign-language error sentence is not recognized as error-semantic).
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

    // Legacy dual-profile safety boundary (Phase C1, issue #2, P0; historical).
    // Superseded by resolveDualUserIdVerified() below (PR-1 stage 2): the
    // single source of truth is now the shared fetch in VerifiedIdentity.kt,
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

    // Verified dual-profile resolution (PR-1 stage 2, issue #21; P2-2 property
    // required, issue #21). Parses both verbose channels and requires
    // cross-source CLONE agreement via SamsungProfileAdapter.resolveDual:
    // Confirmed(HIGH) maps to its userId, Unknown/Refused both map to -1
    // (fail closed, never guess a target). The pm-list label is never read
    // here (display-only by contract).
    //
    // @param userListVerbose raw `cmd user list -v` output.
    // @param dumpsysUser raw `dumpsys user` output.
    // @param propertyId nullable system-property dual id (e.g. parsed from
    //   `getprop sys.dualapp.profile_id` via parseDualPropertyId). Phase C
    //   P2-2: property is required corroboration, not optional. null (missing/
    //   empty/unparseable, no PROP candidate) yields Unknown (fail closed),
    //   never a two-channel Confirm; a mismatched property yields Refused.
    //   A version that cannot read the property refuses explicitly as an
    //   unsupported version (#21 acceptance: "property missing/format unknown
    //   ... Unknown/Refused" and "unknown explicitly refuses writes").
    fun resolveDualUserIdVerified(
        userListVerbose: String,
        dumpsysUser: String,
        propertyId: Int? = null,
    ): Int = resolveDualTarget(userListVerbose, dumpsysUser, propertyId)?.userId ?: -1

    // Verified-target resolution (Phase D, issue #21): same single source as
    // resolveDualUserIdVerified but preserves the Phase B identity continuity
    // signals. Maps Confirmed(id, serial, parentId) -> VerifiedTarget,
    // Unknown/Refused -> null (fail closed, never guess a target).
    fun resolveDualTarget(
        userListVerbose: String,
        dumpsysUser: String,
        propertyId: Int? = null,
    ): VerifiedTarget? {
        val candidates =
            SamsungProfileAdapter.parseUserListVerbose(userListVerbose) +
                SamsungProfileAdapter.parseDumpsysUser(dumpsysUser)
        return when (val resolution = SamsungProfileAdapter.resolveDual(candidates, propertyId)) {
            is DualResolution.Confirmed ->
                VerifiedTarget(
                    userId = resolution.userId,
                    serial = resolution.serial,
                    parentId = resolution.parentId,
                )
            is DualResolution.Unknown -> null
            is DualResolution.Refused -> null
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
    // Strict single-line full match `^\s*(-?\d+)\s*$`: the whole payload
    // must be one integer token (surrounding whitespace allowed). A payload
    // with a second non-blank line, trailing tokens, or non-numeric text
    // yields null (no claim, never throws). Negative values mean "no claim"
    // and are normalized to null so callers never mistake them for a target.
    fun parseDualPropertyId(propOutput: String): Int? {
        val m = Regex("""^\s*(-?\d+)\s*$""").matchEntire(propOutput) ?: return null
        val id = m.groupValues[1].toIntOrNull() ?: return null
        return if (id >= 0) id else null
    }

    // Parses `pm list users` output into one entry per UserInfo{...} line.
    // Returns all profiles found; resolve the dual one via
    // resolveDualUserIdVerified() (the label-only resolveDualUserId() is
    // Deprecated, issue #21, and must not authorize new writes).
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
    // Prefix match is case-insensitive for consistency with the trusted
    // listing path (TRUSTED_EXEMPT_LINE) and parsePackagesTrusted's M2
    // payload check; real pm emits all-lowercase, so this only widens
    // tolerance without changing any lowercase behavior.
    fun parsePackages(output: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in output.lines()) {
            val line = raw.trim()
            if (!line.startsWith("package:", ignoreCase = true)) continue
            val pkg = line.substring("package:".length).trim()
            if (pkg.isNotEmpty()) out.add(pkg)
        }
        return out
    }

    // Trusted tri-state parse for `pm list packages` (PR-2 stage 1, issue
    // #22; stage 1 was additive-only, stage 2 now consumes it in
    // verifyListProbe/listProbe -- parsePackages/isSuccess keep exact behavior).
    // Rules, in order:
    // 1. truncated flag (or a TRUNCATION_MARKER surviving from a caller
    //    that still builds CmdResult without the flag -- legacy text
    //    fallback, TODO: drop the sniff once all producers set the flag) ->
    //    Unknown: the listing may have been cut off, so "no package: line"
    //    proves nothing.
    // TRUNCATION_MARKER itself lives in CmdGateway.kt (same package, core
    // single source of truth shared with the app gateways).
    // 2. exit != 0 -> Unknown (detail keeps the code plus the first
    //    stderr (else stdout) line for diagnosis; retry/backoff policy
    //    belongs to the upper layer, never to this pure parser).
    // 3. error-semantic text in out/err -> Unknown. Same line-level C2 rule
    //    as isSuccess, except the trusted listing scan only exempts package:
    //    echoes and Success lines -- the install-only "installed for user"
    //    echo (EXEMPT_LINE[2]) is NOT exempt here, so a stray install echo
    //    inside a listing is still scrutinized. There is NO ALREADY_EXISTS
    //    exception here: that code is install-only idempotent-success; any
    //    Failure line in a listing makes the snapshot untrusted.
    // 4. structural whitelist (P1 BLOCKER #1, issue #22 review): an
    //    authoritative full listing contains NOTHING but legal package:
    //    lines -- every non-blank stdout line must start with "package:"
    //    (case-insensitive, same as TRUSTED_EXEMPT_LINE) with a payload
    //    matching ^[A-Za-z0-9._-]+$ (same charset as
    //    ShellEscaper.SAFE_TOKEN), and stderr must be fully blank. Any
    //    other stdout line (usage text, non-English error, stray echo) ->
    //    Unknown("unexpected listing format: <first line>"); any non-blank
    //    stderr line -> Unknown("unexpected stderr: <first line>").
    //    Strict policy: only a whitelisted clean listing is
    //    absence/presence evidence. ROM-specific extra lines are NOT
    //    tolerated by default -- each must be proven and added here as an
    //    explicit allowlist entry (there is no other escape hatch).
    //    Trusted path only; parsePackages() itself stays tolerant.
    // 5. otherwise parsePackages(out): empty -> TrustedEmpty (confirmed
    //    empty); non-empty -> TrustedFull(packages). Reached only when the
    //    whitelist above passed, so both cases are authoritative.
    fun parsePackagesTrusted(
        out: String,
        err: String,
        exit: Int,
        truncated: Boolean = false,
    ): TrustedList {
        if (truncated || out.contains(TRUNCATION_MARKER) || err.contains(TRUNCATION_MARKER)) {
            // Preserve exit/hint on the truncated path (interrupt/timeout are
            // exit!=0 + truncated): exit==0 keeps the bare string.
            if (exit != 0) {
                val src = if (err.isBlank()) out else err
                val hint = src.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.trim()?.take(200)
                return if (hint != null) {
                    TrustedList.Unknown("truncated: listing may be incomplete (exit=$exit): $hint")
                } else {
                    TrustedList.Unknown("truncated: listing may be incomplete (exit=$exit)")
                }
            }
            return TrustedList.Unknown("truncated: listing may be incomplete")
        }
        if (exit != 0) {
            val src = if (err.isBlank()) out else err
            val hint = src.lineSequence().firstOrNull { it.isNotBlank() }
                ?.trim()?.take(200)
            return if (hint != null) {
                TrustedList.Unknown("verify failed (exit=$exit): $hint")
            } else {
                TrustedList.Unknown("verify failed (exit=$exit)")
            }
        }
        // Shared line scan (see firstErrorLine): trusted exempt subset only,
        // no ALREADY_EXISTS exception -- any Failure line here is untrusted.
        val errorLine = firstErrorLine(out + "\n" + err, TRUSTED_EXEMPT_LINE)
        if (errorLine != null) {
            return TrustedList.Unknown("error-semantic output: ${errorLine.take(200)}")
        }
        // P1 structural whitelist (fail closed, issue #22 review): an
        // authoritative listing carries nothing but legal package: lines.
        // TRUSTED_EXEMPT stays unchanged above (package:/Success lines never
        // reach the error scan, so failure-named packages keep their
        // regression); this is an independent subsequent gate, not a second
        // error scan. Prefix match is case-insensitive for consistency with
        // TRUSTED_EXEMPT_LINE (real pm emits all-lowercase; this only
        // keeps the check self-consistent, still fail-closed).
        for (raw in out.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("package:", ignoreCase = true)) {
                return TrustedList.Unknown("unexpected listing format: ${line.take(200)}")
            }
            // M2 payload check (trusted path only; parsePackages() itself
            // stays tolerant): must match ^[A-Za-z0-9._-]+$, same charset
            // as ShellEscaper.SAFE_TOKEN.
            val payload = line.substring("package:".length).trim()
            if (!PACKAGE_PAYLOAD.matches(payload)) {
                return TrustedList.Unknown("malformed package line: ${line.take(200)}")
            }
        }
        // Unexplained stderr residue: the truncation marker/flag and
        // error-semantic lines already returned above, so any remaining
        // non-blank stderr line (warning, notice, partial-result marker the
        // error rule did not classify) is a dirty channel, never ignored --
        // with or without packages.
        val firstErr = err.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (firstErr != null) {
            return TrustedList.Unknown("unexpected stderr: ${firstErr.take(200)}")
        }
        val packages = parsePackages(out)
        if (packages.isEmpty()) {
            return TrustedList.TrustedEmpty
        }
        return TrustedList.TrustedFull(packages)
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
    // 需 verified-dual id，目前無生產調用 (builder retained for tests).
    fun buildGrant(pkg: String, dualId: Int, permission: String): String {
        val p = ShellEscaper.escapePackage(pkg)
        val u = ShellEscaper.escapeUserId(dualId)
        val perm = ShellEscaper.escapePermission(permission)
        return "pm grant --user $u $p $perm"
    }

    // Builds `appops set --user <dualId> <pkg> <op> <mode>`, e.g. mode allow/deny.
    // 需 verified-dual id，目前無生產調用 (builder retained for tests).
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
        // Shared line scan (see firstErrorLine): full EXEMPT_LINE, with the
        // ALREADY_EXISTS idempotent-success early-out above intact.
        return firstErrorLine(output, EXEMPT_LINE) == null
    }

    // Identity-channel gate (Phase 2, issue #21; P2-1 header strip, issue #21).
    // Strict variant of isSuccess for the verified-identity channels
    // (`cmd user list -v`, `dumpsys user`, `getprop sys.dualapp.profile_id`).
    // Differences from isSuccess (which stays the install/uninstall/
    // list-packages pm-channel rule; profilesSnapshot and the Elevator
    // pm-list gates now use the dedicated isListUsersOutputOk below):
    // - No INSTALL_FAILED_ALREADY_EXISTS exemption: idempotent-success is a
    //   pm install semantic; an identity dump carrying an install code (or
    //   any Failure line) is still error-semantic output and must fail the
    //   channel closed instead of confirming an id.
    // - No EXEMPT_LINE filtering: package:/path echoes and "installed for
    //   user" success echoes are pm success payloads, never legitimate
    //   identity content; every non-blank line is tested against ERROR_LINE.
    // - P2-1 header strip: all `UserInfo{...}` header substrings are removed
    //   before scanning, so a renamed label carrying error keywords
    //   (`UserInfo{95:Error:400}`, `{95:Failure:400}`, `{10:not found:30}`)
    //   can never trigger ERROR_LINE. The label is display-only by contract
    //   (renames do not change authorization); only body lines carry
    //   error semantics. A genuine body `Failure [...]` line still fails.
    // exit != 0 is always failure here (unlike isSuccess, where the
    // ALREADY_EXISTS exemption is checked before exit and can still report
    // success on a non-zero exit).
    private val IDENTITY_HEADER_STRIP = Regex("UserInfo\\{\\d+:[^}:\\r\\n]*:[^}\\r\\n]*\\}")

    private fun checkStrictLines(output: String, exit: Int): Boolean {
        if (exit != 0) return false
        for (raw in output.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (ERROR_LINE.any { it.containsMatchIn(line) }) return false
        }
        return true
    }

    fun isIdentityChannelOk(output: String, exit: Int): Boolean {
        val sanitized = IDENTITY_HEADER_STRIP.replace(output, "")
        return checkStrictLines(sanitized, exit)
    }

    // `pm list users` strict gate (Phase B P1-2, issue #21; owner re-review R3
    // blocking). Independent strict判定, no longer delegating to isSuccess:
    // exit-first (any non-zero exit fails), header-strip (same
    // IDENTITY_HEADER_STRIP shape as isIdentityChannelOk, so a renamed label
    // carrying error keywords stays display-only), then every non-blank body
    // line tested against ERROR_LINE with NO INSTALL_FAILED_ALREADY_EXISTS
    // exemption and NO EXEMPT_LINE filtering. A genuine body `Failure [...]`
    // line (stdout or stderr) fails even at exit 0, and
    // `exit=1 + Failure [INSTALL_FAILED_ALREADY_EXISTS]` can no longer pass
    // as Loaded (it previously inherited the isSuccess exemption and passed
    // before the exit check, letting a guard be bypassed via snapshot fake
    // Loaded). Relation: isSuccess keeps the install-channel
    // idempotent-success semantics (ALREADY_EXISTS + EXEMPT_LINE) unchanged;
    // isIdentityChannelOk shares this same判定 shape (exit-first,
    // header-strip, no exemptions) for the verified-identity channels
    // (`cmd user list -v` / `dumpsys user` / `getprop`), while this gate
    // serves only the `pm list users` channel.
    fun isListUsersOutputOk(output: String, exit: Int): Boolean {
        val sanitized = IDENTITY_HEADER_STRIP.replace(output, "")
        return checkStrictLines(sanitized, exit)
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
