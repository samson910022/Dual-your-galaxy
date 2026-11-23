#!/usr/bin/env bash
# Verify that built APK artifacts still contain the required release-critical
# classes/resources. Used by both PR CI (android.yml, debug APK) and the
# release workflow (release.yml, release APKs) to avoid drift.
#
# Usage: verify-release-artifacts.sh <apk> [<apk>...]
# NOTE: run from the repository root — several checks read repo-relative
# paths (app/build.gradle.kts). The checker path itself is resolved via the
# script's location, so this script works from any CWD; the repo-root
# assumption is only for repo files.
set -euo pipefail

# fail() is defined before its first use (the versionName gate below calls it).
# Body is byte-identical to the historical definition.
fail() {
  echo "::error::$*" >&2
  exit 1
}

# DEX-entry guard retained from the historical grep-based checks: fail fast
# when an APK carries no classes*.dex member. The class-presence decision
# itself is delegated to scripts/check-dex-classdefs.py (real class_def_item
# counts, not raw descriptor-string hits) — there is deliberately no grep
# fallback here (issue #67).
require_dex_entries() {
  local apk="$1" dex_count
  # Anchor to the whole archive member name, matching the parser's
  # DEX_NAME_RE.fullmatch: 'assets/classes.dex', 'myclasses.dex' and
  # 'classes.dex.bak' must NOT satisfy the guard. Use `unzip -Z1` (names only)
  # and `grep -Ec` with `|| true` so a zero count exits the substitution
  # cleanly under `set -euo pipefail` (grep returns 1 when count is 0); the
  # resulting "0" is then caught by the explicit check below.
  dex_count="$(unzip -Z1 "$apk" | grep -Ec '^classes[0-9]*\.dex$' || true)"
  if [ "${dex_count:-0}" -eq 0 ]; then
    fail "No DEX entries found in $apk"
  fi
}

if [ "$#" -eq 0 ]; then
  echo "::error::usage: $0 <apk>..." >&2
  exit 2
fi

APP_VER=""
if [ -f "app/build.gradle.kts" ]; then
  APP_VER="$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1 | tr -d ' \r' || true)"
fi
if [ -z "$APP_VER" ]; then
  # The module.prop version sync check below is the only guard against shipping
  # an app bundle whose versionName disagrees with the xposed module.prop.
  # Fail closed when it cannot be run — never silently degrade.
  fail "unable to parse versionName from app/build.gradle.kts (wrong CWD, or the file is missing/malformed)"
fi

AAPT2=""
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  AAPT2="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n 1)/aapt2"
fi
if [ -z "$AAPT2" ] || [ ! -x "$AAPT2" ]; then
  AAPT2="$(command -v aapt2 2>/dev/null || true)"
fi
if [ -z "$AAPT2" ] || [ ! -x "$AAPT2" ]; then
  echo "::error::aapt2 not found; cannot inspect packaged manifest" >&2
  exit 1
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVIDER_PARITY_TMP="$(mktemp -d "${TMPDIR:-/tmp}/provider-parity.XXXXXX")"
trap 'rm -rf "$PROVIDER_PARITY_TMP"' EXIT

