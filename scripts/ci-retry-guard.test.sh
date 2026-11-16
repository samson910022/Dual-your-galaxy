#!/usr/bin/env bash
# Self-test for scripts/ci-retry-guard.sh — pins the fail-fast semantics of
# the dependency-verification signature check used by every until-gradlew
# retry loop in CI. A verification failure must exit 1 IMMEDIATELY with no
# retry (retrying only hides it); every other failure may retry up to the
# attempt cap and must exit 1 at the cap.
#
# Signature detection is two-pass: a line-based `grep -E`, then a
# CR-dropped, newline-collapsed fallback (`tr -d '\r' | tr '\n' ' '`, with the
# grep fed via process substitution) with the SAME VERIFICATION_RE. The legacy
# `.{0,200}` co-occurrence budget survives collapsing (a wrap stands for one
# extra byte — exactly one, even for CRLF, because CR bytes are dropped), so a
# proxy-504 body with 'checksum mismatch' but no verification|metadata|artifact
# wording within the budget still does NOT hard-fail — pinned below by the
# proxy-504 fixture. Its substring mirror image — 'artifact' present only as
# a substring of 'artifactory' inside the budget, with no verification|metadata
# wording anywhere in the body — DOES hard-fail, pinned below by the
# artifactory-in-budget fixture. A signature SPLIT ACROSS LINES (including across a CRLF
# boundary) is caught by the fallback and fails fast — pinned below. Also
# pinned below: a split whose collapsed gap exceeds the 200-byte budget is NOT
# matched and is retried up to the cap (overbudget-split-signature), a
# CRLF-wrapped signature fails fast (split-crlf-signature), and the fallback
# reprint is bounded to 3 `matched:` lines even when more matches exist
# (split-signatures-many + standalone count check at the end). Argument
# validation: labels carrying control characters (CR, LF) are rejected with
# exit 2 before the wrapped command runs ('label containing CR rejected',
# 'label containing LF rejected'; invocations == 0, the [[:cntrl:]] class pin). NUL-gap
# fixtures pin the `-a` flag that lets GNU grep's `.` span \x00: a NUL byte in
# the co-occurrence gap fails fast on a single line (nul-gap-singleline,
# line-based pass) and across a line boundary (nul-gap-split-signature,
# multiline fallback).
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
    # Signature printed across two lines: the line-based pass cannot match;
    # the newline-collapsed fallback can and fails fast.
    printf 'Dependency verification\nfailed for org.junit:junit-bom:5.9.2\n'
    exit 1 ;;
  split-checksum-context)
    # Line 1 carries 'checksum mismatch' with NO verification|metadata|artifact
    # word on the same line, so the line-based pass does NOT match; line 2
    # supplies the 'artifact' context word. The collapsed stream has
    # 'checksum mismatch ... artifact' well within the 200-byte budget, so
    # the fallback fails fast.
    printf 'checksum mismatch for org.example:library\nwhile verifying artifact foo\n'
    exit 1 ;;
  proxy-504-checksum-no-context)
    # False-positive resistance pin: 'checksum mismatch' in a proxy-504
    # transport body, followed by MORE THAN 200 bytes of unrelated filler
    # before the first verification|metadata|artifact word — beyond the
    # co-occurrence budget shared by both passes, so this does NOT
    # hard-fail and is retried up to the cap.
    printf 'FAILURE: Could not GET https://repo.example.com/org/junit/junit-bom/5.9.2/junit-bom-5.9.2.pom. Received status code 504 from server: Gateway Timeout; checksum mismatch while relaying the response body through the proxy and the mirror kept the connection open and streamed ordinary payload bytes that carry no repository policy wording at all and the client simply saw a truncated body before the artifact download eventually resumed from a different mirror host\n'
    exit 1 ;;
  artifactory-in-budget)
    # Substring trade-off pin: 'checksum mismatch' followed 17 bytes later
    # by 'artifact' (only as a substring of 'artifactory'), with NO
    # verification|metadata wording anywhere in the line, so the legacy
    # checksum branch's co-occurrence budget matches — the guard fails fast
    # (accepted substring trade-off pinned here).
    printf 'FAILURE: Could not GET https://repo.example.com/x/y.pom. Received status code 504 from server: Gateway Timeout; checksum mismatch relayed through artifactory proxy for org.example:lib-marker\n'
    exit 1 ;;
  split-signatures-many)
    # Five wrapped verification signatures; the line-based pass cannot match
    # any of them, the fallback matches all five — the reprint must bound
    # itself to head -n 3.
    for i in 1 2 3 4 5; do
      printf 'Dependency verification\nfailed for org.example.lib:1.0.0\n'
    done
    exit 1 ;;
  split-crlf-signature)
    # Same split as split-signature but with CRLF endings; the line-based
    # pass cannot match line 1, the CR/LF-collapsed fallback can.
    printf 'Dependency verification\r\nfailed for org.junit:junit-bom:5.9.2\r\n'
    exit 1 ;;
  nul-gap-singleline)
    # A single-line transport body with a NUL byte in the co-occurrence gap
    # between 'checksum mismatch' and the context word 'artifact' (plus raw
    # filler bytes). The gap is ~100 bytes, well within the 200-byte budget.
    # GNU grep's '.' cannot span a NUL without -a, so this pins -a on the
    # line-based pass. No verification|metadata|artifact wording appears
    # before the trailing context word.
    printf 'checksum mismatch \x00\x01\x02 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa artifact\n'
    exit 1 ;;
  nul-gap-split-signature)
    # A wrapped two-line case for the .-window with NULs: line 1 carries
    # 'checksum mismatch' followed by a NUL and raw high bytes but NO
    # verification|metadata|artifact word, so neither line matches the
    # line-based pass; the CR/LF-collapsed fallback matches within budget
    # only with -a (NUL is a matchable byte).
    printf 'verification failed: checksum mismatch \x00\xde\xad\xbe\xef\nwhile verifying artifact foo\n'
    exit 1 ;;
  overbudget-split-signature)
    # Fallback MISS pin: 'checksum mismatch' followed by more than 200 bytes
    # (measured 275) of unrelated filler before the first
    # verification|metadata|artifact word, so the collapsed co-occurrence
    # budget is exceeded — neither pass matches and the guard retries to the
    # cap. Line 2 deliberately contains no verification|metadata wording.
    printf 'checksum mismatch the response body travelled through a relay and a mirror and carried nothing repository specific whatsoever and the client merely observed a truncated stream of ordinary bytes on the wire before the mirror moved the fetch onto a different host entirely x\nwhile relaying the artifact bytes for org.example:library\n'
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
run_guard 'proxy-504 checksum mismatch NOT within 200-byte budget is retried (invocations == cap)' \
  1 3 proxy-504-checksum-no-context \
  3 0 "test proxy-504-checksum-no-context" -- "$FAKE_GRADLE"
