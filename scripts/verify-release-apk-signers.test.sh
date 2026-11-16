#!/usr/bin/env bash
# Self-test for scripts/verify-release-apk-signers.sh — SDK-free arms only:
# the fixtures pin the staging-set validation that runs BEFORE any
# apksigner/Android-SDK invocation (unexpected-file allowlist, regular-file
# / no-symlink apk checks, empty-release refusal). Cert-pinning arms need a
# real signed APK and live only in the release workflow's tag run.
#
# Fixtures live in scripts/fixtures/verify-release-apk-signers/ and are
# named by expected outcome: fail-* must exit 1 with the pinned ::error::
# substring (expected_reason(); a fail fixture whose output does not contain
# the mapped substring is a MISMATCH, not a pass — no false-confidence
# fixtures). Each fixture is copied to a fresh mktemp dir per case
# (side-effect-free; the corpus is never mutated), removed via trap on exit.
#
# Intentionally `set -u` (not -e): both exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

FIXTURE_DIR="scripts/fixtures/verify-release-apk-signers"
SCRIPT="scripts/verify-release-apk-signers.sh"
if [ ! -f "$SCRIPT" ]; then
  echo "::error::script missing: $SCRIPT" >&2
  exit 1
fi

# Fixture-count pin: the suite's summary is only meaningful when the fixture
# corpus is exactly the reviewed set. A silently added or deleted fixture
# changes what "PASS summary" means, so fail closed on drift. Single source
# of truth for the expected count; bump only together with fixture review.
EXPECTED_FIXTURE_COUNT=3
shopt -s nullglob
pinned_fixtures=("$FIXTURE_DIR"/fail-*/)
if [ "${#pinned_fixtures[@]}" -ne "$EXPECTED_FIXTURE_COUNT" ]; then
  echo "::error::fixture count drift: expected $EXPECTED_FIXTURE_COUNT fixture directories (fail-*) in $FIXTURE_DIR, found ${#pinned_fixtures[@]} — update EXPECTED_FIXTURE_COUNT only with fixture review" >&2
  exit 1
fi

# Note: count drift catches add/delete only — NOT substitution; substitution
# is routed to review via CODEOWNERS (/scripts/fixtures/).

# Required output substring per fixture. No substring mapping => mismatch.
expected_reason() {
  case "$1" in
    fail-unexpected-file)
      printf 'unexpected file(s) staged for release' ;;
    fail-symlink)
      printf 'not a regular file (or a symlink)' ;;
    fail-empty)
      printf 'No release APKs found' ;;
    *)
      printf '' ;;
  esac
}

pass_count=0
fail_count=0
mismatch=0
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

for fixture_path in "${pinned_fixtures[@]}"; do
  fixture="$(basename "$fixture_path")"
  reason="$(expected_reason "$fixture")"
  if [ -z "$reason" ]; then
    echo "MISMATCH fixture $fixture (no reason pin)"
    mismatch=$((mismatch + 1))
    continue
  fi
  stage="$work_dir/$fixture"
  mkdir -p "$stage"
  (cd "$fixture_path" && cp -a . "$stage"/)
  output="$(bash "$SCRIPT" "$stage" 2>&1)"
  rc=$?
  if [ "$rc" -ne 1 ]; then
    echo "MISMATCH fixture $fixture (expected exit 1, got $rc)"
    echo "$output"
    mismatch=$((mismatch + 1))
    continue
  fi
  if ! printf '%s' "$output" | grep -qF "$reason"; then
    echo "MISMATCH fixture $fixture (expected substring not found: $reason) — output was:"
    echo "$output"
    mismatch=$((mismatch + 1))
    continue
  fi
  echo "PASS fixture $fixture (expected exit 1, got 1, reason pinned)"
  pass_count=$((pass_count + 1))
done

total=$((pass_count + mismatch + fail_count))
if [ "$mismatch" -ne 0 ]; then
  echo "----"
  echo "summary: $total fixtures, $pass_count as expected, $mismatch mismatched" >&2
  exit 1
fi
echo "----"
echo "summary: $total fixtures, $pass_count as expected, 0 mismatched"
