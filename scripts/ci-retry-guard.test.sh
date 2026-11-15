#!/usr/bin/env bash
# Self-test for scripts/ci-retry-guard.sh — pins the fail-fast semantics of
# the dependency-verification signature check used by every until-gradlew
# retry loop in CI. A verification failure must exit 1 IMMEDIATELY with no
# retry (retrying only hides it); every other failure may retry up to the
# attempt cap and must exit 1 at the cap.
#
# Synthetic "gradle" commands are emitted as a bash script written to a temp
# dir (FAKE_MODE selects the behavior, every invocation is counted in
# INVOCATIONS_FILE). Invocation shape matches the android.yml call sites:
#   bash scripts/ci-retry-guard.sh <max> <sleep> <label> -- <command...>
#
# Intentionally `set -u` (not -e): both pass and fail exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

GUARD="scripts/ci-retry-guard.sh"

# Known-miss, pinned deliberately (do NOT "fix" by asserting exit 0):
# the guard applies VERIFICATION_RE with `grep -E`, which is line-based. A
# verification signature SPLIT ACROSS LINES cannot match, so the guard fails
# OPEN to its retry path for that shape: it retries up to the attempt cap and
# exits 1 at the cap (CI still goes red). If the guard ever grows a
# multi-line signature matcher, update this fixture and this constant.
KNOWN_MISS_SPLIT_SIGNATURE=1

if [ ! -f "$GUARD" ]; then
  echo "::error::guard script missing: $GUARD" >&2
  exit 1
fi

SUITE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/ci-retry-guard-test.XXXXXX")" || {
  echo "::error::mktemp -d failed" >&2
  exit 1
}
trap 'rm -rf "$SUITE_TMP"' EXIT

FAKE_GRADLE="$SUITE_TMP/fake-gradle"
cat > "$FAKE_GRADLE" <<'EOF'
#!/usr/bin/env bash
# Synthetic stand-in for ./gradlew used by ci-retry-guard.test.sh.
# FAKE_MODE selects the emitted output/exit code; every invocation appends a
# line to INVOCATIONS_FILE so the suite can assert the exact attempt count.
printf 'call\n' >> "${INVOCATIONS_FILE:?}"
attempt="$(wc -l < "$INVOCATIONS_FILE" | tr -d ' ')"
mode="${FAKE_MODE:?}"
case "$mode" in
  verify-junit-bom)
    echo 'Dependency verification failed for org.junit:junit-bom:5.9.2'
    exit 1 ;;
  verify-failed-verification)
    echo 'failed verification: org/example/lib'
    exit 1 ;;
  verify-metadata-checksum)
    echo 'verification-metadata: checksum mismatch'
    exit 1 ;;
  verify-verification-metadata-prefix)
    # 'Verification-metadata ...' prefix wording (pre-existing hyphen branch
    # pinned as a wording variant; still matched by the old regex).
    echo 'Verification-metadata could not be parsed; see gradle/verification-metadata.xml'
    exit 1 ;;
  verify-verification-metadata-space)
    # Spaced 'Verification metadata' wording (CI-5) — deliberately NO hyphenated
    # path or hyphenated 'verification-metadata' anywhere on the line, so this
    # pin fires on the `verification[ -]metadata` class alone (the pre-existing
    # hyphen branch does not match this line).
    echo 'Verification metadata failed to verify'
    exit 1 ;;
  verify-signature-verification)
    # Bare PGP wording (CI-5) — matches no pre-existing branch (no 'artifact',
    # no trailing 'failed verification:'); pins the 'signature verification'
    # alternative alone.
    echo 'Signature verification failed'
    exit 1 ;;
  verify-checksum-verification-failed)
    # Bare checksum wording (CI-5) — the legacy checksum branch requires
    # trailing verification/metadata/artifact context, so this pins the new
    # standalone 'checksum verification failed' alternative alone.
    echo 'Checksum verification failed'
    exit 1 ;;
  http-504)
    echo 'FAILURE: Could not GET https://repo.example.com/org/junit/junit-bom/5.9.2/junit-bom-5.9.2.pom. Received status code 504 from server: Gateway Timeout'
    exit 1 ;;
  connect-etimedout)
    echo 'org.gradle.internal.resource.transport.http.HttpRequestException: Could not GET https://repo.example.com/maven-metadata.xml connect timed out (ETIMEDOUT)'
    exit 1 ;;
  flaky-then-ok)
    if [ "$attempt" -ge 2 ]; then
      echo 'BUILD SUCCESSFUL in 12s'
      exit 0
    fi
    echo 'FAILURE: Received status code 504 from server: Gateway Timeout'
    exit 1 ;;
  silent-flaky)
    # Prints nothing but exits non-zero on attempt 1, then succeeds.
    if [ "$attempt" -ge 2 ]; then
      exit 0
    fi
    exit 1 ;;
  ok)
    echo 'BUILD SUCCESSFUL in 1s'
    exit 0 ;;
  split-signature)
    # KNOWN-MISS (see KNOWN_MISS_SPLIT_SIGNATURE above): the signature is
    # printed across two lines, which grep -E cannot match.
    printf 'Dependency verification\nfailed for org.junit:junit-bom:5.9.2\n'
    exit 1 ;;
  *)
    echo "fake-gradle: unknown FAKE_MODE '$mode'" >&2
    exit 99 ;;
