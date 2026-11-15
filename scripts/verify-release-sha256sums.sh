#!/usr/bin/env bash
# Publish-time integrity gate for the release publish job.
#
# Re-verifies the bytes about to be attached to the GitHub Release so a
# corrupted or tampered artifact never reaches it (SHA256SUMS.txt was
# written next to the verified bytes at sign time and travels with them as
# a workflow artifact). Two checks, both fail closed:
#
#   1. `sha256sum -c SHA256SUMS.txt` must pass — every listed file must
#      exist and match its recorded digest.
#   2. Attachment-set equality — the publish directory must hold EXACTLY
#      the SHA256SUMS-listed files plus SHA256SUMS.txt itself. Any extra
#      staged file (e.g. an unverified APK / stray blob) fails the gate, so
#      the release can never ship more or fewer files than the verified
#      set. Filenames with spaces are handled: the hash prefix is stripped
#      with a sed that tolerates both single- and multi-space separators.
#
# Usage: verify-release-sha256sums.sh [publish-dir]
#   Without an argument, defaults to release-publish/ (the artifact the
#   release workflow downloads); a missing default dies with a usage error.
#   Pass the publish directory explicitly to verify anywhere else.
set -euo pipefail

if [ "$#" -gt 1 ]; then
  echo "::error::usage: $0 [publish-dir]" >&2
  exit 2
fi
publish_dir="${1:-}"
if [ -z "$publish_dir" ]; then
  if [ -d release-publish ]; then
    publish_dir="release-publish"
  else
    echo "::error::usage: $0 [publish-dir] (default 'release-publish/' not found — run from the repository root or pass the publish directory)" >&2
    exit 2
  fi
fi
if [ ! -d "$publish_dir" ]; then
  echo "::error::publish directory not found: $publish_dir" >&2
  exit 2
fi

cd "$publish_dir"
sha256sum -c SHA256SUMS.txt
extra="$(comm -13 <(sed -E 's/^[0-9a-fA-F]{64} +//' SHA256SUMS.txt | sort -u) <(ls -A | sort) | grep -Fvx 'SHA256SUMS.txt' || true)"
if [ -n "$extra" ]; then echo "::error::unlisted file(s) staged for release: $extra" >&2; exit 1; fi
echo "PASS: release-publish holds exactly the SHA256SUMS-listed files + SHA256SUMS.txt"
