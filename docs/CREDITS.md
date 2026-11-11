# Credits & References

> License: MIT — Copyright (c) 2026 samson910022. See [../LICENSE](../LICENSE).
> This project contains **only original code** under MIT license.
> The projects below are **idea-level references only**, except for the
> Apache-2.0 AARs listed under "Binary dependencies", which are linked into the
> APK. No source code was copied from GPL-licensed projects.

## References (idea-level only)

- `trunone/dual-manager` — Unlicense — Dual-app manager concept reference.
- `Creator54/dual-apps` — MIT — General dual-apps UI flow reference.
- `Arora-Sir/DualClone` — GPL-3.0 — Idea only, no code copied.
- `AhmetCanArslan/CloneCat` — GPL-3.0 — Idea only, no code copied.
- `nathanatgit/AppDualZuku` — GPL-3.0 — Idea only, no code copied.
- `libxposed/example` — Apache-2.0 — Modern Xposed API (API 102) usage pattern.
- `JingMatrix/Vector` — GPL-3.0 — General LSPosed module structure idea only.
- `RikkaApps/Shizuku` — Apache-2.0 — Shizuku shell fallback integration pattern
  (also linked as a binary dependency; see below).
- `salvogiangri/KnoxPatch` — GPL-3.0 — Mentioned only to explicitly exclude; no Knox bypass is implemented.

## Binary dependencies (Apache-2.0)

- `libxposed/service` — Apache-2.0 — LSPosed remote preferences service AAR
  (`XposedServiceHelper`/`RemotePreferences`); linked into the app APK. Its
  exported `XposedProvider` is replaced by the app-owned `GuardXposedProvider`.
- `libxposed/interface` — Apache-2.0 — Transitive Xposed service interface AAR;
  linked into the app APK.
- `RikkaApps/Shizuku` — Apache-2.0 — Shizuku API/provider AARs linked into the
  app APK for the shell fallback channel.

## License Statement (MIT)

- All files in `app/`, `core/`, `hook/` are newly written for this project under MIT.
- MIT License text: see [../LICENSE](../LICENSE), Copyright (c) 2026 samson910022.
- Third-party ideas were reimplemented from scratch and do not trigger license contagion.
- If any reference updates its license, this file will be revised accordingly.

## Policy

- Secure Folder (Knox container) is out of scope and never accessed.
- Knox bypass techniques are explicitly out of scope.