esac
EOF
chmod +x "$FAKE_GRADLE"

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

# run_guard <name> <expected-rc> <expected-invocations> <fake-mode> <guard args...>
# Invokes the guard exactly as android.yml does, with sleep=0 for speed, and
# asserts BOTH the exit code and the number of times the command ran.
run_guard() {
  local name="$1" expected_rc="$2" expected_invocations="$3" fake_mode="$4"
  shift 4
  local case_dir case_rc case_invocations expected_desc
  case_dir="$(mktemp -d "${TMPDIR:-/tmp}/ci-retry-guard-test.XXXXXX")"
  : > "$case_dir/invocations"
  INVOCATIONS_FILE="$case_dir/invocations" FAKE_MODE="$fake_mode" \
    bash "$GUARD" "$@" >"$case_dir/output.log" 2>&1
  case_rc=$?
  case_invocations="$(wc -l < "$case_dir/invocations" | tr -d ' ')"
  if [ "$case_rc" = "$expected_rc" ] && [ "$case_invocations" = "$expected_invocations" ]; then
    ok "$name (exit $case_rc, invocations $case_invocations)"
  else
    expected_desc="expected exit $expected_rc + invocations $expected_invocations, got exit $case_rc + invocations $case_invocations"
    fail "$name ($expected_desc)" "$case_dir/output.log"
  fi
  rm -rf "$case_dir"
}

# --- (a) dependency-verification signatures: fail fast, no retry -------------
run_guard 'verification signature: Dependency verification failed (junit-bom)' \
  1 1 verify-junit-bom \
  3 0 "test verify-junit-bom" -- "$FAKE_GRADLE"
run_guard 'verification signature: failed verification:' \
  1 1 verify-failed-verification \
  3 0 "test failed-verification" -- "$FAKE_GRADLE"
run_guard 'verification signature: verification-metadata checksum mismatch' \
  1 1 verify-metadata-checksum \
  3 0 "test verification-metadata" -- "$FAKE_GRADLE"
run_guard 'verification signature: Verification-metadata prefix (could not be parsed)' \
  1 1 verify-verification-metadata-prefix \
  3 0 "test verification-metadata-prefix" -- "$FAKE_GRADLE"
run_guard 'verification signature: Verification metadata (spaced wording)' \
  1 1 verify-verification-metadata-space \
  3 0 "test verification-metadata-space" -- "$FAKE_GRADLE"
