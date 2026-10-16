# AeonPS2 v1.0.0 — Bench Gate Evidence

**Date:** 2026-10-16 · **Bridge:** `app/src/main/jni/aeonps2_bridge.c`
**Core:** PCEE2 v2.9.81-22-g81526d4dc7 (Linux x86_64
`e9a5003a…f58200`; Android arm64-v8a `880c4daa…80`, shipped as
`libpcee2_libretro_android.so`)
**Harness:** bench build of the bridge (`-DAEONPS2_BENCH`) +
`bench/unit/test_unit.c`

## v1.0.1 changes (fix-and-rebuild, Oct 16)

Two input defects from the v1.0.0 review, fixed before delivery:

### 1. START now reaches the core (was stolen by the app)

**The defect:** `KEYCODE_BUTTON_START` opened the app pause menu, so
the core never received it. On PS2, Start is an in-game button —
Kingdom Hearts' own pause menu IS Start, FFX uses it in menus —
so this broke both test games.

**The fix** (`EmulatorActivity.kt`):
- Quick Start press → sent to the core as joypad id 3
  (`RETRO_DEVICE_ID_JOYPAD_START`); the press is re-emitted on
  key-UP as a clean press held 80 ms (`START_TAP_RELEASE_MS`) so
  the core's per-frame input poll is guaranteed to see it at least
  once.
- Long press (~600 ms, `START_LONG_PRESS_MS`) → opens the app pause
  menu instead. **Nothing is sent on key-down**: key-up decides
  which one it was, so a long-press can never leak a Start press to
  the core. On release after a long-press the release is swallowed —
  the core never saw the press, so there is nothing to release.
- Back still opens the pause menu on a plain press (unchanged):
  the Fire TV remote has no Start button, so this is how
  remote-only users pause. Holding Back repeats key-downs onto the
  same idempotent `openMenu()`.
- BIOS-test abort behavior (any of Start/Back/Menu ends the test)
  is untouched.
