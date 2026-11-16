#!/usr/bin/env bash
# Self-test for scripts/verify-release-sha256sums.sh.
#
# Fixtures live in scripts/fixtures/verify-release-sha256sums/ and are named by
# expected outcome: pass-* must exit 0 and print the PASS line; fail-* must
# exit 1 and print the pinned ::error::/sha256sum substring the fixture exists
# for (expected_reason(); a fail fixture whose output does not contain the
# mapped substring is a MISMATCH, not a pass — no false-confidence fixtures).
# Fixture directories are copied to a fresh mktemp dir per case (self-contained
# and side-effect-free; the corpus is never mutated), and the temp dirs are
# removed via trap on exit.
#
# Intentionally `set -u` (not -e): both pass and fail exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

FIXTURE_DIR="scripts/fixtures/verify-release-sha256sums"
SCRIPT="scripts/verify-release-sha256sums.sh"

if [ ! -d "$FIXTURE_DIR" ]; then
  echo "::error::fixture directory missing: $FIXTURE_DIR" >&2
  exit 1
fi
if [ ! -f "$SCRIPT" ]; then
  echo "::error::script missing: $SCRIPT" >&2
  exit 1
fi

# Fixture-count pin: the suite's summary is only meaningful when the fixture
# corpus is exactly the reviewed set. A silently added or deleted fixture
# changes what "PASS summary" means, so fail closed on drift. Single source of
# truth for the expected count; bump only together with fixture review.
EXPECTED_FIXTURE_COUNT=18
shopt -s nullglob
pinned_fixtures=("$FIXTURE_DIR"/pass-*/ "$FIXTURE_DIR"/fail-*/)
if [ "${#pinned_fixtures[@]}" -ne "$EXPECTED_FIXTURE_COUNT" ]; then
  echo "::error::fixture count drift: expected $EXPECTED_FIXTURE_COUNT fixture directories (pass-*/fail-*) in $FIXTURE_DIR, found ${#pinned_fixtures[@]} — update EXPECTED_FIXTURE_COUNT only with fixture review" >&2
  exit 1
fi

# Required output substring per fixture (pass-* expect the PASS line).
# No substring mapping => mismatch (forces reason pins for new fixtures).
expected_reason() {
  case "$1" in
    pass-listed-ok)
      printf 'PASS: release-publish holds exactly' ;;
    pass-spaced-name)
      printf 'PASS: release-publish holds exactly' ;;
    fail-crlf)
      printf 'CRLF' ;;
    fail-backslash)
      printf 'backslash not allowed in entry name' ;;
    fail-nul)
      printf 'NUL' ;;
    fail-symlink-listed)
      printf 'symlink' ;;
    fail-extra-file)
      printf 'unlisted file(s) staged for release' ;;
    fail-missing-file)
      printf 'gone.apk' ;;
    fail-forged-sums)
      printf 'FAILED' ;;
    fail-empty)
      printf 'contains no entries' ;;
    fail-path-traversal)
      printf 'path traversal' ;;
    fail-absolute-path)
      printf 'absolute path' ;;
    fail-subdirectory)
      printf 'separator' ;;
    fail-symlink)
      printf 'symlink' ;;
    fail-no-apk)
      printf 'no *.apk' ;;
    fail-ghost-listed)
      printf 'ghost.apk' ;;
    fail-duplicate-record)
      printf 'duplicate' ;;
    fail-directory)
      printf 'director' ;;
    *)
      printf '' ;;
  esac
}

CLEANUP_DIRS=()
trap 'for d in "${CLEANUP_DIRS[@]}"; do rm -rf -- "$d"; done' EXIT
mk_tmp_copy() {
  local src="$1"
  local dst
  dst="$(mktemp -d "${TMPDIR:-/tmp}/verify-release-sha256sums.XXXXXX")"
  CLEANUP_DIRS+=("$dst")
  cp -a "$src/." "$dst/"
  printf '%s' "$dst"
}

total=0
matched=0
mismatched=0
for fixture in "${pinned_fixtures[@]}"; do
  base="$(basename -- "$fixture")"
  case "$base" in
    pass-*) expected=0 ;;
    fail-*) expected=1 ;;
    *) echo "SKIP $base (directory must start with pass- or fail-)"; continue ;;
  esac
  reason="$(expected_reason "$base")"
  if [ -z "$reason" ]; then
    echo "MISMATCH $base (no output substring pinned in expected_reason() — add one)"
    mismatched=$((mismatched + 1))
    total=$((total + 1))
    continue
  fi

  work="$(mk_tmp_copy "$fixture")"
  total=$((total + 1))
  output="$(bash "$SCRIPT" "$work" 2>&1)"
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
