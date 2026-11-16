#!/usr/bin/env bash
# Publish-time integrity gate for the release publish job.
#
# Re-verifies the bytes about to be attached to the GitHub Release so a
# corrupted or tampered artifact never reaches it (SHA256SUMS.txt was
# written next to the verified bytes at sign time and travels with them as
# a workflow artifact). Fail-closed, in order:
#
#   1. Structural validation of SHA256SUMS.txt (embedded python3):
#      every record must be "<64-hex><1-2 spaces><name>" where <name> is a bare
#      BASENAME — no backslashes, no `/`, no absolute paths, no `.`/`..`, no
#      empty names, no leading/trailing whitespace, no CRLF/NUL lines, no
#      duplicates.
#      Each listed file must be a REGULAR, NON-SYMLINK file resolving
#      inside the publish directory. The STAGED set (os.scandir, regular
#      non-symlink files, excluding SHA256SUMS.txt) must equal the listed
#      set exactly — both directions — and the staged set must contain
#      ZERO directories and ZERO symlinks (a staged dir/symlink fails even
#      if unlisted). SHA256SUMS.txt is the only allowed extra file. At
#      least one *.apk (case-insensitive) must be listed AND staged.
#   2. `sha256sum -c SHA256SUMS.txt` — final byte-verification step, run
#      only AFTER the structural checks pass (it proves the bytes).
#
# Notes on the trust model and failure modes:
#
#   - The early die paths (an unreadable, symlinked, or otherwise non-regular
#     SHA256SUMS.txt — e.g. a fifo/socket/directory — including failure to
#     stat it, and a sums file containing a NUL byte) print ::error:: and exit 1
#     BEFORE any violations accumulate — they fail fast because parsing cannot
#     proceed; every other failure mode accumulates ALL violations and prints
#     them together before exiting 1.
#   - Listed files are required to be regular files at a path inside the
#     publish directory; inode provenance is NOT enforced — a hardlink to an
#     outside inode is indistinguishable from a regular file to this gate.
#     The digest recorded in SHA256SUMS.txt is the byte authority.
#   - Coreutils availability: the byte-verification step shells out to GNU
#     coreutils' sha256sum(1) with `-c`; this gate is written and tested
#     against GNU coreutils check semantics (ubuntu-latest CI). No other
#     implementation is supported.
#   - Entry names containing `\` are rejected outright (the checker treats
#     any backslash in the name as a hard error). GNU coreutils lists such
#     lines with a leading `\` (escaped-line marker) and would then check a
#     DIFFERENT filename than the one validated here; rejecting both keeps
#     the gate fail-closed under every sha256sum implementation. Normalize
#     or reject such names at sign time regardless.
#   - Accepted TOCTOU: the python structural gate and the subsequent
#     `sha256sum -c` are two separate filesystem reads; a payload file
#     swapped between them is caught by the digest check, not by
#     re-validation. The sums file itself is re-read by `sha256sum -c`
#     (a manifest swapped to a symlink in that window is out of scope of
#     this two-step gate — the payload bytes verified by `sha256sum -c` are the authority). This window
#     is accepted as inherent to a two-step gate.
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

# Structural validation + bidirectional attachment-set equality. On any
# violation, prints one ::error:: line per problem and exits 1. On success,
# prints the number of SHA256SUMS-listed records and exits 0.
if ! listed_count="$(python3 - <<'PYEOF'
import sys

