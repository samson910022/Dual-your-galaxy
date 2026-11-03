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
- GitHub Actions uses `SIGN_STORE_BASE64`, `SIGN_STORE_PASSWORD`, `SIGN_KEY_ALIAS`, `SIGN_KEY_PASSWORD` secrets.
- Local sign: `apksigner sign --ks ~/.android-keys/Galaxy-Dual-app-Plus-release.jks --out app-release-signed.apk app-release-unsigned.apk`
- Verify: `apksigner verify --print-certs app-release-signed.apk`

Related: `.gitignore` blocks `*.jks/*.keystore/*.p12/*key*.properties`.
