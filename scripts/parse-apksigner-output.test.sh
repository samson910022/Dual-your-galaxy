#!/usr/bin/env bash
# Self-test for scripts/parse-apksigner-output.sh — SDK-free, synthetic cases
# fed to the parser via heredocs. Pins the parser's semantics:
#
#   - v1 legacy (Signer #1), v2, v2.0, v3 (bare `V3 Signer:`), v3.0, v3.1,
#     v3.2, v4 and multi-scheme (identical certs) outputs PASS;
#   - CRLF line endings are tolerated (pins parser item: \r stripping);
#   - conflicting digests across scheme lines, multiple signers, zero digest
#     lines, malformed signer prefixes, an extra "Signer #2+" entry smuggled
#     past a forged "Number of signers: 1", and pin mismatches FAIL closed;
#   - --parse-only skips the pin check;
#   - uppercase/colon-separated digests normalise to lowercase.
#
# Intentionally `set -u` (not -e): both exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

SCRIPT="scripts/parse-apksigner-output.sh"
if [ ! -f "$SCRIPT" ]; then
  echo "::error::script missing: $SCRIPT" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

# Synthetic 64-hex digests (not real keys, not the release fingerprint).
DIGEST_A="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
DIGEST_B="fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
DIGEST_A_COLON_UPPER="01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF"

cat > "$work_dir/v2-only.txt" <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
V2 Signer: certificate DN: CN=PR60 Test
V2 Signer: certificate SHA-256 digest: $DIGEST_A
V2 Signer: public key SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/v3.0-only.txt" <<EOF
Verifies
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
V3.0 Signer: certificate DN: CN=PR60 Test
V3.0 Signer: certificate SHA-256 digest: $DIGEST_A
V3.0 Signer: public key SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/v3-only.txt" <<EOF
Verifies
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
V3 Signer: certificate DN: CN=PR60 Test
V3 Signer: certificate SHA-256 digest: $DIGEST_A
V3 Signer: public key SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/v2-plus-v3.0-identical.txt" <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
V2 Signer: certificate DN: CN=PR60 Test
V2 Signer: certificate SHA-256 digest: $DIGEST_A
V3.0 Signer: certificate DN: CN=PR60 Test
V3.0 Signer: certificate SHA-256 digest: $DIGEST_A
V3.0 Signer: public key SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/conflicting-digests.txt" <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
V2 Signer: certificate DN: CN=PR60 Test
V2 Signer: certificate SHA-256 digest: $DIGEST_A
V3.0 Signer: certificate DN: CN=PR60 Other
V3.0 Signer: certificate SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/two-signers.txt" <<EOF
Verifies
Number of signers: 2
Signer #1 certificate DN: CN=PR60 Test
Signer #1 certificate SHA-256 digest: $DIGEST_A
EOF

cat > "$work_dir/dn-only.txt" <<EOF
Verifies
Number of signers: 1
Signer #1 certificate DN: CN=PR60 Test no SHA-256 digest here
Signer #1 public key SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/malformed-prefix.txt" <<EOF
Verifies
Number of signers: 1
V3 Party Signer: certificate SHA-256 digest: $DIGEST_A
EOF

cat > "$work_dir/v1-signer#1.txt" <<EOF
Verifies
Number of signers: 1
Signer #1 certificate DN: CN=PR60 Test
Signer #1 certificate SHA-256 digest: $DIGEST_A
Signer #1 public key SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/v2.0-signer.txt" <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
V2.0 Signer: certificate DN: CN=PR60 Test
V2.0 Signer: certificate SHA-256 digest: $DIGEST_A
EOF

cat > "$work_dir/v3.1-signer.txt" <<EOF
Verifies
Verified using v3.1 scheme (APK Signature Scheme v3.1): true
Number of signers: 1
V3.1 Signer: certificate DN: CN=PR60 Test
V3.1 Signer: certificate SHA-256 digest: $DIGEST_A
EOF

cat > "$work_dir/v3.2-signer.txt" <<EOF
Verifies
Verified using v3.2 scheme (APK Signature Scheme v3.2): true
Number of signers: 1
V3.2 Signer: certificate DN: CN=PR60 Test
V3.2 Signer: certificate SHA-256 digest: $DIGEST_A
EOF

cat > "$work_dir/v4-signer.txt" <<EOF
Verifies
Verified using v4 scheme (APK Signature Scheme v4): true
Number of signers: 1
V4 Signer: certificate DN: CN=PR60 Test
V4 Signer: certificate SHA-256 digest: $DIGEST_A
EOF

cat > "$work_dir/crlf-output.txt" <<EOF
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
V2 Signer: certificate DN: CN=PR60 Test
V2 Signer: certificate SHA-256 digest: $DIGEST_A
EOF
# Convert every line to CRLF (digest line + signers line + DN line).
sed -i 's/$/\r/' "$work_dir/crlf-output.txt"

cat > "$work_dir/extra-signer#2-same-digest.txt" <<EOF
Verifies
Number of signers: 1
Signer #1 certificate DN: CN=PR60 Test
Signer #1 certificate SHA-256 digest: $DIGEST_A
Signer #2 certificate DN: CN=PR60 Evil
Signer #2 certificate SHA-256 digest: $DIGEST_A
EOF