def main():
    import stat
    import os
    import re

    SUMS = 'SHA256SUMS.txt'
    RECORD_RE = re.compile(r'^([0-9a-fA-F]{64}) {1,2}([^/]+)$')
    SPLIT_RE = re.compile(r'^([0-9a-fA-F]{64}) +(.+)$')

    violations = []

    def read_sums_bytes():
        # Refuse to read SHA256SUMS.txt unless it is a regular file, and
        # NEVER follow it: a symlink points elsewhere (an attacker could
        # plant one to a valid-looking sums file elsewhere), and a fifo or
        # socket would block the open. Lstat first — these classes all die
        # before any bytes of the target are read. Fail-fast before parsing.
        try:
            st = os.lstat(SUMS)
        except OSError as err:
            print('::error::cannot stat SHA256SUMS.txt in publish directory: %s' % err, file=sys.stderr)
            sys.exit(1)
        if stat.S_ISLNK(st.st_mode):
            print('::error::symlinked SHA256SUMS.txt not allowed — refusing to read it without following symlinks', file=sys.stderr)
            sys.exit(1)
        if not stat.S_ISREG(st.st_mode):
            print('::error::SHA256SUMS.txt is not a regular file — refusing to parse', file=sys.stderr)
            sys.exit(1)
        # lstat → open is not atomic; O_NOFOLLOW on the final open closes
        # the swap-to-symlink window between the two syscalls for free.
        try:
            fd = os.open(SUMS, os.O_RDONLY | os.O_NOFOLLOW)
        except OSError as err:
            print('::error::cannot read SHA256SUMS.txt in publish directory: %s' % err, file=sys.stderr)
            sys.exit(1)
        with os.fdopen(fd, 'rb') as fh:
            return fh.read()

    raw = read_sums_bytes()

    if b'\0' in raw:
        print('::error::SHA256SUMS.txt contains a NUL byte — refusing to parse', file=sys.stderr)
        sys.exit(1)

    lines = raw.split(b'\n')
    if lines and lines[-1] == b'':
        lines.pop()  # a single trailing LF is fine; embedded blank lines are not

    entries = []  # (name, lineno) — validated bare basenames only
    for lineno, line_bytes in enumerate(lines, start=1):
        if b'\r' in line_bytes:
            violations.append('::error::SHA256SUMS.txt:%d: CRLF line ending — only Unix LF line endings are allowed' % lineno)
            continue
        line = line_bytes.decode('utf-8', errors='surrogateescape')
        if line == '':
            violations.append('::error::SHA256SUMS.txt:%d: empty line' % lineno)
            continue
        if line.strip() == '':
            violations.append('::error::SHA256SUMS.txt:%d: blank line' % lineno)
            continue
        m = RECORD_RE.match(line)
        if m:
            name = m.group(2)
            if name in ('.', '..'):
                violations.append("::error::SHA256SUMS.txt:%d: '%s' is not a valid entry name" % (lineno, name))
                continue
            if '\\' in name:
                violations.append('::error::SHA256SUMS.txt:%d: backslash not allowed in entry name (unsupported — normalize or reject at sign time): %s' % (lineno, name))
                continue
            if name != name.strip():
                violations.append('::error::SHA256SUMS.txt:%d: leading/trailing whitespace in entry name: %r' % (lineno, name))
                continue
            entries.append((name, lineno))
            continue
        # Diagnostic classification so failures name the actual problem.
        m2 = SPLIT_RE.match(line)
        if m2:
            name = m2.group(2)
            if name.startswith('/'):
                violations.append('::error::SHA256SUMS.txt:%d: absolute path not allowed (entry must be a bare basename): %s' % (lineno, name))
            elif any(part == '..' for part in name.split('/')):
                violations.append('::error::SHA256SUMS.txt:%d: path traversal not allowed (entry must be a bare basename): %s' % (lineno, name))
            elif '/' in name:
                violations.append('::error::SHA256SUMS.txt:%d: path separator not allowed (entry must be a bare basename, no subdirectories): %s' % (lineno, name))
            else:
                violations.append('::error::SHA256SUMS.txt:%d: malformed record: %s' % (lineno, line))
            continue
        violations.append('::error::SHA256SUMS.txt:%d: malformed record (expected "<64-hex><1-2 spaces><name>"): %s' % (lineno, line))

    if not entries and not violations:
        violations.append('::error::SHA256SUMS.txt contains no entries (expected "<64-hex><1-2 spaces><name>" lines)')

    seen = {}
    for name, lineno in entries:
        if name in seen:
            violations.append('::error::duplicate entry in SHA256SUMS.txt: %s (lines %d and %d)' % (name, seen[name], lineno))
        else:
            seen[name] = lineno

    dir_real = os.path.realpath('.')
    for name, lineno in entries:
        if os.path.islink(name):
            violations.append('::error::listed file is a symlink, not a regular file: %s' % name)
            continue
        if not os.path.exists(name):
            violations.append('::error::listed file is missing from the publish directory: %s' % name)
            continue
        if os.path.isdir(name):
            violations.append('::error::listed file is a directory: %s' % name)
            continue
        if not os.path.isfile(name):
            violations.append('::error::listed file is not a regular file: %s' % name)
            continue
        if os.path.dirname(os.path.realpath(name)) != dir_real:
            violations.append('::error::listed file resolves outside the publish directory: %s' % name)

    staged_regular = set()
    try:
        scan = list(os.scandir('.'))
    except OSError as err:
        scan = []
        violations.append('::error::cannot scan publish directory: %s' % err)
    for entry in scan:
        if entry.is_symlink():
            violations.append('::error::staged entry is a symlink (not allowed): %s' % entry.name)
            continue
        if entry.is_dir(follow_symlinks=False):
            violations.append('::error::staged entry is a directory (not allowed): %s' % entry.name)
            continue
        if not entry.is_file(follow_symlinks=False):
            violations.append('::error::staged entry is not a regular file (not allowed): %s' % entry.name)
            continue
        if entry.name == SUMS:
            continue  # SHA256SUMS.txt is the only allowed extra file
        staged_regular.add(entry.name)

    listed_set = {name for name, _ in entries}
    unlisted = sorted(staged_regular - listed_set)
    missing = sorted(listed_set - staged_regular)
    if unlisted:
        violations.append('::error::unlisted file(s) staged for release: %s' % ' '.join(unlisted))
    if missing:
        violations.append('::error::listed file(s) not staged as regular files: %s' % ' '.join(missing))

    listed_apks = [n for n in listed_set if n.lower().endswith('.apk')]
    staged_apks = [n for n in staged_regular if n.lower().endswith('.apk')]
    if not listed_apks or not staged_apks:
        violations.append('::error::no *.apk file listed/staged — at least one *.apk is required for the release')

    if violations:
        for v in violations:
            print(v, file=sys.stderr)
        sys.exit(1)

    print(len(entries))
    sys.exit(0)

try:
    main()
except Exception as exc:
    print('::error::internal: %s: %s' % (type(exc).__name__, exc), file=sys.stderr)
    sys.exit(1)

PYEOF
)"; then
  exit 1
fi

sha256sum -c SHA256SUMS.txt
echo "PASS: release-publish holds exactly the $listed_count file(s) listed in SHA256SUMS.txt + SHA256SUMS.txt"