for apk in "$@"; do
  if [ ! -f "$apk" ]; then
    fail "APK not found: $apk"
  fi
  echo "=== verifying $apk ==="

  # APK package + manifest dump, shared by step 1 (ShizukuProvider
  # element-scope gate) and step 1b (GuardXposedProvider parity). Derived once
  # from the APK itself (aapt2 dump packagename), never from the filename — a
  # renamed *-debug*.apk built from release code must not slip the debug
  # authority past the release gate. The mapping is exact and anything else
  # fails closed: io.github.samson910022.galaxydualplus.debug -> debug authorities;
  # io.github.samson910022.galaxydualplus -> release authorities. The legacy ids
  # com.galaxydual.plus / com.galaxydual.plus.debug fail closed as "old
  # applicationId identity"; everything else: hard fail.
  if ! APK_PACKAGE="$("$AAPT2" dump packagename "$apk" 2>/dev/null)"; then
    fail "aapt2 dump packagename failed for $apk"
  fi
  APK_PACKAGE="$(printf '%s' "$APK_PACKAGE" | tr -d '[:space:]')"
  case "$APK_PACKAGE" in
    io.github.samson910022.galaxydualplus.debug)
      EXPECTED_AUTHORITY="io.github.samson910022.galaxydualplus.debug.XposedService"
      EXPECTED_AUTHORITY_SHIZUKU="io.github.samson910022.galaxydualplus.debug.shizuku" ;;
    io.github.samson910022.galaxydualplus)
      EXPECTED_AUTHORITY="io.github.samson910022.galaxydualplus.XposedService"
      EXPECTED_AUTHORITY_SHIZUKU="io.github.samson910022.galaxydualplus.shizuku" ;;
    com.galaxydual.plus|com.galaxydual.plus.debug)
      # Pre-migration identity: never let a stale/pre-migration APK through the
      # release gate just because its authorities happen to parse. Fail closed.
      fail "old applicationId identity '$APK_PACKAGE' in $apk (migrated id is io.github.samson910022.galaxydualplus[.debug])" ;;
    *) fail "unsupported APK package name '$APK_PACKAGE' in $apk (expected exactly 'io.github.samson910022.galaxydualplus.debug' or 'io.github.samson910022.galaxydualplus'; the old id com.galaxydual.plus[.debug] is rejected above)" ;;
  esac

  # 0b. Packaged identity parity: module.prop id must equal the APK's RELEASE
  #     identity (applicationId with a trailing ".debug" stripped). This is the artifact-level
  #     companion to scripts/check-app-identity.sh (which pins the source tree):
  #     a stale module.prop baked into the APK with the OLD id must not pass the
  #     release gate even when the source has been migrated. Fail closed on a
  #     missing/empty or mismatched id. Placed before the manifest dump so a
  #     wrong identity is rejected cheaply and unambiguously.
  MOD_ID="$(unzip -p "$apk" META-INF/xposed/module.prop 2>/dev/null | sed -n 's/^id=//p' | head -n 1 | tr -d ' \r' || true)"
  # module.prop always carries the RELEASE id. A debug APK's applicationId has
  # the ".debug" suffix (app/build.gradle.kts applicationIdSuffix), so compare
  # against the release identity derived by stripping that suffix.
  EXPECTED_MOD_ID="${APK_PACKAGE%.debug}"
  if [ -z "$MOD_ID" ]; then
    fail "module.prop id missing in $apk (expected '$EXPECTED_MOD_ID')"
  fi
  if [ "$MOD_ID" != "$EXPECTED_MOD_ID" ]; then
    fail "module.prop id ($MOD_ID) != APK release identity ($EXPECTED_MOD_ID, APK applicationId $APK_PACKAGE) in $apk"
  fi
  echo "FOUND module.prop id == APK release identity ($MOD_ID)"
  MANIFEST_DUMP_FILE="$PROVIDER_PARITY_TMP/$(basename -- "$apk").xmltree.txt"
  if ! "$AAPT2" dump xmltree "$apk" --file AndroidManifest.xml > "$MANIFEST_DUMP_FILE" 2>/dev/null; then
    fail "aapt2 dump of AndroidManifest.xml failed for $apk"
  fi
  if [ ! -s "$MANIFEST_DUMP_FILE" ]; then
    fail "aapt2 dump of AndroidManifest.xml produced no output for $apk"
  fi

  # 1. Packaged manifest declares exactly one ShizukuProvider <provider> with
  #    the full element-scoped contract (all five on the SAME element):
  #      android:name EXACTLY "rikka.shizuku.ShizukuProvider"
  #      android:authorities EXACTLY "<applicationId>.shizuku"
  #        (debug: io.github.samson910022.galaxydualplus.debug.shizuku; release:
  #         io.github.samson910022.galaxydualplus.shizuku — derived from the actual
  #         applicationId, same way EXPECTED_AUTHORITY is derived; the debug
  #         authority shows up verbatim in the merged debug manifest)
  #      android:exported="true"
  #      android:multiprocess="false"
  #      android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
  #    Parsed from the aapt2 xmltree dump by scripts/check-shizuku-provider.py
  #    (the element/attribute idiom is shared with scripts/check-provider-parity.py:
  #    an `E:` line sets the current element; `A: .../android:<name>(0x<hex>)=<value>`
  #    lines set fields on it). An unanchored substring match would also admit
  #    the class name inside an unrelated attribute value, and attribute lines
  #    alone say nothing about WHICH element carries them. Fails closed
  #    (::error:: + exit 1) on: a missing provider, a wrong
  #    authority/exported/multiprocess/permission, or any parse oddity.
  #    Division of labor: this step pins the ShizukuProvider element-scoped
  #    (name+authority+exported+multiprocess+permission) on the packaged
  #    manifest; step 1b pins the GuardXposedProvider (name+authority parity,
  #    via scripts/check-provider-parity.py); step 2 pins the ShizukuProvider
  #    class in the DEX. ShizukuProvider is not a trust boundary for
  #    GalaxyExecService (see the sharedUserId note in AndroidManifest.xml).
  #    Fixtures in scripts/fixtures/shizuku-provider/ run via
  #    scripts/check-shizuku-provider.test.sh (wired into android.yml build job
  #    next to the provider-parity self-test); keep the two in sync in the
  #    same change.
  if ! python3 "$SCRIPT_DIR/check-shizuku-provider.py" --aapt2-xmltree "$MANIFEST_DUMP_FILE" --authority "$EXPECTED_AUTHORITY_SHIZUKU"; then
    fail "ShizukuProvider element-scoped manifest gate failed for $apk"
  fi

  # 1d. Packaged manifest exports ONLY the allowlisted components (inventory
  #     gate / new-surface tripwire). Step 1 above pins the ShizukuProvider
  #     element-scoped, step 1b pins the GuardXposedProvider (name + authority
  #     parity) — but NEITHER sees an ADDITIONAL exported <provider>/<service>/
  #     <receiver>/<activity> at an unpinned authority/name, and neither
  #     sees a weakened GuardXposedProvider.kt (that remains with human
  #     review + CODEOWNERS). Enforced here by parsing the SAME
  #     $MANIFEST_DUMP_FILE (aapt2 mode; scripts/check-exported-components.py,
  #     fixtures and the checker's regression net run via
  #     scripts/check-exported-components.test.sh (wired into android.yml build
  #     job next to the provider-parity self-test); keep the two in sync in the
  #     same change.). Loud hard-fail, never a skip.
  if ! python3 "$SCRIPT_DIR/check-exported-components.py" --aapt2-xmltree "$MANIFEST_DUMP_FILE"; then
    fail "exported-components inventory gate failed for $apk"
  fi

  # 1b. Packaged manifest provisions exactly one GuardXposedProvider at the
  #     expected authority — name and authority on the SAME <provider>
  #     element, exported=true, no android:permission, zero libxposed
  #     XposedProvider nodes anywhere, and zero wrong-element occurrences of
  #     the GuardXposedProvider name (e.g. an <activity>). APK_PACKAGE /
  #     EXPECTED_AUTHORITY / MANIFEST_DUMP_FILE were derived above (shared
  #     with step 1). Enforced by parsing the aapt2 xmltree dump
  #     (scripts/check-provider-parity.py; fixtures in
  #     scripts/fixtures/provider-parity/ run via scripts/check-provider-parity.test.sh).
  #     Loud hard-fail, never a skip: CI has the SDK, and a
  #     silent pass here would mask a manifest-merge regression shipping the
  #     unguarded (exported, no-permission) provider. Variant handling: the
  #     expected authority is derived from the APK's ACTUAL applicationId
  #     (aapt2 dump packagename), never from the APK filename — a renamed
  #     *-debug*.apk built from release code must not slip the debug
  #     authority past the release gate (PR CI invokes this on the debug
  #     APK, whose applicationIdSuffix=.debug; the release APK carries
  #     io.github.samson910022.galaxydualplus, no .debug). The mapping is exact and anything
  #     else fails closed: io.github.samson910022.galaxydualplus.debug -> debug authority;
  #     io.github.samson910022.galaxydualplus -> release authority (and only a release-
  #     expected APK carrying the .debug authority fails the leak guard).
  if ! python3 "$SCRIPT_DIR/check-provider-parity.py" --aapt2-xmltree "$MANIFEST_DUMP_FILE" --authority "$EXPECTED_AUTHORITY"; then
    fail "GuardXposedProvider provider parity check failed for $apk"
  fi
  if [ "$EXPECTED_AUTHORITY" = "io.github.samson910022.galaxydualplus.XposedService" ] \
    && grep -qF 'io.github.samson910022.galaxydualplus.debug.XposedService' "$MANIFEST_DUMP_FILE"; then
    fail "release-expected APK carries the debug authority (io.github.samson910022.galaxydualplus.debug.XposedService): $apk"
  fi
  echo "FOUND single GuardXposedProvider at $EXPECTED_AUTHORITY; no libxposed XposedProvider reference"

  # 1c. APK binary manifest has no sharedUserId (UTF-16LE + UTF-8 string-pool
  #     scan of the raw bytes; same check as android.yml's D3(b)). The source
  #     gate (C4) cannot catch a manifest transform or library merge that
  #     injects android:sharedUserId after source — the binary manifest in the
  #     shipped APK is what runs (uid gate would admit siblings). The embedded
  #     python prints OK or FAIL itself; exit 1 routes to fail(). Decoded
  #     strings are lowercased and matched against 'shareduserid' so the scan
  #     is case-insensitive (same check as android.yml's D3(b)).
  if unzip -p "$apk" AndroidManifest.xml | python3 -c '
