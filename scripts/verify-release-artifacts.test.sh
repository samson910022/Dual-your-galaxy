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

# --- identity arms: OLD applicationId fails closed; packaged module.prop id ---
# Stub aapt2 (package-name probe) so the identity case map is exercised without
# a real SDK/APK. ANDROID_HOME points at a non-existent dir so the verifier
# falls back to `command -v aapt2`, i.e. the stub on PATH.
write_aapt2_stub() {
  cat > "$1" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "dump" ] && [ "${2:-}" = "packagename" ]; then
  cat "${STUB_PACKAGE_FILE:?}"
  exit 0
fi
printf 'stub aapt2: unexpected invocation: %s\n' "$*" >&2
exit 1
STUB
  chmod +x "$1"
}

setup_identity_arm() {
  # $1 = case dir, $2 = package id the stub aapt2 reports
  mkdir -p "$1/app" "$1/bin"
  printf 'versionName = "0.1.0-beta.2"\n' > "$1/app/build.gradle.kts"
  printf '%s\n' "$2" > "$1/package.txt"
  write_aapt2_stub "$1/bin/aapt2"
  : > "$1/dummy.apk"
}

run_identity_arm() {
  IDENTITY_RC=0
  ( cd "$1" && ANDROID_HOME="$1/no-sdk" PATH="$1/bin:$PATH" \
      STUB_PACKAGE_FILE="$1/package.txt" bash "$VERIFY" dummy.apk ) \
      >"$1/output.log" 2>&1 || IDENTITY_RC=$?
}

# (c1) old release id -> "old applicationId identity"
case_c1="$SUITE_TMP/case-old-release-id"
setup_identity_arm "$case_c1" "com.galaxydual.plus"
run_identity_arm "$case_c1"
if [ "$IDENTITY_RC" -ne 0 ] && grep -F 'old applicationId identity' "$case_c1/output.log" >/dev/null; then
  ok "old release id fails closed with 'old applicationId identity' (exit $IDENTITY_RC)"
else
  fail "old release id must exit non-zero with 'old applicationId identity' (got exit $IDENTITY_RC)" "$case_c1/output.log"
fi

# (c2) old debug id -> same guard
case_c2="$SUITE_TMP/case-old-debug-id"
setup_identity_arm "$case_c2" "com.galaxydual.plus.debug"
run_identity_arm "$case_c2"
if [ "$IDENTITY_RC" -ne 0 ] && grep -F 'old applicationId identity' "$case_c2/output.log" >/dev/null; then
  ok "old debug id fails closed with 'old applicationId identity' (exit $IDENTITY_RC)"
else
  fail "old debug id must exit non-zero with 'old applicationId identity' (got exit $IDENTITY_RC)" "$case_c2/output.log"
fi

# (d) stale module.prop id -> fail with 'module.prop id'
case_d="$SUITE_TMP/case-module-id-mismatch"
setup_identity_arm "$case_d" "io.github.samson910022.galaxydualplus"
cat > "$case_d/bin/unzip" <<'STUB'
#!/usr/bin/env bash
printf 'id=com.galaxydual.plus\nversion=0.1.0-beta.2\n'
STUB
chmod +x "$case_d/bin/unzip"
run_identity_arm "$case_d"
if [ "$IDENTITY_RC" -ne 0 ] && grep -F '::error::module.prop id' "$case_d/output.log" >/dev/null; then
  ok "stale packaged module.prop id fails closed (exit $IDENTITY_RC)"
else
  fail "stale module.prop id must exit non-zero with 'module.prop id' (got exit $IDENTITY_RC)" "$case_d/output.log"
fi

# (e) matching module.prop id -> identity gate PASSES (failure happens later)
case_e="$SUITE_TMP/case-module-id-match"
setup_identity_arm "$case_e" "io.github.samson910022.galaxydualplus"
cat > "$case_e/bin/unzip" <<'STUB'
#!/usr/bin/env bash
printf 'id=io.github.samson910022.galaxydualplus\nversion=0.1.0-beta.2\n'
STUB
chmod +x "$case_e/bin/unzip"
run_identity_arm "$case_e"
if [ "$IDENTITY_RC" -ne 0 ] && ! grep -F '::error::module.prop id' "$case_e/output.log" >/dev/null; then
  ok "matching module.prop id passes the identity gate (exit $IDENTITY_RC; fails later as expected)"
else
  fail "identity gate must PASS for matching module.prop id (::error::module.prop id present, exit $IDENTITY_RC)" "$case_e/output.log"
fi

# (f) debug APK package + release-id module.prop -> identity gate PASSES.
#     The debug applicationId has a ".debug" suffix while module.prop always
#     carries the release id; the gate must strip the suffix before comparing.
case_f="$SUITE_TMP/case-module-id-match-debug-suffix"
setup_identity_arm "$case_f" "io.github.samson910022.galaxydualplus.debug"
cat > "$case_f/bin/unzip" <<'STUB'
#!/usr/bin/env bash
printf 'id=io.github.samson910022.galaxydualplus\nversion=0.1.0-beta.2\n'
STUB
chmod +x "$case_f/bin/unzip"
run_identity_arm "$case_f"
if [ "$IDENTITY_RC" -ne 0 ] && ! grep -F '::error::module.prop id' "$case_f/output.log" >/dev/null; then
  ok "release module.prop id passes identity gate for .debug APK (exit $IDENTITY_RC; fails later as expected)"
else
  fail "identity gate must PASS for release module.prop id with .debug APK (::error::module.prop id present, exit $IDENTITY_RC)" "$case_f/output.log"
fi

echo "----"
printf 'summary: %d fixtures, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
