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
# Signature detection is two-pass: a line-based grep, then a CR-dropped,
# newline-collapsed fallback with the SAME VERIFICATION_RE (Gradle wraps long
# lines, so a signature can be split across line boundaries).
# All other failures are retried up to <max-attempts>, sleeping
# <sleep-seconds> between attempts. Exit 0 when the command eventually
# succeeds; nonzero otherwise.
#
# Deliberately `set -u` + pipefail only: callers assert exit codes, so no
# `set -e`.
set -uo pipefail
# GNU grep in UTF-8 locales treats `.` as a character match, which breaks the
# 200-byte byte budget and cannot match invalid-UTF-8 gap bytes; CI runners
# export LANG=C.UTF-8. LC_ALL=C is applied INLINE to the greps (and kept on
# the tr reprint filters) rather than exported, so the wrapped command's
# environment is untouched (JDK 17 file.encoding is locale-derived).

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
case "$LABEL" in *$'\n'*|*$'\r'*) echo "ci-retry-guard.sh: <label> must not contain CR or LF" >&2; exit 2;; esac
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
# miss a bare line). The newline-collapsed fallback below re-matches with the
# SAME regex; after CR-dropping + LF collapsing a wrap stands for one extra
# byte (a CRLF wrap also stands for one byte). Any of
# these wordings appearing in a FAILED run is a
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
  if LC_ALL=C grep -Eaqi "$VERIFICATION_RE" "$LOG"; then
    echo "::error::$LABEL failed with a dependency-verification error — NOT retrying (verification failures are deterministic; fix verification-metadata.xml or the pinned checksums). Matched lines:"
    LC_ALL=C grep -Enia "$VERIFICATION_RE" "$LOG" | head -n 3 | LC_ALL=C tr -d '\000-\010\013\014\015-\037\177'
    exit 1
  fi
  # Multiline fallback: Gradle wraps long lines, so a verification signature
  # can be split across line boundaries and defeat the line-based grep above.
  # Drop CR bytes and collapse LF to spaces, then re-match with the SAME
  # VERIFICATION_RE. After collapsing, a LF wrap stands for one extra byte
  # (CR bytes are dropped, so a CRLF wrap also stands for exactly one extra
  # byte), so the legacy `.{0,200}` co-occurrence budget survives wraps; the
  # budget still caps the collapsed gap at 200 bytes. An LF wrap mid-token
  # cannot match, but CR bytes are dropped everywhere in the collapsed
  # stream, so a mid-token CR stitches bytes (deliberate, fail-closed-biased
  # over-match — endorsed because deleting bytes can only strengthen matches,
  # never create a non-match or fake a clean bill). Note the widened
  # surface: the co-occurrence alternative can now match across arbitrary line
  # boundaries anywhere in the log (still bounded to 200 collapsed bytes; the
  # anchor must still precede the context word), and `artifact` matches as a
  # substring (e.g. `artifactory`) — accepted trade-off, pinned by the
  # proxy-504 fixture. A proxy-504 body with 'checksum mismatch' but no
  # verification/metadata/artifact wording within the budget STILL does not
  # match. Full attempt output is already teed to the console; print the
  # matched signature(s) bounded (head -n 3), not the whole collapsed log.
  if LC_ALL=C grep -Eaqi "$VERIFICATION_RE" < <(tr -d '\r' < "$LOG" | tr '\n' ' '); then
    echo "::error::$LABEL failed with a dependency-verification error SPLIT ACROSS LINES (multiline match) — NOT retrying (verification failures are deterministic; fix verification-metadata.xml or the pinned checksums)."
    tr -d '\r' < "$LOG" | tr '\n' ' ' | LC_ALL=C grep -Eaio "$VERIFICATION_RE" | head -n 3 | LC_ALL=C tr -d '\000-\010\013\014\016-\037\177' | sed 's/^/     matched: /'
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