import sys
data = sys.stdin.buffer.read()
if not data:
    print("FAIL: APK binary AndroidManifest.xml is empty or unreadable")
    sys.exit(1)
t16 = data.decode("utf-16-le", errors="ignore").lower()
t8 = data.decode("utf-8", errors="ignore").lower()
if "shareduserid" in t16 or "shareduserid" in t8:
    print("FAIL: sharedUserId string found in APK binary AndroidManifest.xml")
    sys.exit(1)
print("OK: no sharedUserId in APK binary AndroidManifest.xml")
' ; then
    :
  else
    fail "sharedUserId scan failed or matched for $apk"
  fi

  # 2. ShizukuProvider survives into the released DEX as a real class_def.
  #    Issue #67: `grep -aFc 'Lrikka/shizuku/ShizukuProvider;'` also matches a
  #    bare descriptor string in the DEX string table (e.g. left by a type
  #    reference), so an APK with zero class_def_item entries for the class
  #    passed the gate. scripts/check-dex-classdefs.py resolves
  #    class_def_item -> type_ids -> string_ids and counts actual definitions;
  #    no grep fallback is kept. The DEX-entry presence guard + fail-fast
  #    semantics are unchanged (require_dex_entries above).
  require_dex_entries "$apk"
  if ! python3 "$SCRIPT_DIR/check-dex-classdefs.py" "$apk" 'Lrikka/shizuku/ShizukuProvider;'; then
    fail "ShizukuProvider class_def not found in DEX of $apk"
  fi

  # 3. Exact Xposed archive members exist (no suffix false-positives).
  for entry in META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list; do
    count="$(unzip -Z1 "$apk" | grep -Fxc "$entry" || true)"
    if [ "${count:-0}" -eq 0 ]; then
      fail "$entry missing in $apk"
    fi
    echo "FOUND $entry"
  done

  # 4. java_init.list points at the exact DualHook class name.
  init_count="$(unzip -p "$apk" META-INF/xposed/java_init.list | grep -Fxc "com.galaxydual.hook.DualHook" || true)"
  if [ "${init_count:-0}" -eq 0 ]; then
    fail "DualHook entry missing in META-INF/xposed/java_init.list of $apk"
  fi
  echo "FOUND DualHook entry in java_init.list"

  # 5. DualHook survives into the DEX as a real class_def (same #67 hardening
  #    as step 2: class_def parsing, no descriptor-string grep fallback).
  require_dex_entries "$apk"
  if ! python3 "$SCRIPT_DIR/check-dex-classdefs.py" "$apk" 'Lcom/galaxydual/hook/DualHook;'; then
    fail "DualHook class_def not found in DEX of $apk"
  fi

  # 6. scope.list carries exactly the canonical two scopes.
  actual_scope="$(unzip -p "$apk" META-INF/xposed/scope.list)"
  expected_scope="$(printf 'system\ncom.samsung.android.da.daagent')"
  if [ "$actual_scope" != "$expected_scope" ]; then
    fail "scope.list mismatch in $apk"
  fi
  echo "FOUND matching scope.list"

  # 7. module.prop fail-safe marker present.
  if ! unzip -p "$apk" META-INF/xposed/module.prop | grep -F "exceptionMode=protective" >/dev/null; then
    fail "exceptionMode=protective missing in module.prop of $apk"
  fi
  echo "FOUND exceptionMode=protective"

  # 8. module.prop version matches app versionName (when known).
  MOD_VER="$(unzip -p "$apk" META-INF/xposed/module.prop | sed -n 's/^version=//p' | head -n 1 | tr -d ' \r' || true)"
  if [ -n "$APP_VER" ]; then
    if [ "$APP_VER" != "$MOD_VER" ]; then
      fail "module.prop version ($MOD_VER) != app versionName ($APP_VER) in $apk"
    fi
    echo "FOUND version match ($MOD_VER)"
  else
    echo "WARN: app versionName unknown; skipping version sync check"
  fi

  echo "PASS: $apk"
done

echo "ALL APK ARTIFACT CHECKS PASSED"
