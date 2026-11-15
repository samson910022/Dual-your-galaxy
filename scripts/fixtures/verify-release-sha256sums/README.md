# verify-release-sha256sums fixtures

Synthetic publish-directory copies for `scripts/verify-release-sha256sums.test.sh`.
Each directory is copied to a fresh mktemp dir by the self-test (the fixtures
themselves are never mutated). pass-* must exit 0, fail-* must exit 1.

- pass-listed-ok/ — two payloads with a SHA256SUMS.txt listing exactly them;
  expects exit 0 and "PASS: release-publish holds exactly the SHA256SUMS-listed files + SHA256SUMS.txt".
- fail-extra-file/ — listed app.apk plus an unlisted extra-unlisted.bin;
  expects exit 1 and "unlisted file(s) staged for release".
- fail-missing-file/ — SHA256SUMS.txt lists gone.apk, which is absent;
  expects exit 1 and sha256sum's FAILED marker.
- fail-forged-sums/ — app.apk present but its recorded digest is 64 zeros;
  expects exit 1 and sha256sum's FAILED marker.
- fail-empty/ — nothing staged at all (no SHA256SUMS.txt);
  expects exit 1 and an error naming SHA256SUMS.txt.
