# EdgeTX on Android

Runs the **real EdgeTX firmware UI** on Android. Nothing about the interface is
re-implemented: EdgeTX's colour-LCD code — together with its LVGL, fonts, themes
and Lua runtime — is cross-compiled for Android into a shared library, and the
APK is a thin `NativeActivity` host around it. Every pixel and every menu comes
from EdgeTX itself.

```
┌─────────────────────── app (android.app.NativeActivity) ───────────────────────┐
│                                                                                │
│  android_main()            Android lifecycle + input                           │
│      │                                                                         │
│      ├── native_main.cpp   ANativeWindow, frame blit, touch & joystick         │
│      ├── joystick.cpp      Android InputDevice axes ──┐                        │
│      ├── simu_host.cpp     firmware lifecycle ────────┤                        │
│      └── sdcard manifest   bundled SD-card seeding    │                        │
│                                                       │                        │
│  ┌────────────────────────────────────────────────────▼─────────────────────┐  │
│  │ libedgetx-st16mk3-simulator.so   (the actual EdgeTX firmware)            │  │
│  │                                                                          │  │
│  │  targets/simu/          platform-agnostic simu core (simulib.h API)      │  │
│  │  gui/colorlcd/          EdgeTX colour-LCD UI + themes + widgets          │  │
│  │  thirdparty/lvgl        LVGL 8.3 fork used by EdgeTX                     │  │
│  │  Lua 5.4                the radio's script runtime                       │  │
│  │  targets/simu/android_host.cpp   host imports + analog injection         │  │
│  └──────────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────────┘
```

The firmware/renderer split is EdgeTX's own: `radio/src/targets/simu/` is the
platform-agnostic core already used by the WASI/web build, and `simulib.h`
documents the exact host boundary. The SDL+ImGui desktop simulator is *not*
involved — this build links only the core UI.

## Requirements

| Tool                  | Verified version / path                          |
|-----------------------|--------------------------------------------------|
| Android SDK           | platform android-36, build-tools 36.1            |
| Android NDK           | 28.2.13676358 (r28)                              |
| JDK                   | Android Studio JBR (Java 25)                     |
| Gradle                | 9.5.0 (wrapper included)                         |
| Android Gradle Plugin | 9.3.2                                            |
| CMake                 | 3.22.1 (from the SDK)                            |
| Python                | 3.11+ with `pydantic`, `clang`, `libclang`       |

The Python interpreter is used by EdgeTX's build-time code generators. Point at
a prepared virtualenv with `-Pedgetx.python=...` (see *Configuration* below).

## Building

```powershell
# JDK 17+ is required; a fresh shell usually has no JAVA_HOME.
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

.\gradlew.bat :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am start -n com.edgetx.droidui/android.app.NativeActivity
```

### How the EdgeTX library gets built

`app/build.gradle.kts` registers the task **`buildEdgeTxSimulator`** (group
`edgetx`), which `preBuild` depends on. For every ABI it configures and builds
the EdgeTX CMake project, strips the result and stages it at
`app/build/edgetx-libs/<abi>/libedgetx-st16mk3-simulator.so`, which is wired in
as a `jniLibs` source directory.

### Configuration

| Gradle property      | Default                       | Meaning                                   |
|----------------------|-------------------------------|-------------------------------------------|
| `edgetx.dir`         | `E:/develop/dev/edgetx_esp32` | EdgeTX checkout to build                  |
| `edgetx.pcb`         | `TX16SMK3`                    | EdgeTX `PCB` (the 800×480 target)         |
| `edgetx.simuName`    | `st16mk3`                     | Library name suffix                       |
| `edgetx.abis`        | `armeabi-v7a,arm64-v8a,x86_64`| ABIs to build                             |
| `edgetx.python`      | `<edgetx.dir>/.venv/...`      | Python interpreter for the code generators|

In PowerShell, **quote** Gradle properties, otherwise the value is parsed as a
task name: `.\gradlew.bat :app:assembleRelease "-Pedgetx.abis=x86_64"`.

## Required patches to the EdgeTX tree

The port needs five changes in the EdgeTX checkout. The last two fix genuine
upstream bugs that affect **every** non-ESP target, not just Android.

