# Key Management (no secrets in repo)

This project signs release APKs with a dedicated RSA-4096 key.
Private key is NEVER committed.

- Keystore location (local only): `~/.android-keys/Galaxy-Dual-app-Plus-release.jks`
  - Alias: `galaxy-dual-plus`, Type: PKCS12, Validity: 2026-2051
  - SHA256 (public): `60:8E:67:4F:CC:D2:35:C1:D7:AB:93:BE:E8:7F:A1:C8:FB:76:1D:EB:7C:3A:8D:00:60:A0:7D:48:89:86:61:E3`
  - Permissions: dir `700`, files `600`
- Password file (local only): `~/.android-keys/Galaxy-Dual-app-Plus-release.properties`
  - Contains `storeFile/storePassword/keyAlias/keyPassword`, `600`
  - Do NOT copy into repo, CI logs, or screenshots.

Backup:
- Copy `~/.android-keys/Galaxy-Dual-app-Plus-release.*` to encrypted offline storage.
- Loss = cannot update Play/GitHub releases with same signature.

CI / Release:
- GitHub Actions secrets (all required):

  | Secret | Purpose |
  | --- | --- |
  | `SIGN_STORE_BASE64` | Base64 of the keystore file itself |
  | `SIGN_STORE_PASSWORD` | Keystore password (`apksigner --ks-pass`) |
  | `SIGN_KEY_ALIAS` | Key alias (`apksigner --ks-key-alias`) |
  | `SIGN_KEY_PASSWORD` | Key password (`apksigner --key-pass`) |

- Fail-closed by design: if any secret is missing on a tag build, the
  signing step fails the job and nothing is published. There is no
  `unsigned` fallback asset — do not add one.
- STATUS (as of 2026-11-04): 4 SIGN_* secrets (`SIGN_STORE_BASE64` /
  `SIGN_STORE_PASSWORD` / `SIGN_KEY_ALIAS` / `SIGN_KEY_PASSWORD`) present
  and valid for that run; tag v0.0.0-dryrun
  [run 41800791298](https://github.com/samson910022/Galaxy-Dual-app-Plus/actions/runs/41800791298)
  passed end-to-end: keystore restore + `./gradlew assembleRelease` +
  `apksigner verify` + single `SHA256SUMS.txt` (SHA-256, `sha256sum -c`
  verified); same-run attempts 1-2 red as observed (base64 invalid /
  password incorrect), attempt 3 green; fail-closed by design; CI green
  covers build+verify, manual download verified + cert SHA-256 matches the
  canonical fingerprint at the top of this file (see
  [#5 close comment](https://github.com/samson910022/Galaxy-Dual-app-Plus/issues/5#issuecomment-6626662388)).
  Push tags `v*` trigger the same workflow; re-verify on first real `v*`
  or after rotation/workflow change. Green non-tag CI does not prove the
  tag path (dry-run tag/Release cleaned per #5).
- Local sign (complete — pass alias and both passwords explicitly):

  ```bash
  apksigner sign \
    --ks ~/.android-keys/Galaxy-Dual-app-Plus-release.jks \
    --ks-key-alias galaxy-dual-plus \
    --ks-pass:env SIGN_STORE_PASSWORD \
    --key-pass:env SIGN_KEY_PASSWORD \
    --out app-release-signed.apk app-release-unsigned.apk
  ```

  `--ks-pass:pass` / `--key-pass:pass` put the secret in shell history —
  prefer `:env` / `:file` forms. The Gradle-style
  `~/.android-keys/Galaxy-Dual-app-Plus-release.properties`
  (`storeFile/storePassword/keyAlias/keyPassword`) is not read by
  `apksigner` directly; export its values as env vars first.
- Signature versions: manual `apksigner sign` schemes depend on the APK
  min/max SDK and options (`--v1-signing-enabled` / `--v2-signing-enabled`
  defaults follow the official apksigner documentation, not always both on).
  To explicitly require v1 (JAR signing) + v2 (APK Signature Scheme v2)
  for widest sideload compatibility, pass
  `--v1-signing-enabled true --v2-signing-enabled true`. CI-built APK
  differs: run 41800791298 attempt 3 `apksigner verify --verbose`
  shows v1=false / v2=true as observed in that run (minSdk 29 per
  gradle/libs.versions.toml; v1/v2 values as measured; cause not yet investigated).
- `.jks` vs PKCS12: the file keeps the historical `.jks` extension but
  its actual format is PKCS12 (`keytool -list` reports `Type: PKCS12`).
  Java/`apksigner` detect format by content, so the extension does not
  matter — do not rename it casually, paths reference this name.

Verify (two different things — do not confuse):
- Certificate fingerprint = signing-key identity, stable across builds
  signed by the same key. Canonical value is the `SHA256 (public)` line
  at the top of this file. Check with:

  ```bash
  apksigner verify --print-certs app-release-signed.apk
  # compare the SHA-256 digest of certificate with the canonical value
  ```

- APK file checksum = integrity of one specific file, changes every
  build. Two cases — do not mix the filenames:
  - Locally signed file (local-only): `app-release-signed.apk` plus
    its sidecar `app-release-signed.apk.sha256` in the same folder.
    Generate the sidecar first (the sign step above only outputs
    the `.apk`), then check with:

    ```bash
    sha256sum app-release-signed.apk > app-release-signed.apk.sha256
    sha256sum -c app-release-signed.apk.sha256
    ```

    A locally generated checksum is only for later integrity
    comparison, not an independent trusted-source verification;
    for official downloads, the Release `SHA256SUMS.txt` and
    certificate comparison remain authoritative.

  - GitHub Release download: `app-release.apk` plus `SHA256SUMS.txt`
    in the same folder. Check with:

    ```bash
    sha256sum -c SHA256SUMS.txt
    ```

    Optionally confirm the signing key on the downloaded APK:

    ```bash
    apksigner verify --print-certs app-release.apk
    # compare the SHA-256 digest of certificate with the canonical value
    ```

  A fingerprint mismatch means a different key; a checksum mismatch
  means a corrupted/modified file. Transcribe fingerprints exactly —
  one hex digit off is a different value.

Key rotation / leak response:
- Rotation = new identity: existing installs will NOT update silently
  (signature mismatch); users must uninstall/reinstall. Update this doc
  fingerprint, CI secrets, and announce the new fingerprint out-of-band.
- Suspected leak: stop signing with the key, rotate
  `SIGN_STORE_*`/`SIGN_KEY_*` secrets immediately, audit which releases
  were signed with the exposed key, and record the incident (date,
  scope, new fingerprint) here or in the release notes.

Related: `.gitignore` blocks `*.jks`/`*.keystore`/`*.p12`/`*.key.properties` (+ `keystore.properties`/`release-key.properties`).
