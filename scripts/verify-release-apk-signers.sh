#!/usr/bin/env bash
# Publish-time signer-certificate gate for the release publish job.
#
# Re-verifies the pinned release signer certificate against the APKs about
# to be attached to the GitHub Release, so a coherently substituted artifact
# (APKs swapped together with a co-located SHA256SUMS.txt between signing
# and publishing) is caught before it reaches the release. The SHA256SUMS
# gate alone proves byte-integrity against the sums file; this gate proves
# the bytes were signed by the pinned release key. Fail-closed, in order:
#
#   1. Usage/environment validation: an explicit publish directory argument
#      (default: release-publish/, a missing default is a usage error),
#      ANDROID_HOME pointing at an SDK with build-tools (the same assumption
#      the sign job makes — ubuntu-latest provides build-tools), and
#      EXPECTED_CERT_SHA256 (64 hex chars, the same pin hardcoded in the
#      sign job) — all checked before any APK is read.
#   2. For every *.apk in the publish directory: `apksigner verify
#      --verbose --print-certs` must succeed, and its output is fed to
#      scripts/parse-apksigner-output.sh — the EXACT parser and EXACT pin
#      the sign job uses — which enforces a single signer and the pinned
#      certificate SHA-256 digest. Any failure stops the job before publish.
#
# Scope and trust notes:
#
#   - Runs in the publish job AFTER the SHA256SUMS gate
#     (scripts/verify-release-sha256sums.sh); both gates must pass.
#   - Reuses the sign job's parser verbatim: the certificate check cannot
#     drift from what the sign job enforced, and the parser's own output
#     (signers=/cert=/PASS) is the only signer material this script prints —
#     it never prints cert bytes itself beyond the parser's output shape,
#     and raw apksigner output is shown only when verification fails.
#   - Zero APKs in the publish directory is a hard failure — this gate
#     refuses to pass an empty release.
#   - Fail-closed everywhere: any missing tool, missing/invalid environment,
#     unreadable APK, unexpected cert, or parser mismatch exits non-zero.
#
# Usage: verify-release-apk-signers.sh [apk-dir]
#   Without an argument, defaults to release-publish/ (the artifact the
#   release workflow downloads); a missing default dies with a usage error.
#   Pass the APK directory explicitly to verify anywhere else.
set -euo pipefail

if [ "$#" -gt 1 ]; then
  echo "::error::usage: $0 [apk-dir]" >&2
  exit 2
fi

EXPECT_DIR="${1:-}"
if [ -z "$EXPECT_DIR" ]; then
  if [ -d release-publish ]; then
    EXPECT_DIR="release-publish"
  else
    echo "::error::usage: $0 [apk-dir] (default 'release-publish/' not found — run from the repository root or pass the APK directory)" >&2
    exit 2
  fi
fi
if [ ! -d "$EXPECT_DIR" ]; then
  echo "::error::usage: apk directory not found or not a directory: $EXPECT_DIR" >&2
  exit 2
fi

# Same ANDROID_HOME/build-tools assumption as the sign job on ubuntu-latest.
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME/build-tools" ]; then
  echo "::error::ANDROID_HOME build-tools missing; cannot verify signatures." >&2
  exit 1
fi
build_tools="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n 1 || true)"
if [ -z "$build_tools" ]; then
  echo "::error::No build-tools found under \$ANDROID_HOME/build-tools; cannot verify signatures." >&2
  exit 1
fi
apksigner="$build_tools/apksigner"
if [ ! -x "$apksigner" ]; then
  echo "::error::apksigner not found under \$ANDROID_HOME/build-tools; cannot verify signatures." >&2
  exit 1
fi

EXPECTED_RAW="${EXPECTED_CERT_SHA256:-}"
if [ -z "$EXPECTED_RAW" ]; then
  echo "::error::EXPECTED_CERT_SHA256 is not set; refusing to verify signers." >&2
  exit 2
fi
if ! [[ "$EXPECTED_RAW" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "::error::EXPECTED_CERT_SHA256 is malformed (expected 64 hex characters)." >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

shopt -s nullglob
apks=("$EXPECT_DIR"/*.apk)
if [ "${#apks[@]}" -eq 0 ]; then
  echo "::error::No release APKs found in $EXPECT_DIR; refusing to publish an empty release." >&2
  exit 1
fi

count=0
for apk in "${apks[@]}"; do
  verify_output="$("$apksigner" verify --verbose --print-certs "$apk" 2>&1)" || {
    printf '%s\n' "$verify_output"
    echo "::error::Signature verification FAILED for $apk; refusing to publish." >&2
    exit 1
  }
  if ! EXPECTED_CERT_SHA256="$EXPECTED_CERT_SHA256" bash "$SCRIPT_DIR/parse-apksigner-output.sh" <<< "$verify_output"; then
    printf '%s
' "$verify_output"
    echo "::error::Signer/certificate check FAILED for $apk; refusing to publish." >&2
    exit 1
  fi
  count=$((count + 1))
  echo "PASS: signer certificate OK for $apk"
done
echo "PASS: all release APKs signed by the pinned certificate ($count verified)"