cat > "$work_dir/extra-signer#2-diff-digest.txt" <<EOF
Verifies
Number of signers: 1
Signer #1 certificate DN: CN=PR60 Test
Signer #1 certificate SHA-256 digest: $DIGEST_A
Signer #2 certificate DN: CN=PR60 Evil
Signer #2 certificate SHA-256 digest: $DIGEST_B
EOF

cat > "$work_dir/uppercase-colon-digest.txt" <<EOF
Verifies
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
V3.0 Signer: certificate DN: CN=PR60 Test
V3.0 Signer: certificate SHA-256 digest: $DIGEST_A_COLON_UPPER
EOF

pass_count=0
mismatch=0

# assert_case <name> <want_rc> <input-file> [want-substring] [want-cert] [mode] [pin]
#   mode: pin (default; EXPECTED_CERT_SHA256=<pin> from $7) | parse-only (no pin check)
assert_case() {
  local name="$1" want_rc="$2" input="$3" want_sub="${4-}" want_cert="${5-}" mode="${6:-pin}" pin="${7-}"
  local output rc
  if [ "$mode" = "parse-only" ]; then
    output="$(env -u EXPECTED_CERT_SHA256 bash "$SCRIPT" --parse-only <"$input" 2>&1)"
  else
    output="$(EXPECTED_CERT_SHA256="$pin" bash "$SCRIPT" <"$input" 2>&1)"
  fi
  rc=$?
  local ok=1
  if [ "$rc" -ne "$want_rc" ]; then ok=0; fi
  if [ -n "$want_sub" ] && ! printf '%s' "$output" | grep -qF "$want_sub"; then ok=0; fi
  if [ -n "$want_cert" ]; then
    local got_cert
    got_cert="$(printf '%s\n' "$output" | sed -n 's/^cert=//p' | tail -n 1)"
    if [ "$got_cert" != "$want_cert" ]; then ok=0; fi
  fi
  if [ "$ok" -eq 1 ]; then
    echo "PASS $name (exit $rc as expected)"
    pass_count=$((pass_count + 1))
  else
    echo "MISMATCH $name (want exit $want_rc, got exit $rc) — output was:"
    printf '%s\n' "$output"
    mismatch=$((mismatch + 1))
  fi
}

assert_case "pass-v2-only (one V2 Signer digest line)" 0 "$work_dir/v2-only.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-v3-signer (bare V3 Signer digest line)" 0 "$work_dir/v3-only.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-v3.0-only (round-4 defect output)" 0 "$work_dir/v3.0-only.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-v2+v3.0 identical certs (two digest lines)" 0 "$work_dir/v2-plus-v3.0-identical.txt" "" "$DIGEST_A" pin "$DIGEST_A"
assert_case "fail-conflicting-digests" 1 "$work_dir/conflicting-digests.txt" "certificate digest lines disagree" "" pin "$DIGEST_A"
assert_case "fail-two-signers" 1 "$work_dir/two-signers.txt" "Expected exactly one signer" "" pin "$DIGEST_A"
assert_case "fail-no-digest-lines (DN lines only)" 1 "$work_dir/dn-only.txt" "no certificate SHA-256 digest line" "" pin "$DIGEST_A"
assert_case "fail-malformed-signer-prefix" 1 "$work_dir/malformed-prefix.txt" "no certificate SHA-256 digest line" "" pin "$DIGEST_A"
assert_case "fail-pin-mismatch" 1 "$work_dir/v2-only.txt" "Certificate mismatch" "" pin "$DIGEST_B"
assert_case "pass-parse-only-ignores-pin" 0 "$work_dir/v2-only.txt" "" "$DIGEST_A" parse-only
assert_case "pass-v1-signer#1 (legacy Signer #1 digest line)" 0 "$work_dir/v1-signer#1.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-v2.0-signer (V2.0 Signer digest line)" 0 "$work_dir/v2.0-signer.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-v3.1-signer (V3.1 Signer digest line)" 0 "$work_dir/v3.1-signer.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-v3.2-signer (V3.2 Signer digest line)" 0 "$work_dir/v3.2-signer.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-v4-signer (V4 Signer digest line)" 0 "$work_dir/v4-signer.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "pass-crlf-output (CRLF line endings tolerated)" 0 "$work_dir/crlf-output.txt" "PASS: signer count and certificate pin verified." "$DIGEST_A" pin "$DIGEST_A"
assert_case "fail-extra-signer#2 (forged signers:1, same digest)" 1 "$work_dir/extra-signer#2-same-digest.txt" "unexpected additional signer entry" "" pin "$DIGEST_A"
assert_case "fail-extra-signer#2-diff-digest (forged signers:1, different digest)" 1 "$work_dir/extra-signer#2-diff-digest.txt" "unexpected additional signer entry" "" pin "$DIGEST_A"
assert_case "pass-uppercase-colon-digest" 0 "$work_dir/uppercase-colon-digest.txt" "" "$DIGEST_A" pin "$DIGEST_A"

total=$((pass_count + mismatch))
if [ "$mismatch" -ne 0 ]; then
  echo "----"
  echo "summary: $total cases, $pass_count as expected, $mismatch mismatched" >&2
  exit 1
fi
echo "----"
echo "summary: $total cases, $pass_count as expected, 0 mismatched"
