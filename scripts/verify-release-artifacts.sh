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

fail() {
  echo "::error::$*" >&2
  exit 1
}

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVIDER_PARITY_TMP="$(mktemp -d "${TMPDIR:-/tmp}/provider-parity.XXXXXX")"
trap 'rm -rf "$PROVIDER_PARITY_TMP"' EXIT

for apk in "$@"; do
  if [ ! -f "$apk" ]; then
    fail "APK not found: $apk"
  fi
  echo "=== verifying $apk ==="

  # 1. Packaged manifest declares ShizukuProvider (not just source).
  if [ "$("$AAPT2" dump xmltree "$apk" --file AndroidManifest.xml 2>/dev/null | grep -Fc "rikka.shizuku.ShizukuProvider" || true)" -gt 0 ]; then
    echo "FOUND ShizukuProvider in packaged manifest"
  else
    fail "ShizukuProvider not found in packaged manifest of $apk"
  fi

  # 1b. Packaged manifest provisions exactly one GuardXposedProvider at the
  #     expected authority — name and authority on the SAME <provider>
  #     element, exported=true, no android:permission, zero libxposed
  #     XposedProvider nodes anywhere, and zero wrong-element occurrences of
  #     the GuardXposedProvider name (e.g. an <activity>). Enforced by parsing
  #     the aapt2 xmltree dump (scripts/check-provider-parity.py; fixtures in
  #     scripts/fixtures/provider-parity/ run via scripts/check-provider-parity.test.sh).
  #     Loud hard-fail, never a skip: CI has the SDK, and a
  #     silent pass here would mask a manifest-merge regression shipping the
  #     unguarded (exported, no-permission) provider. Variant handling: the
  #     expected authority is derived from the APK's ACTUAL applicationId
  #     (aapt2 dump packagename), never from the APK filename — a renamed
  #     *-debug*.apk built from release code must not slip the debug
  #     authority past the release gate (PR CI invokes this on the debug
  #     APK, whose applicationIdSuffix=.debug; the release APK carries
  #     com.galaxydual.plus, no .debug). The mapping is exact and anything
  #     else fails closed: com.galaxydual.plus.debug -> debug authority;
  #     com.galaxydual.plus -> release authority (and only a release-
  #     expected APK carrying the .debug authority fails the leak guard).
  if ! APK_PACKAGE="$("$AAPT2" dump packagename "$apk" 2>/dev/null)"; then
    fail "aapt2 dump packagename failed for $apk"
  fi
  APK_PACKAGE="$(printf '%s' "$APK_PACKAGE" | tr -d '[:space:]')"
  case "$APK_PACKAGE" in
    com.galaxydual.plus.debug) EXPECTED_AUTHORITY="com.galaxydual.plus.debug.XposedService" ;;
    com.galaxydual.plus)       EXPECTED_AUTHORITY="com.galaxydual.plus.XposedService" ;;
    *) fail "unsupported APK package name '$APK_PACKAGE' in $apk (expected exactly 'com.galaxydual.plus.debug' or 'com.galaxydual.plus')" ;;
  esac
  MANIFEST_DUMP_FILE="$PROVIDER_PARITY_TMP/$(basename -- "$apk").xmltree.txt"
  if ! "$AAPT2" dump xmltree "$apk" --file AndroidManifest.xml > "$MANIFEST_DUMP_FILE" 2>/dev/null; then
    fail "aapt2 dump of AndroidManifest.xml failed for $apk"
  fi
  if [ ! -s "$MANIFEST_DUMP_FILE" ]; then
    fail "aapt2 dump of AndroidManifest.xml produced no output for $apk"
  fi
  if ! python3 "$SCRIPT_DIR/check-provider-parity.py" --aapt2-xmltree "$MANIFEST_DUMP_FILE" --authority "$EXPECTED_AUTHORITY"; then
    fail "GuardXposedProvider provider parity check failed for $apk"
  fi
  if [ "$EXPECTED_AUTHORITY" = "com.galaxydual.plus.XposedService" ] \
    && grep -qF 'com.galaxydual.plus.debug.XposedService' "$MANIFEST_DUMP_FILE"; then
    fail "release-expected APK carries the debug authority (com.galaxydual.plus.debug.XposedService): $apk"
  fi
  echo "FOUND single GuardXposedProvider at $EXPECTED_AUTHORITY; no libxposed XposedProvider reference"

  # 1c. APK binary manifest has no sharedUserId (UTF-16LE + UTF-8 string-pool
  #     scan of the raw bytes; same check as android.yml's D3(b)). The source
  #     gate (C4) cannot catch a manifest transform or library merge that
  #     injects android:sharedUserId after source — the binary manifest in the
  #     shipped APK is what runs (uid gate would admit siblings). The embedded
  #     python prints OK or FAIL itself; exit 1 routes to fail().
  if unzip -p "$apk" AndroidManifest.xml | python3 -c '
import sys
data = sys.stdin.buffer.read()
if not data:
    print("FAIL: APK binary AndroidManifest.xml is empty or unreadable")
    sys.exit(1)
t16 = data.decode("utf-16-le", errors="ignore")
t8 = data.decode("utf-8", errors="ignore")
if "sharedUserId" in t16 or "sharedUserId" in t8:
    print("FAIL: sharedUserId string found in APK binary AndroidManifest.xml")
    sys.exit(1)
print("OK: no sharedUserId in APK binary AndroidManifest.xml")
' ; then
    :
  else
    fail "sharedUserId scan failed or matched for $apk"
  fi

  # 2. ShizukuProvider survives into the released DEX (exact descriptor).
  FOUND=0
  DEX_COUNT=0
  while IFS= read -r dex; do
    [ -z "$dex" ] && continue
    DEX_COUNT=$((DEX_COUNT + 1))
    hits="$(unzip -p "$apk" "$dex" | grep -aFc 'Lrikka/shizuku/ShizukuProvider;' || true)"
    if [ "${hits:-0}" -gt 0 ]; then
      echo "FOUND Lrikka/shizuku/ShizukuProvider; in $dex"
      FOUND=1
    fi
  done < <(unzip -l "$apk" | grep -o 'classes[0-9]*\.dex' | sort -u)
  if [ "$DEX_COUNT" -eq 0 ]; then
    fail "No DEX entries found in $apk"
  fi
  if [ "$FOUND" -eq 0 ]; then
    fail "ShizukuProvider class descriptor not found in DEX of $apk"
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

  # 5. DualHook survives into the DEX (exact descriptor).
  FOUND=0
  DEX_COUNT=0
  while IFS= read -r dex; do
    [ -z "$dex" ] && continue
    DEX_COUNT=$((DEX_COUNT + 1))
    hits="$(unzip -p "$apk" "$dex" | grep -aFc 'Lcom/galaxydual/hook/DualHook;' || true)"
    if [ "${hits:-0}" -gt 0 ]; then
      echo "FOUND Lcom/galaxydual/hook/DualHook; in $dex"
      FOUND=1
    fi
  done < <(unzip -l "$apk" | grep -o 'classes[0-9]*\.dex' | sort -u)
  if [ "$DEX_COUNT" -eq 0 ]; then
    fail "No DEX entries found in $apk"
  fi
  if [ "$FOUND" -eq 0 ]; then
    fail "DualHook class descriptor not found in DEX of $apk"
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