run_guard 'overbudget split signature (collapsed gap > 200 bytes) is retried to cap (invocations == cap)' \
  1 3 overbudget-split-signature \
  3 0 "test overbudget-split-signature" -- "$FAKE_GRADLE"
run_guard 'artifactory-in-budget pins substring match fails fast (exit 1, 1 invocation)' \
  1 1 artifactory-in-budget \
  3 0 "test artifactory-in-budget" -- "$FAKE_GRADLE"

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

# --- (e) multiline fallback: signature split across lines fails fast ---------
# The guard collapses newlines to spaces and re-matches with the SAME
# VERIFICATION_RE, so a signature Gradle wrapped across line boundaries still
# fails fast (exit 1, exactly one invocation).
run_guard 'split verification signature fails fast via multiline fallback (exit 1, 1 invocation)' \
  1 1 split-signature \
  3 0 "test split-signature" -- "$FAKE_GRADLE"
run_guard 'split checksum context fails fast via multiline fallback (exit 1, 1 invocation)' \
  1 1 split-checksum-context \
  3 0 "test split-checksum-context" -- "$FAKE_GRADLE"
run_guard 'split CRLF signature fails fast via multiline fallback (exit 1, 1 invocation)' \
  1 1 split-crlf-signature \
  3 0 "test split-crlf-signature" -- "$FAKE_GRADLE"
run_guard '5 split signatures fail fast via multiline fallback (exit 1, 1 invocation)' \
  1 1 split-signatures-many \
  3 0 "test split-signatures-many" -- "$FAKE_GRADLE"

# --- (g) NUL bytes in the co-occurrence gap: -a pins on both passes ---------
# GNU grep's `.` cannot span \x00 without -a, so a hostile transport body
# carrying NUL bytes inside the gap failed open even under LC_ALL=C. Both
# fixtures carry no verification|metadata|artifact wording except the context
# word after the NULs, within the 200-byte budget.
run_guard 'NUL byte in gap fails fast (line-based pass, -a)' \
  1 1 nul-gap-singleline \
  3 0 "test nul-gap-singleline" -- "$FAKE_GRADLE"
run_guard 'NUL bytes in wrapped gap fail fast via multiline fallback' \
  1 1 nul-gap-split-signature \
  3 0 "test nul-gap-split-signature" -- "$FAKE_GRADLE"

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
run_guard 'argument validation: label containing CR rejected' \
  2 0 ok \
  3 0 $'test\rlabel' -- "$FAKE_GRADLE"
run_guard 'argument validation: label containing LF rejected' \
  2 0 ok \
  3 0 $'test\nlabel' -- "$FAKE_GRADLE"

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

# --- bound pin: the multiline-fallback reprint is capped at 3 matched: lines -
# split-signatures-many emits 5 wrapped signatures; the fallback reprint must
# print at most 3 'matched:' lines even though 5 matches exist. run_guard
# itself cannot assert this (it only checks rc + invocation count), so run the
# guard directly and count the reprinted lines.
total=$((total + 1))
bound_dir="$(mktemp -d "${TMPDIR:-/tmp}/ci-retry-guard-test.XXXXXX")"
: > "$bound_dir/invocations"
INVOCATIONS_FILE="$bound_dir/invocations" FAKE_MODE="split-signatures-many" \
  bash "$GUARD" 3 0 "test split-signatures-many-bound" -- "$FAKE_GRADLE" >"$bound_dir/output.log" 2>&1
bound_rc=$?
bound_count="$(grep -c '^     matched: ' "$bound_dir/output.log" || true)"
if [ "$bound_rc" = "1" ] && [ "$bound_count" = "3" ]; then
  matched=$((matched + 1))
  printf 'PASS fixture fallback reprint bounded to 3 matched: lines (split-signatures-many)\n'
else
  mismatched=$((mismatched + 1))
  printf '::error::FAIL fixture fallback reprint bound (expected exit 1 + 3 matched: lines, got exit %s + %s)\n' "$bound_rc" "$bound_count"
  sed 's/^/     /' "$bound_dir/output.log"
fi
rm -rf "$bound_dir"

echo "----"
printf 'summary: %d fixtures, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
