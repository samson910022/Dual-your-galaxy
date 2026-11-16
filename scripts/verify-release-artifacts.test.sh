#!/usr/bin/env bash
# Self-test for scripts/verify-release-artifacts.sh — pins the fail-fast
# versionName gate semantics. Regression guard for the K4/F-1 defect where
# fail() was CALLED before it was DEFINED (under `set -e` that died with
# "command not found", exit 127, no ::error:: annotation).
#
# Arm (a): empty CWD -> the versionName gate fires; the script must exit
#          non-zero with '::error::unable to parse versionName' on stderr
#          (proves fail() is defined before its first call).
# Arm (b): fake repo layout carrying app/build.gradle.kts with
#          versionName="1.2.3" -> the version gate PASSES; the script
#          proceeds to the aapt2 stage and fails there (aapt2 unusable for a
#          non-APK argument, or the APK itself missing) — non-zero exit is
#          expected, but stderr must NOT contain 'unable to parse
#          versionName'.
#
# Intentionally `set -euo pipefail`: both arms assert a NON-ZERO exit, so all
# invocations run under `|| rc=$?` capture; no fixture relies on -e being off.
set -euo pipefail

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(pwd)"
VERIFY="$REPO_ROOT/scripts/verify-release-artifacts.sh"

if [ ! -f "$VERIFY" ]; then
  echo "::error::verify script missing: $VERIFY" >&2
  exit 1
fi

SUITE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/verify-release-artifacts-test.XXXXXX")" || {
  echo "::error::mktemp -d failed" >&2
  exit 1
}
trap 'rm -rf "$SUITE_TMP"' EXIT

total=0
matched=0
mismatched=0

ok() {
  matched=$((matched + 1))
  total=$((total + 1))
  printf 'PASS fixture %s\n' "$1"
}

fail() {
  mismatched=$((mismatched + 1))
  total=$((total + 1))
  printf '::error::FAIL fixture %s\n' "$1"
  if [ -n "${2:-}" ]; then
    sed 's/^/     /' "$2"
  fi
}

# --- (a) empty CWD: versionName gate fails with the ::error:: annotation ---
case_a="$SUITE_TMP/case-a"
mkdir -p "$case_a"
a_rc=0
( cd "$case_a" && bash "$VERIFY" dummy.apk ) >"$case_a/output.log" 2>&1 || a_rc=$?
if [ "$a_rc" -ne 0 ] && grep -F '::error::unable to parse versionName' "$case_a/output.log" >/dev/null; then
  ok "empty CWD fails closed with '::error::unable to parse versionName' (exit $a_rc)"
else
  fail "empty CWD must exit non-zero with '::error::unable to parse versionName' (got exit $a_rc)" "$case_a/output.log"
fi

# --- (b) fake repo layout: version gate passes, failure happens downstream ---
case_b="$SUITE_TMP/case-b"
mkdir -p "$case_b/app"
printf 'versionName = "1.2.3"\n' > "$case_b/app/build.gradle.kts"
b_rc=0
( cd "$case_b" && bash "$VERIFY" dummy.apk ) >"$case_b/output.log" 2>&1 || b_rc=$?
if ! grep -F 'unable to parse versionName' "$case_b/output.log" >/dev/null; then
  ok "fake repo with versionName=1.2.3 passes the version gate (exit $b_rc; fails later as expected)"
else
  fail "version gate must PASS for parseable versionName (stderr mentions 'unable to parse versionName', exit $b_rc)" "$case_b/output.log"
fi

echo "----"
printf 'summary: %d fixtures, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
