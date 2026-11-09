#!/usr/bin/env bash
# Parse `apksigner verify --verbose --print-certs` output and validate the
# release certificate pin. Shared by release CI (tag path) and PR CI fixtures
# so the parser is exercised without the release key.
#
# Usage:
#   EXPECTED_CERT_SHA256=<lowercase-hex> parse-apksigner-output.sh < output.txt
#   parse-apksigner-output.sh --parse-only < output.txt   (no pin check)
#
# Prints:
#   signers=<n>
#   cert=<lowercase-hex>
# Exit 0 on success (and pin match if EXPECTED_CERT_SHA256 is set), 1 on
# malformed output, signer-count mismatch, or certificate mismatch.
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

signers="$(printf '%s\n' "$verify_output" | sed -n 's/^Number of signers: //p' | head -n 1 | tr -d '[:space:]' || true)"
if [ -z "$signers" ]; then
  echo "::error::Could not determine signer count (missing 'Number of signers:' line)." >&2
  exit 1
fi
if [ "$signers" != "1" ]; then
  echo "::error::Expected exactly one signer (found $signers)" >&2
  exit 1
fi

# Anchor to the exact certificate digest line (avoids DN-injection via crafted
# DN containing "SHA-256 digest:" substring). Public-key digest lines are
# intentionally ignored; there must be exactly one certificate digest line.
cert_count="$(printf '%s\n' "$verify_output" | grep -cE '^(Signer #1 |V2 Signer: )certificate SHA-256 digest:' || true)"
if [ "${cert_count:-0}" -ne 1 ]; then
  echo "::error::Expected exactly one certificate SHA-256 digest line (found ${cert_count:-0})." >&2
  exit 1
fi
cert="$(printf '%s\n' "$verify_output" | sed -nE 's/^(Signer #1 |V2 Signer: )certificate SHA-256 digest:[[:space:]]*//p' | head -n 1 | tr -d ' :' | tr 'A-F' 'a-f')"

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
EXPECTED="$(printf '%s' "$EXPECTED_RAW" | tr -d ' :' | tr 'A-F' 'a-f')"
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