| # | File | Change |
|---|------|--------|
| 1 | `radio/src/targets/simu/CMakeLists.txt` | Add an `elseif(ANDROID)` branch that builds the simu core as a `SHARED` library instead of the SDL/ImGui executable. |
| 2 | `radio/src/targets/simu/android_host.cpp` | **New file.** Implements the host imports (`simuGetAnalog`, `simuTrace`, `simuLcdNotify`, `simuQueueAudio`). Android's linker rejects a shared library with unresolved symbols, so they have to live inside the `.so`. Also exports the analog-injection hook used by the app. |
| 3 | `cmake/Macros.cmake` | `GenerateDatacopy()` runs libclang over the sources; pass `--sysroot`, `-resource-dir` and `-target` from the NDK, otherwise the generator fails with `inttypes.h file not found`. Do **not** add `-isystem usr/include` — it breaks libc++'s `include_next` ordering. |
| 4 | `radio/src/gui/colorlcd/bitmaps.cpp` | `ICON_RADIO_WIFI` is unconditional in the `EdgeTxIcon` enum but `#if defined(ESP_PLATFORM)`-guarded in the `_builtinIcons[]` table, so on any non-ESP build the table is one entry short and the trailing icons (including `ICON_TOP_LOGO`) read out of bounds → NULL dereference in `_decompressed_mask()` when the QuickMenu opens. Keep the slot with an `#else` placeholder. |
| 5 | `radio/src/gui/colorlcd/lcd.cpp` | `char LVGL_MEM_BUFFER[...] __SDRAM __ALIGNED(16)` — `__ALIGNED(x)` expands to **nothing** in simulator builds (`definitions.h`), so the pool is only 1-byte aligned. LVGL's TLSF rejects a mis-aligned pool (`lv_tlsf_create()` returns NULL) and the first `lv_mem_alloc()` then crashes. Use an explicit `__attribute__((aligned(16)))`. |

Patch 5 only *appeared* to work on x86_64 because the linker happened to place
the array on an aligned address; on arm64 it landed on an odd one.

## Input

**Touch** is the primary input: touches are mapped from window coordinates into
LCD coordinates and forwarded to the firmware as `simuTouchDown/Up`.

**Hardware joysticks** (gamepads, or RC transmitters that enumerate as a HID
joystick) feed the analog channels. `joystick.cpp` enumerates the system
`InputDevice`s, keeps the axes each one declares, and pushes values into the
firmware; the app then stops generating its demo sine wave. Until a joystick is
seen, the stick channels keep that sine wave so the UI is not dead on an
emulator.

Axis mapping — EdgeTX simulator channel order, from
`radio/src/targets/simu/adc_driver.cpp` and the SDL reference implementation in
`sdl_simu.cpp`:

| analog | EdgeTX channel | Android axis (first one the device declares) |
|--------|----------------|----------------------------------------------|
| 0 | left stick X — rudder | `AXIS_X`, `AXIS_RUDDER` |
| 1 | left stick Y — throttle *(inverted)* | `AXIS_Y`, `AXIS_THROTTLE` |
| 2 | right stick Y — elevator *(inverted)* | `AXIS_RY` |
| 3 | right stick X — aileron | `AXIS_RX` |
| 4.. | sliders / pots | `AXIS_Z`/`AXIS_RZ`/`AXIS_BRAKE`/`AXIS_GAS` |

Values are normalised with the device's own `MotionRange` min/max, so both
centred sticks (`-1..1`) and triggers (`0..1`) convert correctly. At startup the
app logs every input device:

```
input device 4: "fts" sources=0x00001002  axes=[X,Y,...]
```

If a remote controller's sticks do not show up there with a `JOYSTICK` marker,
Android never sees them — a vendor SDK would be needed instead.

## Simulated SD card

`app/src/main/assets/sdcard/` holds a full EdgeTX SD-card tree (RADIO, MODELS,
THEMES, SOUNDS, WIDGETS, …). It is copied into the app's private storage on
first launch. `AAssetDir` enumeration proved unreliable for APK assets, so the
file list is generated at configure time into `sdcard_manifest.h` and the
runtime copies each entry individually, skipping files whose size already
matches (so it never clobbers `radio.yml` or models the firmware wrote).

## Launcher icon

`res/mipmap-anydpi-v26/ic_launcher.xml` declares an **adaptive icon**; the
artwork lives in `res/mipmap-*/ic_launcher_foreground.png` with the shield
colour as the background. A plain PNG would be treated as a legacy icon, which
launchers shrink into the safe zone and wrap in their own shape.

## Troubleshooting

* **The UI is blank / the process dies right after launch.** Look for a
  tombstone first: `adb logcat -b crash`. A NULL dereference inside
  `lv_tlsf_malloc` means patch 5 is missing.
* **Tapping the EdgeTX logo crashes.** Patch 4 is missing.
* **`Gradle requires JVM 17 or later`.** Set `JAVA_HOME` (see *Building*).
* **`EdgeTX simulator library not found`** while configuring the app module —
  the staging directory has no library for an ABI listed in `abiFilters`.
  Build every ABI you ship, or drop the extra ABI.
* Log tags: `EdgeTXUI` (host), `EdgeTXSim` (firmware `TRACE()` output).

## License

GPLv2, like EdgeTX. This project links and redistributes EdgeTX code and its
bundled SD-card content (themes, sounds, scripts), all of which are GPLv2.
