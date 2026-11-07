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

## Status: M1 complete, M2–M3 complete (v1.0.0)

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

## Status: M3 complete — v1.0.0 signed release

**M2 — install reliability (first field round fixed):**
- **Home grid shows installed guests again.** `ModernPackageParser` stored
  intent filters under raw manifest class names (".MainActivity") and looked
  them up under the platform-expanded ones ("com.example.MainActivity") —
  every app failed the launchable check and the launcher stayed empty.
  Names are now normalized at insert; the same fix repairs clone-list and
  home-grid resolution.
- **"Install failed: null result" + the `isSuccess` NPE killed.**  Two
  stacked causes: (1) modern AGP AIDL codegen gives every Stub a `Default`
  implementation whose methods return `null`, and the generated proxy uses
  it whenever `transact()` returns false — exactly what happens when the
  `:x` engine process is momentarily frozen (Android 12+ cached-app
  freezer) or raced at startup; (2) callers read `result.isSuccess`
  unguarded.  `AidlGuard` now replaces every silent Default with a throwing
  one, `installPackage` retries once (engine wakes up), results are never
  null, and failures surface the real cause.
- Server-side parse failures now carry the actual exception into the
  install error text.
- **Diagnostics screen** (Aegis OS settings → Diagnostics): on-device log of
  every install attempt + engine error, copy-to-clipboard for bug reports.

**M3 — identity + signed releases:**
- Package `app.orubus.aegis`, launcher icon replaced (Aegis shield), all
  locale strings Aegis-branded, About page with GitHub repo/issues links.
- Release pipeline: RSA-4096 keystore in GitHub Actions secrets, signed
  `aosp` + `fdroid` release APKs on every `v*` tag, GitHub Release with
  What's New extracted from `RELEASE_NOTES.md`.

**M4 started — guest media auto-vault:**
- When enabled (Aegis OS settings, on by default), media that guests write
  into the container's virtual storage is pulled into the encrypted vault
  (AEGV1) next time the vault opens, and the plaintext original is shredded.

**Still honest:** all of the above compiles locally (JDK 17 / AGP 7.4.2) and
the signed release path is verified locally end-to-end; the *behavioral*
fixes (grid, install, auto-vault) still need a real-device pass — that is
exactly what the v1.0.0 test round is for.

**M4–M6 queue:** deep media hooks (capture-time interception instead of
scan-on-open), guest notification routing into Aegis-owned channels,
AppOps op-code remap audit, per-SDK `bindApplication`/mirror variance
checks, Android 16 (W) pass.

## Status: v1.1.0 — install self-healing + simulated permissions + identity apps

**Second field round fixed (v1.0.0 device logs → this build):**
- **"Installs but never shows in the drawer" + "Can not update the package
  (such as version downrange)" — both one bug family, fixed at the root.**
  Caches written by older builds stored activities with EMPTY intent-filter
  lists (the pre-normalization parser bug), so such a package was invisible
  in the launcher (nothing launchable) while still blocking every reinstall.
  The engine now *repairs at load*: on boot it re-walks each package's
  manifest and re-attaches the missing filters; entries whose manifest
  declares a launcher that cannot be attached are purged automatically so
  the next clone is a clean install.
- Clone and APK-import now pass `UPDATE_IF_EXIST`: re-cloning or re-importing
  an installed package reinstalls/updates it in place instead of failing.
  Downgrade installs are allowed inside the sandbox (explicit user action).
- Install-failure results carry the package name again (diagnostics logged
  `pkg=null`) and state the installed vs new versionCode.
- `isPackageLaunchable` no longer crashes the drawer when a package has no
  user state (empty `getInstalledUsers()`).

**M2 — simulated permissions (OS-in-OS):**
- Full simulated permission system: every guest app has its own grant state
  inside the sandbox, independent of the phone. `checkPermission` from any
  guest resolves through the Aegis grant store (JSON, engine-private):
  requested → granted by default, never-requested → denied (real Android
  behavior), explicit overrides from the UI always win.
- **Permissions tab** (Aegis OS → Permissions): master simulation switch +
  one sheet per guest app listing every requested permission with group,
  sensitive flag and Granted/Denied state; tap to flip, long-press to reset.
- Off-switch restores the legacy behavior (guests mirror the host's grants).

**M2b — simulated identity:**
- On first use Aegis generates a device profile (name, +234-flavored phone,
  email, model, Android ID, Luhn-valid IMEI), 24 contacts and 7 message
  threads — all stored in the host's private storage, never synced.

**M3 — secure camera + metadata scrubbing:**
- **In-app camera** (camera1 preview, no external camera app involved):
  capture → strip all identifying EXIF (GPS block, make/model/serials,
  timestamps, comments, thumbnails refs) → encrypt into the vault → shred
  the plaintext temp. Nothing reaches the phone's gallery.
- System-camera fallback kept for photo/video; **vault auto-import now also
  scrubs JPEG metadata** before sealing guest media into the vault.

**M4 — built-in Aegis apps:**
- **Contacts** (generated, fully editable), **Messages** (threads +
  composer, on-device only — nothing is transmitted), **Phone** (dialpad +
  sandbox call log, simulated calls only — nothing is dialed). All honest
  about being local-only, right in their subtitles.

**Still honest:** this compiles and the signed-release path is verified by
CI; the behavioral fixes above are *not yet verified on a real device* —
that is what this test round is for.

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
