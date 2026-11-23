#!/usr/bin/env bash
# Self-test for scripts/check-shizuku-provider.py.
#
# Fixtures live in scripts/fixtures/shizuku-provider/ and are named by expected
# outcome: pass-* must exit 0, fail-* must exit 1 AND print the ::error::
# reason the fixture exists for (expected_reason(); a fail fixture whose
# ::error:: output does not contain the mapped substring is a MISMATCH, not
# a pass — no false-confidence fixtures). Debug fixtures are checked against
# the debug authority (io.github.samson910022.galaxydualplus.debug.shizuku), release fixtures
# against the release authority (io.github.samson910022.galaxydualplus.shizuku); any fixture
# with "release" in its filename uses the release authority.
#
# Intentionally `set -u` (not -e): both pass and fail exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

FIXTURE_DIR="scripts/fixtures/shizuku-provider"
CHECKER="scripts/check-shizuku-provider.py"

SUITE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/check-shizuku-provider-test.XXXXXX")" || {
  echo "::error::mktemp -d failed" >&2
  exit 1
}
trap 'rm -rf "$SUITE_TMP"' EXIT
DEBUG_AUTHORITY="io.github.samson910022.galaxydualplus.debug.shizuku"
RELEASE_AUTHORITY="io.github.samson910022.galaxydualplus.shizuku"
OLD_RELEASE_AUTHORITY="com.galaxydual.plus.shizuku"

if [ ! -d "$FIXTURE_DIR" ]; then
  echo "::error::fixture directory missing: $FIXTURE_DIR" >&2
  exit 1
fi

# Fixture-count pin: the suite's summary is only meaningful when the fixture
# corpus is exactly the reviewed set. A silently added or deleted fixture
# changes what "PASS summary" means, so fail closed on drift. Single source of
# truth for the expected count; bump only together with fixture review.
EXPECTED_FIXTURE_COUNT=7
shopt -s nullglob
pinned_fixtures=("$FIXTURE_DIR"/*.xmltree.txt)
if [ "${#pinned_fixtures[@]}" -ne "$EXPECTED_FIXTURE_COUNT" ]; then
  echo "::error::fixture count drift: expected $EXPECTED_FIXTURE_COUNT fixtures (*.xmltree.txt) in $FIXTURE_DIR, found ${#pinned_fixtures[@]} — update EXPECTED_FIXTURE_COUNT only with fixture review" >&2
  exit 1
fi

# Required ::error:: substring per fail fixture (pass-* fixtures expect
# "FOUND ShizukuProvider in packaged manifest"). No substring mapping =>
# mismatch (forces reason pins for new fail fixtures).
expected_reason() {
  case "$1" in
    pass-*.xmltree.txt)
      printf 'FOUND ShizukuProvider in packaged manifest' ;;
    fail-missing-provider.xmltree.txt)
      printf 'with android:name exactly' ;;
    fail-missing-permission.xmltree.txt)
      printf 'is missing android:permission' ;;
    fail-wrong-authority.xmltree.txt)
      printf "android:authorities is '" ;;
    fail-wrong-export.xmltree.txt)
      printf 'android:exported must be' ;;
    fail-wrong-permission.xmltree.txt)
      printf "android:permission is '" ;;
    *)
      printf '' ;;
  esac
}

shopt -s nullglob
fixtures=("$FIXTURE_DIR"/*.xmltree.txt)
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
  reason="$(expected_reason "$base")"
  if [ -z "$reason" ]; then
    echo "MISMATCH $base (no ::error:: reason pinned in expected_reason() — add one)"
    mismatched=$((mismatched + 1))
    total=$((total + 1))
    continue
  fi

  total=$((total + 1))
  output="$(python3 "$CHECKER" --aapt2-xmltree "$fixture" --authority "$authority" 2>&1)"
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

# --- negative case: the OLD (pre-migration) authority must be rejected ---
# Generated inline from the reviewed pass fixture (only the authority is swapped),
# so the EXPECTED_FIXTURE_COUNT pin above still describes exactly the reviewed
# fixture set. This is the authority-only regression: the old applicationId's
# shizuku authority must not pass the new pinned authority.
old_case_dir="$SUITE_TMP/old-authority"
mkdir -p "$old_case_dir"
sed "s|$RELEASE_AUTHORITY|$OLD_RELEASE_AUTHORITY|g" \
  "$FIXTURE_DIR/pass-release.xmltree.txt" > "$old_case_dir/old-release.xmltree.txt"
total=$((total + 1))
old_output="$(python3 "$CHECKER" --aapt2-xmltree "$old_case_dir/old-release.xmltree.txt" --authority "$RELEASE_AUTHORITY" 2>&1)" || old_rc=$?
old_rc=${old_rc:-0}
if [ "$old_rc" -eq 1 ] && printf '%s\n' "$old_output" | grep -qF "android:authorities is '$OLD_RELEASE_AUTHORITY'"; then
  matched=$((matched + 1))
  printf 'PASS negative case old-authority.xmltree.txt (old authority rejected, exit %d)\n' "$old_rc"
else
  mismatched=$((mismatched + 1))
  printf 'FAIL negative case old-authority.xmltree.txt (expected exit 1 + old-authority reason, got exit %d)\n' "$old_rc"
  printf '%s\n' "$old_output" | sed 's/^/     /'
fi

echo "----"
printf 'summary: %d fixtures, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
