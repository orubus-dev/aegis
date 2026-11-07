# Release notes

Each `## <tag>` section below becomes the "What's new" text of the matching
GitHub Release. Keep the newest section at the top.

## v1.0.0

**Aegis 1.0.0 — the first signed release.** Everything in this build came
straight out of your field reports from the last one. Thank you for testing.

### Fixed
- **Apps now show up in the Aegis home grid.** The new package parser stored
  intent filters under raw manifest names (".MainActivity") but looked them
  up under the expanded names ("com.example.MainActivity"), so every app
  failed the launchable check and the launcher stayed empty. Both the home
  grid and the "Add to Aegis" clone list resolve correctly now.
- **No more "Install failed: null result" and no more raw
  "isSuccess on a null object reference" crashes.** Install results are
  null-safe end to end; if an install fails you now get the REAL reason
  (parser error, storage error, version conflict...) instead of a mystery.
- Parser errors surface the actual exception in the error dialog.

### Added
- **Diagnostics screen** — Aegis OS settings → Diagnostics (install log).
  Every install attempt and engine error is logged on-device; tap Copy log
  and paste it into a bug report. No adb needed.
- **Signed releases** — this APK is signed with the Aegis release key and
  installed updates will no longer complain about signature mismatches.
- App identity is now `app.orubus.aegis` (this is the first release under
  the final package name; previous test builds were a different identity).

### Notes for testers
- Please re-test: clone apps, import an APK, and check the home grid.
- If anything fails, open Aegis OS → Diagnostics → Copy log and send it —
  that turns a "it didn't work" into a one-line fix.
- Known from your last report, still on the M-plan: per-app media vault
  auto-hook, guest notification routing, Android 16 pass.
