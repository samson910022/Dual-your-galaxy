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
#          proceeds to the aapt2 stage and fails there with 'aapt2 not found'.
#          Both the non-zero exit AND that downstream marker are pinned, so
#          the arm cannot pass if the verifier silently exits 0 (or if the
#          version gate starts rejecting a parseable versionName).
#          stderr must NOT contain 'unable to parse versionName'.
#
# Arms (g)-(k): end-to-end DEX-gate integration. Real ZIP APKs come from the
#               shared offline builder (scripts/fixtures/dex_builder.py) and
#               aapt2's xmltree dump is stubbed with the checked-in known-good
#               release fixture. The WHOLE verifier runs, so steps 2 and 5
#               (the two check-dex-classdefs.py calls) are actually exercised:
#               a revert to grep -aFc, or deletion of a parser call, turns
#               these arms RED instead of leaving every self-test green.
#
# Intentionally `set -euo pipefail`: every arm asserts an explicit exit code,
# so all verifier invocations run under `|| rc=$?` capture; no fixture relies
# on -e being off.
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
mkdir -p "$case_b/app" "$case_b/empty-bin"
printf 'versionName = "1.2.3"\n' > "$case_b/app/build.gradle.kts"
# Hermetic downstream stage: a controlled PATH containing every external tool
# the verifier uses EXCEPT aapt2, plus a non-SDK ANDROID_HOME. Without this, a
# CI runner with a preinstalled Android SDK would find aapt2 and fail later
# with a different message, making the pinned 'aapt2 not found' marker flaky.
for tool in bash sed head tr ls sort dirname pwd mktemp rm grep cat python3 unzip basename; do
  tool_path="$(command -v "$tool" 2>/dev/null || true)"
  if [ -n "$tool_path" ]; then
    ln -sf "$tool_path" "$case_b/empty-bin/$tool"
  fi
done
b_rc=0
( cd "$case_b" && ANDROID_HOME="$case_b/no-sdk" PATH="$case_b/empty-bin" \
    bash "$VERIFY" dummy.apk ) >"$case_b/output.log" 2>&1 || b_rc=$?
if [ "$b_rc" -ne 0 ] \
  && ! grep -F 'unable to parse versionName' "$case_b/output.log" >/dev/null \
  && grep -F 'aapt2 not found' "$case_b/output.log" >/dev/null; then
  ok "fake repo with versionName=1.2.3 passes the version gate and fails downstream at aapt2 (exit $b_rc)"
else
  fail "version gate must PASS for parseable versionName and then fail downstream with 'aapt2 not found' (exit $b_rc)" "$case_b/output.log"
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

# --- end-to-end DEX-gate integration arms -----------------------------------
# Build the shared offline fixtures once and stub aapt2 so the verifier can run
# from a fake repo root with no SDK and no network. The xmltree fixture is the
# release provider-parity "pass" case, which satisfies all three packaged
# manifest checkers (ShizukuProvider, exported-components, provider parity).
case_int="$SUITE_TMP/case-integration"
mkdir -p "$case_int/app" "$case_int/bin"
printf 'versionName = "%s"\n' "0.1.0-beta.3" > "$case_int/app/build.gradle.kts"
python3 "$REPO_ROOT/scripts/fixtures/dex_builder.py" "$case_int/fixtures" \
  > "$case_int/build.log" 2>&1
XMLTREE="$REPO_ROOT/scripts/fixtures/provider-parity/pass-release.xmltree.txt"

# aapt2 stub: exact package name + the known-good xmltree dump; every other
# invocation fails closed so an unexpected verifier call cannot pass silently.
cat > "$case_int/bin/aapt2" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "dump" ] && [ "${2:-}" = "packagename" ]; then
  printf '%s\n' 'io.github.samson910022.galaxydualplus'
  exit 0
fi
if [ "${1:-}" = "dump" ] && [ "${2:-}" = "xmltree" ]; then
  shift 2
  if [ "${2:-}" != "--file" ] || [ "${3:-}" != "AndroidManifest.xml" ]; then
    printf 'stub aapt2: dump xmltree needs --file AndroidManifest.xml: %s\n' "$*" >&2
    exit 1
  fi
  cat "${STUB_XMLTREE_FILE:?}"
  exit 0
fi
printf 'stub aapt2: unexpected invocation: %s\n' "$*" >&2
exit 1
STUB
chmod +x "$case_int/bin/aapt2"

