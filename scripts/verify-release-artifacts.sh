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

  # 2. ShizukuProvider survives into the released DEX (fully-qualified slash form).
  FOUND=0
  DEX_COUNT=0
  while IFS= read -r dex; do
    [ -z "$dex" ] && continue
    DEX_COUNT=$((DEX_COUNT + 1))
    hits="$(unzip -p "$apk" "$dex" | grep -aFc "rikka/shizuku/ShizukuProvider" || true)"
    if [ "${hits:-0}" -gt 0 ]; then
      echo "FOUND rikka/shizuku/ShizukuProvider in $dex"
      FOUND=1
    fi
  done < <(unzip -l "$apk" | grep -o 'classes[0-9]*\.dex' | sort -u)
  if [ "$DEX_COUNT" -eq 0 ]; then
    fail "No DEX entries found in $apk"
  fi
  if [ "$FOUND" -eq 0 ]; then
    fail "ShizukuProvider class string not found in DEX of $apk"
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

  # 5. DualHook survives into the DEX (fully-qualified slash form).
  FOUND=0
  DEX_COUNT=0
  while IFS= read -r dex; do
    [ -z "$dex" ] && continue
    DEX_COUNT=$((DEX_COUNT + 1))
    hits="$(unzip -p "$apk" "$dex" | grep -aFc "com/galaxydual/hook/DualHook" || true)"
    if [ "${hits:-0}" -gt 0 ]; then
      echo "FOUND com/galaxydual/hook/DualHook in $dex"
      FOUND=1
    fi
  done < <(unzip -l "$apk" | grep -o 'classes[0-9]*\.dex' | sort -u)
  if [ "$DEX_COUNT" -eq 0 ]; then
    fail "No DEX entries found in $apk"
  fi
  if [ "$FOUND" -eq 0 ]; then
    fail "DualHook class string not found in DEX of $apk"
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
