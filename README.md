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

**Current status: M0 — build modernization.**
`AGP 7.4.2 / Gradle 7.6.4 / JDK 17` replaces the 2018-era toolchain
(AGP 3.2.1 / Gradle 4.6), `jcenter` fallback kept only for legacy engine
artifacts, NDK 23 native build fixed (`APP_PLATFORM android-21`).
CI builds debug APKs on every push.

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