run_integration_arm() {
  # $1 = APK file name inside $case_int/fixtures; sets INT_RC + INTEGRATION_LOG.
  INTEGRATION_LOG="$case_int/$1.output.log"
  INT_RC=0
  ( cd "$case_int" && ANDROID_HOME="$case_int/no-sdk" \
      PATH="$case_int/bin:$PATH" STUB_XMLTREE_FILE="$XMLTREE" \
      bash "$VERIFY" "fixtures/$1" ) >"$INTEGRATION_LOG" 2>&1 || INT_RC=$?
}

NO_PASSED='ALL APK ARTIFACT CHECKS PASSED'

# (g) positive: both targets in canonical multi-dex APK -> full pass. This is
#     the control: it proves every earlier gate passes, so the negative arms
#     below can only be failing at the DEX gate.
run_integration_arm "integration-positive.apk"
if [ "$INT_RC" -eq 0 ] \
  && grep -F 'FOUND class_def Lrikka/shizuku/ShizukuProvider; in classes2.dex' "$INTEGRATION_LOG" >/dev/null \
  && grep -F 'FOUND class_def Lcom/galaxydual/hook/DualHook; in classes.dex' "$INTEGRATION_LOG" >/dev/null \
  && grep -F "$NO_PASSED" "$INTEGRATION_LOG" >/dev/null; then
  ok "integration positive APK reaches both DEX gates and passes end to end (exit $INT_RC)"
else
  fail "integration positive APK must reach both DEX gates and print '$NO_PASSED' (exit $INT_RC)" "$INTEGRATION_LOG"
fi

# (h) negative ShizukuProvider: fail specifically at step 2.
run_integration_arm "integration-missing-shizuku.apk"
if [ "$INT_RC" -ne 0 ] \
  && grep -F '::error::MISSING class_def Lrikka/shizuku/ShizukuProvider;' "$INTEGRATION_LOG" >/dev/null \
  && grep -F 'ShizukuProvider class_def not found in DEX of' "$INTEGRATION_LOG" >/dev/null \
  && ! grep -F "$NO_PASSED" "$INTEGRATION_LOG" >/dev/null; then
  ok "integration missing-ShizukuProvider APK fails at the DEX gate (exit $INT_RC)"
else
  fail "integration missing-ShizukuProvider APK must fail at the DEX gate with the pinned parser message (exit $INT_RC)" "$INTEGRATION_LOG"
fi

# (i) negative DualHook: fail specifically at step 5.
run_integration_arm "integration-missing-dualhook.apk"
if [ "$INT_RC" -ne 0 ] \
  && grep -F '::error::MISSING class_def Lcom/galaxydual/hook/DualHook;' "$INTEGRATION_LOG" >/dev/null \
  && grep -F 'DualHook class_def not found in DEX of' "$INTEGRATION_LOG" >/dev/null \
  && ! grep -F "$NO_PASSED" "$INTEGRATION_LOG" >/dev/null; then
  ok "integration missing-DualHook APK fails at the DEX gate (exit $INT_RC)"
else
  fail "integration missing-DualHook APK must fail at the DEX gate with the pinned parser message (exit $INT_RC)" "$INTEGRATION_LOG"
fi

# (j) zero class_defs: descriptor strings/type_ids exist but no definitions.
run_integration_arm "integration-zero-classdefs.apk"
if [ "$INT_RC" -ne 0 ] \
  && grep -F 'class_defs_size=0' "$INTEGRATION_LOG" >/dev/null \
  && ! grep -F "$NO_PASSED" "$INTEGRATION_LOG" >/dev/null; then
  ok "integration zero-class_defs APK fails at the DEX gate (exit $INT_RC)"
else
  fail "integration zero-class_defs APK must fail at the DEX gate with 'class_defs_size=0' (exit $INT_RC)" "$INTEGRATION_LOG"
fi

# (k) non-contiguous secondary DEX numbering (classes.dex + classes3.dex):
#     both targets are present and valid, so only the parser's multi-dex name
#     policy can reject it. Proves that policy is enforced through the wrapper.
run_integration_arm "integration-numbering-gap.apk"
if [ "$INT_RC" -ne 0 ] \
  && grep -F 'non-contiguous secondary DEX numbering' "$INTEGRATION_LOG" >/dev/null \
  && ! grep -F "$NO_PASSED" "$INTEGRATION_LOG" >/dev/null; then
  ok "integration numbering-gap APK fails the multi-dex DEX gate (exit $INT_RC)"
else
  fail "integration numbering-gap APK must fail with 'non-contiguous secondary DEX numbering' (exit $INT_RC)" "$INTEGRATION_LOG"
fi

echo "----"
printf 'summary: %d fixtures, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