- Plain language, twice: the pause menu itself now carries a
  message ("Quick-press START in a game: the game's own button… Hold
  START, or press Back: this menu"), and Settings gained a
  "Buttons" section with the same explanation.

### 2. Right analog stick (shipped in v1.0.1, NOT deferred)

**Why it was safe:** the bridge already stored
`g_analog[port][left/right][x/y]` and the `input_state_cb` already
served `RETRO_DEVICE_ANALOG` index 1 (`RETRO_DEVICE_INDEX_ANALOG_RIGHT`)
to the core — the write side was the only missing piece. Changes:
- New plain-C `aeonps2_set_analog_stick(port, stick, x, y)` (bounds-
  checked, same mutex) used by the existing `nativeSetAnalog`
  (stick 0) and a new `nativeSetAnalogRight` JNI entry (stick 1).
  The pre-existing input path is untouched.
- Kotlin `EmuBridge.nativeSetAnalogRight`; `onGenericMotionEvent`
  probes the device: `AXIS_Z`/`AXIS_RZ` on modern gamepads,
  `AXIS_RX`/`AXIS_RY` fallback on older/generic controllers.
  Scale identical to the left stick (±32767).

## v1.0.1 bench evidence

- **Bridge Linux compile** (`-DAEONPS2_BENCH`): clean, warnings only
  (pre-existing `-Wunused-function` style notes).
- **Host unit tests: 22/22 PASS** (was 15/15) — 7 new input checks:
  left stick X/Y reaches the core, **right stick X/Y reaches the
  core**, left state untouched by right-stick writes, out-of-range
  stick/port writes rejected without crashing, joypad id 3 (START)
  reads back as a live button. `bench/unit/test_unit.c`.
- **Spike load re-run** against the bench-verified Linux PCEE2 .so:
  dlopen/init/no-content boot TRUE, 30 frames, clean deinit,
  exit 0 — the core pull is unchanged and healthy.
- **Kotlin compiled clean** (new strings `pause_start_help`,
  `buttons_section`, `buttons_desc` resolved — the build would fail
  otherwise).

## v1.0.1 APK receipts (independently re-verified after build)

- File: `~/workspace/aeonps2/output/AeonPS2-v1.0.1.apk`,
  **34,730,573 bytes**, sha256
  `a7c7f60a46d8edf55d4939f09eb1b0f2b0667e79b1b29eddba99c8433f7ca10e`
- aapt: `package='com.aeonps2.firetv' versionCode='2'
  versionName='1.0.1'`, leanback-launchable-activity present,
  `native-code: 'arm64-v8a'`, touchscreen not required.
- apksigner verify: OK; signer cert SHA-256
  `46ddbd2e494505df59f588ca2f4551a051106d14fbbc3e318ce99025494fb4b1`
  — **identical to v1.0.0** (same permanent keystore; installs over
  v1.0.0 with no uninstall).
- Manifest xmltree: `EmulatorActivity` carries
  `android:process=":emu"` — unchanged.
- Native libs in APK: `lib/arm64-v8a/libaeonps2_bridge.so` (25,432 B)
  + `lib/arm64-v8a/libpcee2_libretro_android.so` (29,336,400 B,
  sha256 `880c4daa26776945202e84f05ff8b6c1c8399c8e1438dd8d7543c15ed1c0e680`
  — **identical to the bench-verified pull**, core unchanged).
- New symbol present in the shipped bridge:
  `Java_com_aeonps2_firetv_EmuBridge_nativeSetAnalogRight` (T).
- NOT published to GitHub (per instructions); v1.0.0 APK retained
  alongside in `output/`.

## What is NOT proven in v1.0.1 (stated plainly)

- The actual feel of quick-tap vs long-press Start, and right-stick
  response in-game: Kotlin logic is compile-verified only; the
  on-device verdict belongs to Isaiah's Fire TV (the victory lap).
  The [Emulator log] shows every pad button id the bridge serves.
- Real BIOS boot / real game boot: still blocked on a real PS2 BIOS
  file + FFX/KH disc images — unchanged from v1.0.0.

---

## 1. Options audit (PASS)

The bridge answers `SET_CORE_OPTIONS_V2_INTL` (the spike's minimal
harness had declined it) and parses all **39 definitions** — keys,
defaults, value lists, categories. GET_VARIABLE is then served from a
table seeded with the core's own defaults, with mode-profile and
per-game overrides on top. Slim profile values were taken verbatim
from the parsed definition lists (not guessed):
- `pcsx2_upscale_multiplier=1` (1x Native)
- `pcsx2_ee_cycle_rate=-1` (75% underclock)
- `pcsx2_ee_cycle_skip=1` (Mild)
- `pcsx2_blending_accuracy=basic` (core default)
Fat = core defaults untouched.

## 2. No-BIOS / bad-BIOS graceful failure (PASS)

- `boot-nocontent` with empty bios dir: `retro_load_game(NULL)` → TRUE
  (bench proved), 600 frames run, 599 video callbacks at 640×480,
  audio 48000 Hz, **0 drops**, exit 0. The core logs
  `VMManager::Initialize() failed.` through the GET_LOG_INTERFACE
  callback into the ring log — no crash, no hang. The app surfaces
  this line on the BIOS screen; a boot is never attempted without a
  BIOS present.
- Same run with a 4,194,304-byte **random garbage file as the BIOS**:
  identical graceful path (`VMManager::Initialize() failed.`,
  exit 0). Content sanity warnings in the app are belt-and-braces;
  the core itself declines cleanly.
- Because the core "succeeds" load even when it rejects the BIOS
  (black frames), the app's [Test BIOS] loop scans the ring log for
  `VMManager::Initialize() failed` before reporting a verdict —
  a rejected BIOS now reports "rejected", never a false OK.
  (Caught in review, fixed before shipping v1.0.0.)

## 3. Corrupt-image set (PASS)

5 garbage inputs (10 MB random, 4 KB, 1 MB, empty, text file) each in
its own process under `timeout 90`: **all exit 0** — zero segfaults
(139), zero aborts (134), zero hangs. The bridge's load path never
crashes on undecodable media.

## 4. Frame pipeline (PASS, with stated limit)

600 `retro_run()` calls → 599 video callbacks → heap frame buffer →
dumped `bench_frame.ppm` (640×480, 100% black). The video pipeline
works end to end through the actual bridge; frames are black **because
no real PS2 BIOS exists in the sandbox** — the core has nothing to
render. Real BIOS-shell frames await Isaiah's BIOS via [Test BIOS].

## 5. Host unit tests (15/15 PASS)

`bench/unit/test_unit.c` compiles the bridge's pure logic and checks:
serial normalization (6 cases incl. `SLUS_203.12;1` → `SLUS-20312`,
cdrom path prefix, lowercase, empty, null), option stack
(defaults → slim → per-game precedence → fat restore), per-game
table dedup. **15 checks, 0 failures.**

## 6. Per-game table

Registered: SLUS-20312 (Final Fantasy X), SLUS-20370 (Kingdom
Hearts). **Zero overrides seeded** — the core's own GameIndex owns
both titles' fixes (bench-verified compat 5 entries in the shipped
GameIndex.yaml). The override mechanism is bench-proven through the
synthetic serial path in the unit tests.

