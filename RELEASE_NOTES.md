# Release notes

Each `## <tag>` section below becomes the "What's new" text of the matching
GitHub Release. Keep the newest section at the top.

## v1.1.0

**Aegis 1.1.0 — the install bug is dead, and the OS-in-OS gets a real
permission system, camera and identity.** Built straight from your v1.0.0
device logs: your log showed `success=false error=Can not update the package
(such as version downrange)` — to answer the question you asked: no, the app
did **not** actually install. An earlier attempt left a half-registered
record behind, that record was invisible in the drawer (it had no launcher
filters) *and* it blocked every new install attempt. This build fixes the
root cause and heals existing damage on first launch.

### Fixed — install & drawer
- **Self-healing engine.** On startup Aegis now repairs package records
  written by older builds (re-attaches the missing launcher filters) — your
  previously "stuck" app should simply appear in the drawer after updating.
  If a record is beyond repair it is purged automatically, so a fresh clone
  just works.
- **Re-clone / re-import reinstalls instead of failing.** Cloning or
  importing an app that is already inside Aegis now updates it in place —
  no more "version downrange" wall. Install failures also carry the real
  package name and version numbers in the error + diagnostics.
- Fixed a drawer crash path when a package record has no user state.

### New — simulated permission system (Aegis OS → Permissions)
- Every app inside Aegis now gets **its own Android permission state**,
  independent of your phone: what the app requested is granted by default,
  what it never requested reads as denied — exactly like a real OS — and
  you can flip anything: tap a permission to Grant/Deny, long-press to
  reset. A master switch turns the whole simulation off (guests then
  inherit your phone's grants, the old behavior).

### New — identity & built-in apps (Aegis OS)
- **Contacts, Messages and Phone now live inside Aegis.** On first open
  Aegis generates a device profile, an address book and message threads.
  Everything is editable and stored on-device only: messages are never
  transmitted, calls never dialed — each app says so plainly.
- **Aegis Camera** — a real in-app camera. Every photo is stripped of GPS
  coordinates and device metadata *before* it is encrypted into the vault;
  the plaintext is shredded. Nothing ever touches your phone's gallery.
  (System-camera fallback still available for photo/video.)
- Vault auto-import now scrubs JPEG metadata on guest media too, before
  sealing it into the encrypted vault.

### Honest footnotes
- Compiles green in CI and is signed with the Aegis release key; the
  behavior above still needs your device to confirm — if anything misbehaves,
  Aegis OS → Diagnostics → Copy log and send it over.
- Note: guest virtual storage on shared storage moved from the old
  `VirtualXposed/` folder to `Aegis/` (branding cleanup). Vault contents
  are unaffected (they live in Aegis's private storage).

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
