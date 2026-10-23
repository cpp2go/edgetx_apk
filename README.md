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

The firmware is **not** owned by the activity: `RcLinkService` (a foreground
service, same process) starts it and keeps the process alive, and the activity
only attaches a window to it while it is on screen. See *Link lifetime* below.

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
| `edgetx.skipBuild`   | `false`                       | Package the libraries already staged in `app/build/edgetx-libs/` instead of building them |

`-Pedgetx.skipBuild=true` is what a machine without the patched EdgeTX checkout
uses: build or copy `libedgetx-st16mk3-simulator.so` into
`app/build/edgetx-libs/<abi>/` once, then build the APK from it.

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

## RF modules

EdgeTX has two module slots. This app bridges each slot to its own USB serial
port, but **drives only one at a time**, the one the model enables:

| Slot | Device | Firmware port |
|------|--------|---------------|
| internal | the built-in module (the C3, ESP32 native USB Serial/JTAG, `303a:1001`) | AUX1 (port 0) |
| external | a module on a USB-UART adapter (CH34x, CP210x, FTDI, Prolific) | AUX2 (port 1) |

* Internal slot enabled → the internal device is opened; the external one is kept
  closed and its frames are discarded, so the other module cannot transmit.
* Internal slot off, external slot enabled → the external device is opened.
* Only one module attached → it is used as the internal one, so a model with the
  internal slot enabled works without changing any settings.
* Both attached → the C3 is the internal module and anything else is the external
  one; the internal slot still has priority.

The active slot runs whatever protocol the model selects for it (CRSF, SBUS, DSM,
PPM, ...), at the baud rate and framing EdgeTX configures for that slot. The
firmware binds the internal slot to AUX1 and the external slot to AUX2, and
`module_serial.cpp` keeps one TX queue per port, so bytes never have to be guessed
into a slot.

`RcModuleSerial` logs every transition with the slot's name (`internal slot` /
`external slot`) and mirrors the log into `module_link.txt` in the app's external
files directory.

**Power.** A bus-powered USB hub may not be able to feed the C3 and an external
module at the same time. Both devices sit behind the same hub here, and with the
external module - a transmitter with a PA - on it the hub reset itself every 20 to
60 seconds: both devices disappeared and came back with new device numbers, which
the app sees as a detach, a re-open and a re-probe. The Lua then never finished a
request/answer round trip and the module looked unreachable, while every individual
sample looked healthy. A hub with its own power supply ended it: zero detaches in
the same window that used to have seven.

So when a module "sometimes" works: count `detached` lines in `module_link.txt`
first. Use a powered hub, or power the external module from its own supply with a
common ground; the app cannot power a USB port off.

**Baud rate on the wire.** The app writes the rate EdgeTX configured into the
adapter's divider, but what comes out depends on the adapter's own clock: the
CH34x takes the divider from the host and derives the bit clock from its crystal
or oscillator. The measured board here turned out to be accurate (399825 baud for
a requested 400000, i.e. 100.0 %), so a wrong rate is *not* the usual explanation,
but an out-of-spec adapter would look exactly like a dead wire: the module hears
garbage, answers nothing, and a loopback test still passes because both directions
share the clock.

The external slot therefore does not trust the configured rate blindly. It starts
with what the firmware asked for, then tries 460800, 921600, 230400 and 115200,
measures the adapter's real rate for the log, and finally sweeps ±12 % around the
configured rate in 2 % steps — UART tolerates about 2 %, so a chip that is a few
percent off is still found. The first rate at which a **CRC-valid CRSF frame**
arrives is kept for the rest of the session.

While probing it also *sends* CRSF device pings, because an ELRS module answers
only when it is asked: with the handset merely streaming RC channels it stays
silent, and a silent module is indistinguishable from a wrong rate.

**A silent module may be in WiFi mode.** ELRS transmitter modules reboot into WiFi
(SSID `ExpressLRS TX`, so they still look powered and blink) when they have not
received valid CRSF for about a minute. The serial port is dead in that mode, so
the only way out is a power cycle or the module's button — no baud rate helps. The
log says so when every rate has failed:
`no CRSF frames between ... and ... baud either ...`.

**Nothing received at all is a wiring problem.** A module prints its boot log on
its TX pin every time it powers up, so a freshly powered module that yields *zero*
bytes while the port is at its measured-correct rate means its TX is not reaching
the adapter's RX: swapped TXD/RXD on a board whose silkscreen is wrong, a loose
Dupont connector or a dead pin. A loopback short (touching the two signal wires
together) tells the two apart: if that echoes bytes, the adapter is fine and the
module side is not connected. Confirmed on the `1a86:7522` board here: its TXD/RXD
labels are reversed, so "crossed" wiring as printed was straight through, nothing
ever reached the module, and the module fell into WiFi mode about a minute later -
which is why the whole chain looked dead no matter the baud rate.

## Link lifetime

On a radio the sticks never stop reaching the RF module while the radio is on.
Android is not a radio: a process with no visible activity is torn down, so
pressing Back or swiping the app away used to stop the mixer and the module
stopped getting frames. `RcLinkService` exists to prevent that.

* It is a **foreground service** (`connectedDevice`, falling back to
  `specialUse`), started from `EdgeTxApplication.onCreate`. The process is then
  kept alive and is not touched by the cached-app freezer.
