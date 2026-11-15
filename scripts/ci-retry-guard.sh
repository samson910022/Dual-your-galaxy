#!/usr/bin/env bash
# ci-retry-guard.sh — retry wrapper for CI gradle invocations.
#
# Usage: bash scripts/ci-retry-guard.sh <max-attempts> <sleep-seconds> <label> -- <command...>
#
# Runs <command...> with combined stdout+stderr streamed to the console AND
# captured to a log. If the command fails AND its output carries a
# dependency-verification signature (Gradle's dependency verification does
# not fail intermittently — retrying only hides it), the guard prints a
# ::error:: with the matched lines and exits 1 IMMEDIATELY, with NO retry.
# All other failures are retried up to <max-attempts>, sleeping
# <sleep-seconds> between attempts. Exit 0 when the command eventually
# succeeds; nonzero otherwise.
#
# Deliberately `set -u` + pipefail only: callers assert exit codes, so no
# `set -e`.
set -uo pipefail

if [ "$#" -lt 4 ]; then
  echo "usage: ci-retry-guard.sh <max-attempts> <sleep-seconds> <label> -- <command...>" >&2
  exit 2
fi
if ! [[ "$1" =~ ^[0-9]+$ ]] || ! (( 10#$1 >= 1 )); then
  echo "ci-retry-guard.sh: <max-attempts> must be a positive integer (got '$1')" >&2
  exit 2
fi
if ! [[ "$2" =~ ^[0-9]+$ ]]; then
  echo "ci-retry-guard.sh: <sleep-seconds> must be a non-negative integer (got '$2')" >&2
  exit 2
fi
MAX_ATTEMPTS="$1"; SLEEP_SECONDS="$2"; LABEL="$3"; shift 3
if [ "${1:-}" = "--" ]; then
  shift
fi
if [ "$#" -lt 1 ]; then
  echo "ci-retry-guard.sh: no command given" >&2
  exit 2
fi

# Fail-fast signatures (case-insensitive, extended grep). 'Dependency
# verification failed' and 'verification-metadata' are the observed CI #304
# signatures and stay as-is; the `verification[ -]metadata` branch additionally
# matches the spaced 'Verification metadata' wording (grep -E alternation is
# byte-based, so the hyphen class keeps 'verification-metadata' intact). The
# checksum branch requires co-occurrence with verification context on the same
# line (grep -E is line-based) so a proxy-504 body containing 'checksum
# mismatch' WITHOUT verification wording does NOT hard-fail the guard; the
# 'artifacts? failed verification' and 'failed verification:' branches cover the
# other observed wordings. The signature-verification branch covers the PGP
# failure detail Gradle emits ('Signature verification failed: ...'). The bare 'checksum verification failed' branch
# covers the standalone wording with no trailing verification/metadata/artifact
# context (the legacy checksum branch requires that trailing context and would
# miss a bare line). Any of these wordings appearing in a FAILED run is a
# deterministic dependency-verification failure — retrying only hides it. Note:
# the legacy checksum branch STILL requires the trailing context; keep the
# bare alternative in sync when touching it.
VERIFICATION_RE='Dependency verification failed|verification[ -]metadata|artifacts? failed verification|failed verification:|checksum (verification|mismatch|failed|missing).{0,200}(verification|metadata|artifact)|artifact (verification|checksum) failed|signature verification (failed|failure|error|exception)|checksum verification failed'

LOG=$(mktemp "${TMPDIR:-/tmp}/ci-retry-guard.XXXXXX.log")
[ -n "$LOG" ] || { echo "ci-retry-guard.sh: mktemp failed" >&2; exit 2; }
trap 'rm -f "$LOG"' EXIT

attempt=1
while true; do
  : > "$LOG"
  "$@" 2>&1 | tee "$LOG"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -eq 0 ]; then
    exit 0
  fi
  if grep -Eqi "$VERIFICATION_RE" "$LOG"; then
    echo "::error::$LABEL failed with a dependency-verification error — NOT retrying (verification failures are deterministic; fix verification-metadata.xml or the pinned checksums). Matched lines:"
    grep -Eni "$VERIFICATION_RE" "$LOG"
    exit 1
  fi
  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "$LABEL attempt $attempt failed (max $MAX_ATTEMPTS)"
    exit 1
  fi
  echo "$LABEL attempt $attempt failed, sleep $SLEEP_SECONDS"
  sleep "$SLEEP_SECONDS"
  attempt=$((attempt + 1))
done