run_guard 'verification signature: Signature verification failed' \
  1 1 verify-signature-verification \
  3 0 "test signature-verification" -- "$FAKE_GRADLE"
run_guard 'verification signature: Checksum verification failed (bare)' \
  1 1 verify-checksum-verification-failed \
  3 0 "test checksum-verification-failed" -- "$FAKE_GRADLE"

# --- (b)/(f) transient transport failures: retried up to the cap, rc 1 --------
run_guard 'transient HTTP 504 retried up to cap (invocations == cap)' \
  1 3 http-504 \
  3 0 "test http-504" -- "$FAKE_GRADLE"
run_guard 'transient ETIMEDOUT retried up to cap (invocations == cap)' \
  1 2 connect-etimedout \
  2 0 "test etimedout" -- "$FAKE_GRADLE"

# --- (c) silent failure then success on the 2nd attempt ------------------------
run_guard 'silent failure then success on 2nd attempt (exits 0, invocations 2)' \
  0 2 silent-flaky \
  5 0 "test silent-flaky" -- "$FAKE_GRADLE"

# transient failure then success on the 2nd attempt
run_guard 'transient 504 then success on 2nd attempt (exits 0, invocations 2)' \
  0 2 flaky-then-ok \
  5 0 "test flaky-then-ok" -- "$FAKE_GRADLE"

# --- (d) passing command: exits 0, exactly one invocation ----------------------
run_guard 'passing command is not retried (exits 0, invocations 1)' \
  0 1 ok \
  3 0 "test ok" -- "$FAKE_GRADLE"

# --- (e) KNOWN-MISS: signature split across lines ------------------------------
# KNOWN_MISS_SPLIT_SIGNATURE=$KNOWN_MISS_SPLIT_SIGNATURE: line-based grep -E
# cannot see a cross-line signature. Pinned semantics: fail OPEN to retry, one
# invocation per attempt up to the cap, exit 1 at the cap (never exit 0).
run_guard 'KNOWN-MISS split verification signature fails open to retry, exits 1 at cap' \
  1 3 split-signature \
  3 0 "test split-signature" -- "$FAKE_GRADLE"

# --- argument validation: exit 2, command never runs (invocations == 0) --------
run_guard 'argument validation: max-attempts abc' \
  2 0 ok \
  abc 0 "test max-attempts-abc" -- "$FAKE_GRADLE"
run_guard 'argument validation: max-attempts 0' \
  2 0 ok \
  0 0 "test max-attempts-zero" -- "$FAKE_GRADLE"
run_guard 'argument validation: missing command' \
  2 0 ok \
  3 0 "test missing-command" --
run_guard 'argument validation: missing sleep' \
  2 0 ok \
  3

# --- regex pin: the VERIFICATION_RE assignment line must stay exactly this -----
# A future weakening of the script's signature regex (removed alternative,
# loosened context requirement) changes this line and fails the fixture.
EXPECTED_RE_LINE="VERIFICATION_RE='Dependency verification failed|verification[ -]metadata|artifacts? failed verification|failed verification:|checksum (verification|mismatch|failed|missing).{0,200}(verification|metadata|artifact)|artifact (verification|checksum) failed|signature verification (failed|failure|error|exception)|checksum verification failed'"
ACTUAL_RE_LINE="$(grep -E '^VERIFICATION_RE=' "$GUARD" || true)"
total=$((total + 1))
if [ "$ACTUAL_RE_LINE" = "$EXPECTED_RE_LINE" ]; then
  matched=$((matched + 1))
  printf 'PASS fixture regex pin (VERIFICATION_RE literal unchanged)\n'
else
  mismatched=$((mismatched + 1))
  printf '::error::FAIL fixture regex pin (VERIFICATION_RE assignment line changed)\n'
  printf '     expected: %s\n' "$EXPECTED_RE_LINE"
  printf '     actual:   %s\n' "$ACTUAL_RE_LINE"
fi

echo "----"
printf 'summary: %d fixtures, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
