# Aegis

A hard fork of [VirtualXposed](https://github.com/android-hacker/VirtualXposed)
(weishu / android-hacker) — an Android app-containment engine: run apps
*inside* an app, with virtualized contacts, location, storage and more.

## What this fork is doing

**Goal:** bring the engine up to **Android 13–16** and build **Aegis** on top —
a safe space to run apps you don't fully trust, with:

- virtual identity (empty/fake contacts, location, device identifiers)
- per-guest network kill switch
- APK risk scanner before you install into the sandbox

**Current status: M0 complete — engine builds green on modern toolchain.**
`AGP 7.4.2 / Gradle 7.6.4 / JDK 17` replaces the 2018-era toolchain
(AGP 3.2.1 / Gradle 4.6). Dead jcenter replaced by the Aliyun jcenter
archive. NDK 23 native build fixed (`APP_PLATFORM android-21`). Launcher3
vendored into the repo. CI builds `aosp` + `fdroid` debug APKs (~8 MB) on
every push — see the Actions artifacts.

## Status: M1 in progress (1.0.0-m1)

**M1 shipped so far:**

- *Android 13–15 engine compat (the core port work)*
  - Hidden-API bypass rebuilt on LSPosed **HiddenApiBypass 6.2** (vendored,
    Apache-2.0) — the old free_reflection meta-reflection trick is dead on
    Android 12+; this is the bypass path that works P→15+. Runs first in
    `VirtualCore.startup()` in **every** process (host, server, guests).
  - Numeric hardening across all service proxies: Android 13 migrated
    several binder interfaces from `int` to `long` flags; raw `(int) args[n]`
    casts now go through `MethodProxy.asInt/asLong` instead.
  - `BuildCompat` gates for T/U/V (13/14/15) + W (16).
  - Delegate-last classloader setup survives 13–15 builds missing
    `android.test.base.jar`.
  - Host `targetSdk` 23 → 24 (Android 15 refuses to install targetSdk < 24).
- *Aegis OS shell (the "hidden OS" feel)*
  - Persistent **settings gear** on the Launcher3 home + overview gear +
    MENU key → **Aegis OS settings hub** (dark, in-container).
  - The hub: guest app manager, **import from installed apps**,
    **import from APK file** (SAF — installs into the container only,
    never on the device), Secure Vault, Secure Camera, engine diagnostics.
  - Home label → "Aegis"; upstream donation dialog removed.
- *Install pipeline rebuilt for Android 13–15 (fixes "Unable to parse the
  package")*
  - Root cause: the legacy install path reflected into
    `android.content.pm.PackageParser`, which **AOSP deleted in Android 14**
    — on 13–15 every install died at parse. New `ModernPackageParser` builds
    the engine's `VPackage` from the **public**
    `PackageManager.getPackageArchiveInfo()` plus a self-contained manifest
    walk (intent filters, `activity-alias` expansion with relative-name
    normalization, split-APK directories). No framework `PackageParser`
    anywhere; identical behavior on 13/14/15, legacy reflection kept only
    as a below-API-33 fallback.
  - Native-lib extraction gained a framework-free zip fallback (ABI chosen
    by `Build.SUPPORTED_ABIS` preference order) in case the hidden
    `NativeLibraryHelper` is blocked on a future release.
- *Full rebrand: Aegis*
  - Package name **`app.orubus.aegis`** (was `io.va.exposed64`), version
    unified to `1.0.0-m1`.
  - Every visible "VirtualXposed" string replaced across all 10 locale
    files; About page rebuilt (GitHub repo + issue tracker links, upstream
    credits dialog, old Telegram/QQ/website elements removed).
- *Aegis Vault (at-rest crypto)*
  - `AEGV1` format: AndroidKeyStore AES-256 master key (StrongBox when
    available), fresh 256-bit key per file wrapped by the master key,
    1 MiB chunked AES-256-GCM frames, chunk counter IVs, chunk index +
    file id bound as AAD (tamper/reorder/truncate detection), zero-shred
    on delete, decrypt-on-view into a private cache that is shredded on
    exit.

**Still honest:** container *compiles*, *installs* and now *parses guest
APKs with a 13–15-proof pipeline*, but **a full guest run has not yet been
verified on a real Android 13–15 device** — that remains the M1 exit gate.
Installing an app end-to-end (parse → copy → launch) on your device is the
first thing to test with the new APK; engine diagnostics in Aegis OS
settings show live whether the hidden-API bypass activated.

**M1 backlog (next grinds):** real-device guest launch on 13/14/15,
per-SDK `bindApplication`/mirror variance checks, AppOps op-code remap
audit, notification channels for guests targeting ≥ 26, guest-media
auto-vault hook, Android 16 (W) pass.

## Build

```bash
git clone --recursive https://github.com/orubus-dev/aegis.git
cd aegis/VirtualApp
./gradlew :app:assembleAospDebug
# APK: app/build/outputs/apk/aosp/debug/
```

## License & attribution

GPL-3.0, inherited from VirtualXposed — see `LICENSE.txt`. The full upstream
git history is preserved in this fork; the original author's copyright is
retained. `VirtualApp/launcher` is a further fork of AOSP Launcher3
(Apache-2.0).