## 7. Vulkan refusal / readback path (evidence)

`SET_HW_RENDER(Vulkan 1.1)` is **refused** by the bridge; the core
logs its documented fallback and pushes frames through
`retro_video_refresh` at XRGB8888. This matches the Linux spike's
observed behavior. On-device Vulkan zero-copy is NOT implemented in
v1.0 — a deliberate, documented choice.

## APK receipts (independently re-verified after build)

- File: `~/workspace/aeonps2/output/AeonPS2-v1.0.0.apk`,
  **34,728,941 bytes**, sha256
  `71faf239039350f49841a9322b995cc47884993d7b127ab4d8df3764ad8cdc14`
- aapt: `package='com.aeonps2.firetv' versionCode='1'
  versionName='1.0.0'`, leanback-launchable-activity present,
  `native-code: 'arm64-v8a'`, `uses-feature: android.software.leanback`,
  touchscreen not required.
- apksigner verify: OK; signer cert SHA-256 matches the new keystore.
- Manifest xmltree: `EmulatorActivity` carries
  `android:process=":emu"` — the brick-loop is structurally impossible.
- Native libs in APK: `lib/arm64-v8a/libaeonps2_bridge.so` (25,192 B)
  + `lib/arm64-v8a/libpcee2_libretro_android.so` (29,336,400 B,
  sha256-identical to the bench-verified pull `880c4daa…80`).
- Core DT_NEEDED: libOpenSLES, libdl, libandroid, liblog,
  libGLESv1_CM, libGLESv2, libz, libm, libc — all present on Fire OS;
  no hard libvulkan dependency (lazily dlopened only if ever needed).
- Assets: 113 files under `assets/pcsx2resources`, byte-identical to
  the bench resources snapshot.
- Kotlin compiled clean (one real fix during build: GamesFragment
  lambda already receives the Entry, dropped a bad `.entry`
  dereference).

## Signing

New permanent keystore: `~/workspace/aeonps2/signing/aeonps2.keystore`
(alias `aeonps2`, RSA-4096, outside the source tree; `keystore.properties`
chmod 600). Cert SHA-256:
`46ddbd2e494505df59f588ca2f4551a051106d14fbbc3e318ce99025494fb4b1`
(SHA-1 `de12f203af0e0ec46100d663de2c005105db8956`).

## What is NOT proven (stated plainly)

- Real BIOS boot with video: blocked on a real PS2 BIOS file.
- Real game boot (FFX / KH): blocked on BIOS + disc images.
- On-device performance on Isaiah's Fire TV: the TV is the victory
  lap, by design. [Test BIOS] in the app is the on-device proof path.
- DEV9 virtual HDD: compiled into the core, INI hook exists; attach
  unproven. Labelled "coming" in-app, never claimed working.
- Slim profile values beyond the 4 listed: unverified against the
  core's behavior without a BIOS; Slim is labelled EXPERIMENTAL in-app.
