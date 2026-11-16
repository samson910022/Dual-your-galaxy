# verify-release-sha256sums fixtures

Synthetic publish-directory copies for `scripts/verify-release-sha256sums.test.sh`.
Each directory is copied to a fresh mktemp dir by the self-test (the fixtures
themselves are never mutated). pass-* must exit 0, fail-* must exit 1.

- pass-listed-ok/ — two APKs with a SHA256SUMS.txt listing exactly them;
  expects exit 0 and "PASS: release-publish holds exactly the 2 file(s) listed in SHA256SUMS.txt + SHA256SUMS.txt".
- pass-spaced-name/ — one APK whose filename contains spaces (`My App-release.apk`),
  staged and listed with a correct digest and a 2-space separator;
  expects exit 0 and "PASS: release-publish holds exactly" (spaces in entry names are valid).
- fail-crlf/ — a digest-correct SHA256SUMS.txt for app.apk but with CRLF (\\r\\n)
  line endings; expects exit 1 and "CRLF" (only Unix LF line endings are allowed).
- fail-nul/ — SHA256SUMS.txt containing a NUL byte mid-file (before a line,
  after a valid LF-terminated first line); expects exit 1 and "NUL"
  (the sums file is rejected before parsing begins).
- fail-symlink-listed/ — SHA256SUMS.txt lists ONLY `linked.apk`, a symlink whose
  target `real.apk` is a regular file in the SAME directory (digest recorded is
  the target's real digest), so the staged-DIRECTORY arm does not fire;
  expects exit 1 and "symlink". The listed-file symlink arm is the arm this
  fixture isolates; the staged-symlink arm and the listed-but-not-staged arm
  also fire (unavoidable for any listed symlink), and the unlisted-file arm
  fires for the same-dir target (any regular-file target in the publish dir is
  staged-but-unlisted). This does NOT fully eliminate the realpath backstop,
  which remains as defense-in-depth.
- fail-extra-file/ — listed app.apk plus an unlisted extra-unlisted.bin;
  expects exit 1 and "unlisted file(s) staged for release" (bidirectional set-equality gate).
- fail-missing-file/ — SHA256SUMS.txt lists gone.apk, which is absent;
  expects exit 1 and an error naming gone.apk (missing-file gate fires before sha256sum -c).
- fail-forged-sums/ — app.apk present but its recorded digest is 64 zeros;
  expects exit 1 and sha256sum's FAILED marker (structural gate passes, byte check fails).
- fail-empty/ — an empty SHA256SUMS.txt and nothing staged;
  expects exit 1 and an error naming SHA256SUMS.txt.
- fail-path-traversal/ — SHA256SUMS.txt lists "../outside.bin" with the CORRECT
  digest of the real payload shipped at fail-path-traversal/outside.bin;
  expects exit 1 and "path traversal" (entries must be bare basenames; rejected at parse, before any hashing).
- fail-absolute-path/ — SHA256SUMS.txt lists "/tmp/abs.bin" with the CORRECT
  digest of the real payload shipped at fail-absolute-path/abs.bin;
  expects exit 1 and "absolute path" (absolute paths are not allowed).
- fail-subdirectory/ — SHA256SUMS.txt lists "sub/inner.apk" with the CORRECT
  digest of the real file under sub/; expects exit 1 and "separator"
  (no path separators / subdirectories in entry names).
- fail-symlink/ — SHA256SUMS.txt lists "linked.apk" with the CORRECT digest of
  its target; linked.apk is a symlink to outside/evil.apk (target bytes are
  valid, so sha256sum -c would pass); expects exit 1 and "symlink"
  (listed files must be regular, non-symlink files; staged symlinks are also rejected).
- fail-no-apk/ — SHA256SUMS.txt lists only document.txt (valid digest) and no
  *.apk exists anywhere; expects exit 1 and "no *.apk" (at least one APK must
  be listed and staged).
- fail-ghost-listed/ — SHA256SUMS.txt lists ghost.apk with a plausible digest
  of bytes that are not staged; the directory holds nothing but SHA256SUMS.txt;
  expects exit 1 and an error naming "ghost.apk" (listed-but-missing fails closed).
- fail-duplicate-record/ — SHA256SUMS.txt lists the same app.apk record twice
  (identical hash, app.apk present and valid);
  expects exit 1 and "duplicate" (no duplicate basenames).
- fail-directory/ — SHA256SUMS.txt lists "assets" (64-hex) while assets/ is a
  directory containing a file; expects exit 1 and "director" (listed files must
  be regular files; staged directories are rejected too, before sha256sum -c).
- fail-backslash/ — SHA256SUMS.txt lists `back\name.apk` whose name carries a literal backslash (digest-correct POSIX file): the gate rejects any backslash in an entry name outright (GNU coreutils escapes such names in its own output; interpreting it either way is unsafe).
