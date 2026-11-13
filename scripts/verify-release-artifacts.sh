#!/usr/bin/env bash
# Verify that built APK artifacts still contain the required release-critical
# classes/resources. Used by both PR CI (android.yml, debug APK) and the
# release workflow (release.yml, release APKs) to avoid drift.
#
# Usage: verify-release-artifacts.sh <apk> [<apk>...]
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "::error::usage: $0 <apk>..." >&2
  exit 2
fi

APP_VER=""
if [ -f "app/build.gradle.kts" ]; then
  APP_VER="$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1 | tr -d ' \r' || true)"
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

  # 1b. Packaged manifest provisions exactly one GuardXposedProvider and ZERO
  #     references to the libxposed service XposedProvider. aapt2 dump text is
  #     grepped name-wise (a provider's android:name appears exactly once per
  #     provider node). Loud hard-fail, never a skip: CI has the SDK, and a
  #     silent pass here would mask a manifest-merge regression shipping the
  #     unguarded (exported, no-permission) provider. Variant handling: PR CI
  #     invokes this on the debug APK (debug applicationIdSuffix=.debug), so the
  #     expected authority is derived from the APK path — the .debug authority
  #     is only accepted for *-debug*.apk / */debug/* paths; anything else
  #     expects the release authority (com.galaxydual.plus.XposedService, no
  #     .debug). A release-expected APK carrying the .debug authority fails.
  if ! MANIFEST_DUMP="$("$AAPT2" dump xmltree "$apk" --file AndroidManifest.xml 2>/dev/null)"; then
    fail "aapt2 dump of AndroidManifest.xml failed for $apk"
  fi
  if [ -z "$MANIFEST_DUMP" ]; then
    fail "aapt2 dump of AndroidManifest.xml produced no output for $apk"
  fi
  guard_nodes="$(printf '%s\n' "$MANIFEST_DUMP" | grep -o 'GuardXposedProvider' | wc -l | tr -d ' ' || true)"
  if [ "${guard_nodes:-0}" -ne 1 ]; then
    fail "expected exactly one GuardXposedProvider node in packaged manifest of $apk, found ${guard_nodes:-0}"
  fi
  if printf '%s\n' "$MANIFEST_DUMP" | grep -qF 'io.github.libxposed.service.XposedProvider'; then
    fail "packaged manifest of $apk references io.github.libxposed.service.XposedProvider (unguarded provider must not ship)"
  fi
  case "$apk" in
    *-debug*.apk|*/debug/*) EXPECTED_AUTHORITY="com.galaxydual.plus.debug.XposedService" ;;
    *)                      EXPECTED_AUTHORITY="com.galaxydual.plus.XposedService" ;;
  esac
  if ! printf '%s\n' "$MANIFEST_DUMP" | grep -qF "$EXPECTED_AUTHORITY"; then
    fail "GuardXposedProvider authority '$EXPECTED_AUTHORITY' not found in packaged manifest of $apk"
  fi
  if [ "$EXPECTED_AUTHORITY" = "com.galaxydual.plus.XposedService" ] \
    && printf '%s\n' "$MANIFEST_DUMP" | grep -qF 'com.galaxydual.plus.debug.XposedService'; then
    fail "release-expected APK carries the debug authority (com.galaxydual.plus.debug.XposedService): $apk"
  fi
  echo "FOUND single GuardXposedProvider at $EXPECTED_AUTHORITY; no libxposed XposedProvider reference"

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
