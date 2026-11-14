#!/usr/bin/env bash
# Self-test for scripts/check-provider-parity.py.
#
# Fixtures live in scripts/fixtures/provider-parity/ and are named by expected
# outcome: pass-* must exit 0, fail-* must exit 1 AND print the ::error::
# reason the fixture exists for (expected_reason(); a fail fixture whose
# ::error:: output does not contain the mapped substring is a MISMATCH, not
# a pass — no false-confidence fixtures). Debug fixtures are checked against
# the debug authority (com.galaxydual.plus.debug.XposedService), release
# fixtures against the release authority (com.galaxydual.plus.XposedService);
# any fixture with "release" in its filename uses the release authority (so
# fail-debug-authority-in-release exercises the cross-variant rejection).
# Format comes from the extension: *.merged.xml -> --merged-xml,
# *.xmltree.txt -> --aapt2-xmltree.
#
# Intentionally `set -u` (not -e): both pass and fail exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

FIXTURE_DIR="scripts/fixtures/provider-parity"
CHECKER="scripts/check-provider-parity.py"
DEBUG_AUTHORITY="com.galaxydual.plus.debug.XposedService"
RELEASE_AUTHORITY="com.galaxydual.plus.XposedService"

if [ ! -d "$FIXTURE_DIR" ]; then
  echo "::error::fixture directory missing: $FIXTURE_DIR" >&2
  exit 1
fi

# Required ::error:: substring per fail fixture (pass-* fixtures expect
# "PASS:"). No substring mapping => mismatch (forces reason pins for new
# fail fixtures).
expected_reason() {
  case "$1" in
    pass-*.merged.xml|pass-*.xmltree.txt)
      printf 'PASS:' ;;
    fail-a-before-e.xmltree.txt)
      printf 'attribute line before any element' ;;
    fail-debug-authority-in-release.merged.xml|fail-debug-authority-in-release.xmltree.txt)
      printf 'authorities exactly equal' ;;
    fail-empty-authority.merged.xml)
      printf 'authorities exactly equal' ;;
    fail-empty.xmltree.txt)
      printf 'no elements parsed' ;;
    fail-exported-false.merged.xml|fail-exported-false.xmltree.txt)
      printf 'android:exported must be' ;;
    fail-missing-exported.merged.xml)
      printf 'missing android:exported' ;;
    fail-multi-authority.merged.xml)
      printf 'authorities exactly equal' ;;
    fail-name-in-activity.merged.xml|fail-name-in-activity.xmltree.txt)
      printf 'is not a <provider>' ;;
    fail-permission-present.merged.xml|fail-permission-present.xmltree.txt)
      printf 'must NOT carry android:permission' ;;
    fail-split-attribute.merged.xml|fail-split-attribute.xmltree.txt)
      printf 'authorities exactly equal' ;;
    fail-tools-namespace-shadow.merged.xml)
      printf 'expected exactly 1 <provider> whose name ends with' ;;
    fail-two-guard-providers.merged.xml|fail-two-guard-providers.xmltree.txt)
      printf 'expected exactly 1 <provider> whose name ends with' ;;
    fail-wrong-authority.merged.xml|fail-wrong-authority.xmltree.txt)
      printf 'authorities exactly equal' ;;
    fail-xposed-provider-node.merged.xml|fail-xposed-provider-node.xmltree.txt)
      printf 'contains XposedProvider' ;;
    *)
      printf '' ;;
  esac
}

shopt -s nullglob
fixtures=("$FIXTURE_DIR"/*.merged.xml "$FIXTURE_DIR"/*.xmltree.txt)
if [ "${#fixtures[@]}" -eq 0 ]; then
  echo "::error::no fixtures found in $FIXTURE_DIR" >&2
  exit 1
fi

total=0
matched=0
mismatched=0
for fixture in "${fixtures[@]}"; do
  base="$(basename -- "$fixture")"
  case "$base" in
    pass-*) expected=0 ;;
    fail-*) expected=1 ;;
    *) echo "SKIP $base (filename must start with pass- or fail-)"; continue ;;
  esac
  case "$base" in
    *release*) authority="$RELEASE_AUTHORITY" ;;
    *)         authority="$DEBUG_AUTHORITY" ;;
  esac
  case "$base" in
    *.merged.xml)  mode="--merged-xml" ;;
    *.xmltree.txt) mode="--aapt2-xmltree" ;;
    *) echo "SKIP $base (unknown extension)"; continue ;;
  esac
  reason="$(expected_reason "$base")"
  if [ -z "$reason" ]; then
    echo "MISMATCH $base (no ::error:: reason pinned in expected_reason() — add one)"
    mismatched=$((mismatched + 1))
    total=$((total + 1))
    continue
  fi

  total=$((total + 1))
  output="$(python3 "$CHECKER" "$mode" "$fixture" --authority "$authority" 2>&1)"
  rc=$?
  ok=true
  if [ "$rc" -ne "$expected" ]; then
    ok=false
  fi
  if ! printf '%s\n' "$output" | grep -qF -- "$reason"; then
    ok=false
  fi
  if [ "$ok" = true ]; then
    matched=$((matched + 1))
    printf 'PASS fixture %s (expected exit %d, got %d, reason pinned)\n' "$base" "$expected" "$rc"
  else
    mismatched=$((mismatched + 1))
    printf 'FAIL fixture %s (expected exit %d + reason %q, got exit %d)\n' "$base" "$expected" "$reason" "$rc"
    printf '%s\n' "$output" | sed 's/^/     /'
  fi
done

echo "----"
printf 'summary: %d fixtures, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
