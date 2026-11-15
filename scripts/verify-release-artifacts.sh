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
  # fails closed: com.galaxydual.plus.debug -> debug authorities;
  # com.galaxydual.plus -> release authorities (everything else: hard fail).
  if ! APK_PACKAGE="$("$AAPT2" dump packagename "$apk" 2>/dev/null)"; then
    fail "aapt2 dump packagename failed for $apk"
  fi
  APK_PACKAGE="$(printf '%s' "$APK_PACKAGE" | tr -d '[:space:]')"
  case "$APK_PACKAGE" in
    com.galaxydual.plus.debug)
      EXPECTED_AUTHORITY="com.galaxydual.plus.debug.XposedService"
      EXPECTED_AUTHORITY_SHIZUKU="com.galaxydual.plus.debug.shizuku" ;;
    com.galaxydual.plus)
      EXPECTED_AUTHORITY="com.galaxydual.plus.XposedService"
      EXPECTED_AUTHORITY_SHIZUKU="com.galaxydual.plus.shizuku" ;;
    *) fail "unsupported APK package name '$APK_PACKAGE' in $apk (expected exactly 'com.galaxydual.plus.debug' or 'com.galaxydual.plus')" ;;
  esac
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
  #        (debug: com.galaxydual.plus.debug.shizuku; release:
  #         com.galaxydual.plus.shizuku — derived from the actual
  #         applicationId, same way EXPECTED_AUTHORITY is derived; the debug
  #         authority shows up verbatim in the merged debug manifest)
  #      android:exported="true"
  #      android:multiprocess="false"
  #      android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
  #    Parsed from the aapt2 xmltree dump with the same element/attribute idiom
  #    as scripts/check-provider-parity.py (an `E:` line sets the current
  #    element; `A: .../android:<name>(0x<hex>)=<value>` lines set fields on
  #    it). An unanchored substring match would also admit the class name
  #    inside an unrelated attribute value, and attribute lines alone say
  #    nothing about WHICH element carries them. Fails closed (::error:: +
  #    exit 1) on: a missing provider, a wrong authority/exported/multiprocess/
  #    permission, or any parse oddity. Division of labor: this step pins the
  #    ShizukuProvider element-scoped (name+authority+exported+multiprocess+
  #    permission) on the packaged manifest; step 1b pins the GuardXposedProvider
  #    (name+authority parity, via scripts/check-provider-parity.py); step 2
  #    pins the ShizukuProvider class in the DEX. ShizukuProvider is not a
  #    trust boundary for GalaxyExecService (see the sharedUserId note in
  #    AndroidManifest.xml).
  if python3 - "$MANIFEST_DUMP_FILE" "$EXPECTED_AUTHORITY_SHIZUKU" <<'PY'
import re
import sys

path, expected_authority = sys.argv[1], sys.argv[2]
EXPECTED_PROVIDER = "rikka.shizuku.ShizukuProvider"
EXPECTED_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL"
E_RE = re.compile(r"^(\s*)(?:\d+\s*)?E: ([^\s()]+)")
ELEMENT_TOKEN_RE = re.compile(r"[A-Za-z0-9_.\-]+")
A_RE = re.compile(
    r"^\s*A: http://schemas\.android\.com/apk/res/android:"
    r"([A-Za-z_][A-Za-z0-9_]*)\(0x[0-9a-fA-F]+\)=(.+)$"
)


def die(reason):
    print(f"::error::{reason}", file=sys.stderr)
    sys.exit(1)


def parse_value(rest):
    # Quoted form: "...value..." (Raw: "...value...") — take up to the closing
    # quote. Unquoted form: the token before any whitespace (e.g. true/false),
    # which also discards an optional ` (Raw: ...)` suffix. Same as the parity
    # checker's parse_value.
    rest = rest.strip()
    if rest.startswith('"'):
        end = rest.find('"', 1)
        if end == -1:
            return rest[1:]
        return rest[1:end]
    return rest.split(None, 1)[0]


try:
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        text = handle.read()
except OSError as exc:
    die(f"aapt2-xmltree unreadable file {path}: {exc}")

# aapt2 xmltree is perfectly nested: attributes always belong to the nearest
# preceding element (same stack discipline as the parity checker).
elements = []
stack = []
for lineno, raw in enumerate(text.splitlines(), start=1):
    match_e = E_RE.match(raw)
    if match_e:
        indent = len(match_e.group(1))
        token = match_e.group(2)
        if ":" in token:
            die(f"{path}:{lineno}: namespace-qualified element token '{token}' (refusing to parse)")
        if not ELEMENT_TOKEN_RE.fullmatch(token):
            die(f"{path}:{lineno}: unrecognized element token '{token}' (refusing to parse)")
        element = {"tag": token, "attrs": {}, "line": lineno}
        while stack and stack[-1][0] >= indent:
            stack.pop()
        elements.append(element)
        stack.append((indent, element))
        continue
    if re.match(r"^\s*(?:\d+\s*)?E:", raw):
        die(f"{path}:{lineno}: unparseable element line: {raw.strip()}")
    match_a = A_RE.match(raw)
    if match_a is None and raw.lstrip().startswith("A:"):
        if "schemas.android.com/apk/res/android" in raw:
            die(f"{path}:{lineno}: unparseable android attribute line: {raw.strip()}")
    if match_a:
        if not stack:
            die(f"{path}:{lineno}: attribute line before any element: {raw.strip()}")
        name, value = match_a.group(1), parse_value(match_a.group(2))
        stack[-1][1]["attrs"][name] = value

if not elements:
    die(f"{path}: no elements parsed (empty or unrecognized xmltree dump)")

providers = [e for e in elements if e["tag"] == "provider"]
pinned = [p for p in providers if p["attrs"].get("name") == EXPECTED_PROVIDER]
if len(pinned) != 1:
    die(f"expected exactly 1 <provider> with android:name exactly "
        f"'{EXPECTED_PROVIDER}', found {len(pinned)}")

attrs = pinned[0]["attrs"]
authority = attrs.get("authorities")
if authority is None:
    die(f"ShizukuProvider <provider> is missing android:authorities (expected '{expected_authority}')")
if authority != expected_authority:
    die(f"ShizukuProvider <provider> android:authorities is '{authority}', "
        f"expected '{expected_authority}'")
exported = attrs.get("exported")
if exported != "true":
    die(f"ShizukuProvider <provider> android:exported must be \"true\", found {exported!r}")
multiprocess = attrs.get("multiprocess")
if multiprocess != "false":
    die(f"ShizukuProvider <provider> android:multiprocess must be \"false\", found {multiprocess!r}")
permission = attrs.get("permission")
if permission is None:
    die(f"ShizukuProvider <provider> is missing android:permission (expected '{EXPECTED_PERMISSION}')")
if permission != EXPECTED_PERMISSION:
    die(f"ShizukuProvider <provider> android:permission is '{permission}', "
        f"expected '{EXPECTED_PERMISSION}'")
print(f"FOUND ShizukuProvider in packaged manifest (android:authorities {authority}; "
      f"exported=true, multiprocess=false, permission={EXPECTED_PERMISSION})")
PY
  then
    :
  else
    fail "ShizukuProvider element-scoped manifest gate failed for $apk"
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
  #     com.galaxydual.plus, no .debug). The mapping is exact and anything
  #     else fails closed: com.galaxydual.plus.debug -> debug authority;
  #     com.galaxydual.plus -> release authority (and only a release-
  #     expected APK carrying the .debug authority fails the leak guard).
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