* It owns the firmware: `nativeStartLink()` (JNI) seeds the simulated SD card,
  installs the module bridge and calls `simu::start()`, and a link thread keeps
  calling `joystick::tick()` so the queued input keeps reaching the mixer.
* While no window is attached the firmware parks after its last LVGL flush: the
  mixer and module tasks keep running (that is what the RF module needs), but the
  frame handshake is left to the activity, because `takeFrame()` ends inside LVGL
  (`lcdFlushed()` → `lv_disp_flush_ready()`) and that belongs to the thread which
  owns the window. Reopening the app resumes rendering where it left off.
* It holds a **screen wake lock**, so neither the RC's display nor the firmware
  behind it ever goes to sleep while the process lives.
* `android:stopWithTask="false"`: removing the task does not stop it.
* The activity only attaches/detaches its window (`window_attach`/
  `window_detach` in `native_main.cpp`). Neither stops the firmware, and
  reopening the app re-attaches to the firmware that is already running — the
  model and the channel values are right where they were.

What keeps flowing after the UI is gone, and what does not:

| Source | After the activity is gone |
|--------|----------------------------|
| DJI SDK (sticks, dials, 5-way, switches — the RC Plus 2's sticks) | **keeps flowing**: the callbacks come from Java inside this process |
| Android input events (a gamepad, or any controller whose events Android dispatches) | **stops**: only a focused window receives them, and a service has no window |
| Touch / the UI itself | stops until the app is opened again |

Watch it from outside with the link heartbeat (every 10 s):

```
link: firmware running, module tx 4213 B rx 0 B dropped 0 B, port open, window detached
```

`window detached` with `tx` still climbing is the whole point of the service.

## Simulated SD card

`app/src/main/assets/sdcard/` holds a full EdgeTX SD-card tree (RADIO, MODELS,
THEMES, SOUNDS, WIDGETS, …). On first launch it is unpacked into a directory the
user can reach with a file manager:

```
/storage/emulated/0/EdgeTX          # the card the firmware sees
```

Writing there needs a permission that differs per remote, and the firmware is held
back until it is there (it picks its card root when it starts and cannot be moved
to another one afterwards):

| Remote | Android | Grant |
|---|---|---|
| RC Pro | 10 | `WRITE_EXTERNAL_STORAGE`, a runtime dialog the app raises at start-up |
| RC Plus / RC Plus 2 | 11 | "All files access" (`MANAGE_EXTERNAL_STORAGE`), a system settings page the app opens at start-up |

The app asks once per launch until the grant exists, and boots with the card in
its own directory if it is never given. Grant it from the shell instead:

```powershell
adb shell appops set com.edgetx.droidui MANAGE_EXTERNAL_STORAGE allow   # Android 11
adb shell pm grant com.edgetx.droidui android.permission.WRITE_EXTERNAL_STORAGE  # Android 10
```

Without any grant the card stays in the app's **external** files directory, which
needs no permission at all and which adb can also read and write:

```
/sdcard/Android/data/com.edgetx.droidui/files/sdcard      # fallback card
/sdcard/Android/data/com.edgetx.droidui/files/joystick.keys
```

```powershell
adb push main.lua /sdcard/Android/data/com.edgetx.droidui/files/sdcard/SCRIPTS/
adb pull /sdcard/Android/data/com.edgetx.droidui/files/sdcard/RADIO/radio.yml .
adb shell ls /storage/emulated/0/EdgeTX
```

Files are picked up the next time the firmware reads them (a model is loaded from
the card when it is selected), so editing one does not need a reinstall. Only
`am force-stop` is needed to get a clean firmware restart.

Devices without external storage fall back to the app-private directory
(`<files>/sdcard`, reachable with `adb shell run-as com.edgetx.droidui`), and a
card that already lives there is copied over on the first run so models and
calibration survive the move.

`AAssetDir` enumeration proved unreliable for APK assets, so the file list is
generated at configure time into `sdcard_manifest.h` and the runtime copies each
entry individually. **An existing file with content is never overwritten** — the
card is the user's, and the firmware writes to it too (LOGS/, screenshots,
`radio.yml`). Only missing (or zero-length) files are written, so delete a file
or the whole folder and relaunch to get the bundled version back. The seeding
result is in logcat:

```
sdcard: root /storage/emulated/0/EdgeTX (push files into it to edit the card, no reboot needed)
sdcard: 918 copied, 0 kept, 918 bundled
```

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
* **Link errors on `simuSetSplashStartup`, `edgetxAndroidTakeAudio`,
  `edgetxAndroidSetAuxSerialSink`, or `simuStart(bool)`.** The `.so` that was
  built is not from the patched EdgeTX checkout: check `CMAKE_HOME_DIRECTORY` in
  `app/build/edgetx-build/<abi>/CMakeCache.txt`, and pass the right tree with
  `-Pedgetx.dir=...`. A CMake build directory remembers its source tree, so
  delete `app/build/edgetx-build/<abi>` when switching trees.
* Log tags: `EdgeTXUI` (host), `EdgeTXSim` (firmware `TRACE()` output).

## License

GPLv2, like EdgeTX. This project links and redistributes EdgeTX code and its
bundled SD-card content (themes, sounds, scripts), all of which are GPLv2.
