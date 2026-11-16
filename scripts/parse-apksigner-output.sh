#!/usr/bin/env bash
# Parse `apksigner verify --verbose --print-certs` output and validate the
# release certificate pin. Shared by release CI (tag path) and PR CI fixtures
# so the parser is exercised without the release key.
#
# Multi-scheme rule: an APK whose multiple APK Signature Scheme versions all
# verify (e.g. v2 + v3) prints ONE certificate SHA-256 digest line PER
# VERIFIED scheme, each carrying the same certificate. This parser collects
# ALL digest lines and requires that (a) at least one is present and (b) every
# digest line carries the SAME digest after normalisation (colon/space
# stripping, A-F -> a-f lowercasing). Distinct digests mean distinct
# certificates (i.e. multiple signers) and are rejected. Zero digest lines
# are rejected.
#
# The `V[0-9]+(\.[0-9]+)* Signer:` prefix grammar covers the apksigner output
# family emitted by Android build-tools >= 34 (`V2 Signer:`, `V2.0 Signer:`,
# `V3 Signer:`, `V3.0 Signer:`, `V3.1 Signer:`, `V3.2 Signer:`, `V4 Signer:`,
# ...), plus the legacy `Signer #1 ` prefix.
#
# NOTE: this file is SHARED with the signing job — any change here lands in
# BOTH the sign job and the publish gate (release.yml). Do not change one
# side without the other.
#
# Usage:
#   EXPECTED_CERT_SHA256=<lowercase-hex> parse-apksigner-output.sh < output.txt
#   parse-apksigner-output.sh --parse-only < output.txt   (no pin check)
#
# Prints:
#   signers=<n>
#   cert=<lowercase-hex>   (the single agreed digest across all scheme lines)
# Exit 0 on success (and pin match if EXPECTED_CERT_SHA256 is set), 1 on
# malformed output, signer-count mismatch, digest-line mismatch, or
# certificate mismatch.
set -euo pipefail

PARSE_ONLY=0
if [ "${1:-}" = "--parse-only" ]; then
  PARSE_ONLY=1
  shift
fi

# Read input to EOF so callers can pipe output directly.
verify_output="$(cat)"
if [ -z "$verify_output" ]; then
  echo "::error::Empty apksigner output." >&2
  exit 1
fi

signers="$(printf '%s\n' "$verify_output" | sed -n 's/^Number of signers: //p' | sed -n '1p' | tr -d '[:space:]' || true)"
if [ -z "$signers" ]; then
  echo "::error::Could not determine signer count (missing 'Number of signers:' line)." >&2
  exit 1
fi
if [ "$signers" != "1" ]; then
  echo "::error::Expected exactly one signer (found $signers)" >&2
  exit 1
fi

# Anchor to the exact certificate digest lines (avoids DN-injection via crafted
# DN containing "SHA-256 digest:" substring). Public-key digest lines are
# intentionally ignored. A multi-scheme APK prints one digest line per
# verified scheme; there must be at least one certificate digest line, and
# every digest line must carry the same (normalised) digest.
digest_lines="$(printf '%s\n' "$verify_output" | grep -E '^(Signer #1 |V[0-9]+(\.[0-9]+)* Signer: )certificate SHA-256 digest:' || true)"
if [ -z "$digest_lines" ]; then
  echo "::error::no certificate SHA-256 digest line found (expected at least one)." >&2
  exit 1
fi
# Fail closed on any additional signer entry (Signer #2+): a forged
# "Number of signers: 1" must not smuggle a second signer's digest line
# past the single-certificate check.
if printf '%s\n' "$verify_output" | grep -Eq '^Signer #([2-9]|[0-9]{2,})([^0-9]|$)'; then
  echo "::error::unexpected additional signer entry (Signer #2 or higher) found; refusing." >&2
  exit 1
fi
# Normalise one digest line per output line: strip the scheme prefix,
# remove colons/spaces/CR (CRLF-tolerant), lowercase. \r is stripped here
# because \r is NOT hex and would otherwise corrupt the digest; the pin
# check remains the security envelope.
# The normalised stream is captured ONCE into cert_lines and consumed
# twice (distinct-count check, then cert pick).
normalize_certs() {
  printf '%s\n' "$digest_lines" \
    | sed -E 's/^(Signer #1 |V[0-9]+(\.[0-9]+)* Signer: )certificate SHA-256 digest:[[:space:]]*//' \
    | tr -d ' :\r' | tr 'A-F' 'a-f'
}
# Count ALL normalised lines (including empty ones): an empty digest line is
# not "the same digest" and must not be silently ignored. sort consumes all
# input, so this pipeline is immune to early-close SIGPIPE.
cert_lines="$(normalize_certs)"
distinct_count="$(printf '%s\n' "$cert_lines" | sort -u | wc -l | tr -d '[:space:]')"
if [ "$distinct_count" -ne 1 ]; then
  echo "::error::certificate digest lines disagree (found $distinct_count distinct digests; all verified signing schemes must carry the same certificate)." >&2
  exit 1
fi
cert="${cert_lines%%$'\n'*}"

if ! [[ "$cert" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::Could not parse signer certificate digest (got '$cert')." >&2
  exit 1
fi

echo "signers=$signers"
echo "cert=$cert"

if [ "$PARSE_ONLY" -eq 1 ]; then
  exit 0
fi

EXPECTED_RAW="${EXPECTED_CERT_SHA256:-}"
EXPECTED="$(printf '%s' "$EXPECTED_RAW" | tr -d ' :\r' | tr 'A-F' 'a-f')"
if [ -z "$EXPECTED_RAW" ]; then
  echo "::error::EXPECTED_CERT_SHA256 is not set." >&2
  exit 1
fi
if ! [[ "$EXPECTED" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::EXPECTED_CERT_SHA256 is malformed." >&2
  exit 1
fi
if [ "$cert" != "$EXPECTED" ]; then
  echo "::error::Certificate mismatch (got $cert, expected $EXPECTED)" >&2
  exit 1
fi

echo "PASS: signer count and certificate pin verified."
