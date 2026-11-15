#!/usr/bin/env bash
# Self-test for scripts/check-exported-components.py.
#
# Fixtures live in scripts/fixtures/exported-components/ and are named by
# expected outcome: pass-* must exit 0, fail-* must exit 1 AND print the
# ::error:: reason the fixture exists for (expected_reason(); a fail fixture
# whose ::error:: output does not contain the mapped substring is a MISMATCH,
# not a pass — no false-confidence fixtures). Format comes from the
# extension: *.xml -> --merged-xml, *.xmltree.txt -> --aapt2-xmltree.
# Each pass fixture is an exported-components inventory of the merged debug
# and release manifests (debug- vs release-suffixed authorities); authorities
# themselves are NOT validated by this gate (owned by check-provider-parity.py),
# so no per-variant authority mapping is needed here.
#
# Intentionally `set -u` (not -e): both pass and fail exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

FIXTURE_DIR="scripts/fixtures/exported-components"
CHECKER="scripts/check-exported-components.py"

if [ ! -d "$FIXTURE_DIR" ]; then
  echo "::error::fixture directory missing: $FIXTURE_DIR" >&2
  exit 1
fi

# Fixture-count pin: the suite's summary is only meaningful when the fixture
# corpus is exactly the reviewed set. A silently added or deleted fixture
# changes what "PASS summary" means, so fail closed on drift. Single source of
# truth for the expected count; bump only together with fixture review.
# NOTE: the count pin catches add/delete drift only — NOT substitution (one
# fixture swapped for another at the same count). Substitution is routed to
# review via CODEOWNERS (/scripts/fixtures/).
EXPECTED_FIXTURE_COUNT=20
shopt -s nullglob
pinned_fixtures=("$FIXTURE_DIR"/*.xml "$FIXTURE_DIR"/*.xmltree.txt)
if [ "${#pinned_fixtures[@]}" -ne "$EXPECTED_FIXTURE_COUNT" ]; then
  echo "::error::fixture count drift: expected $EXPECTED_FIXTURE_COUNT fixtures (*.xml + *.xmltree.txt) in $FIXTURE_DIR, found ${#pinned_fixtures[@]} — update EXPECTED_FIXTURE_COUNT only with fixture review" >&2
  exit 1
fi

# Required ::error:: substring per fail fixture (pass-* fixtures expect
# "PASS:"). No substring mapping => mismatch (forces reason pins for new
# fail fixtures).
expected_reason() {
  case "$1" in
    pass-*.xml|pass-*.xmltree.txt)
      printf 'PASS:' ;;
    fail-unallowlisted-service.xml|fail-unallowlisted-receiver.xml|fail-unallowlisted-provider.xml|fail-unallowlisted-activity.xml)
      printf 'not in the allowlist' ;;
    fail-application-nested-queries.xml)
      printf 'not in the allowlist' ;;
    fail-receiver-lost-permission.xml|fail-shizuku-lost-permission.xml)
      printf 'missing android:permission' ;;
    fail-provider-path-permission.xml)
      printf 'carries <path-permission>' ;;
    fail-guard-added-permission.xml|fail-mainactivity-added-permission.xml)
      printf 'must NOT carry android:permission' ;;
    fail-application-permission.xml)
      printf '<application> android:permission' ;;
    fail-missing-exported.xml|fail-intent-filter-no-exported.xml)
      printf 'missing android:exported' ;;
    fail-namespace-qualified.xml)
      printf 'namespace-qualified element' ;;
    fail-provider-grant-uri-permissions.xml)
      printf 'grantUriPermissions' ;;
    *)
      printf '' ;;
  esac
}

shopt -s nullglob
fixtures=("$FIXTURE_DIR"/*.xml "$FIXTURE_DIR"/*.xmltree.txt)
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
    *.xml)         mode="--merged-xml" ;;
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
  output="$(python3 "$CHECKER" "$mode" "$fixture" 2>&1)"
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
