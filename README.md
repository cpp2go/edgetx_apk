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
a prepared virtualenv with `-Pedgetx.python=...`.

## Building

```powershell
# JDK 17+ is required; a fresh shell usually has no JAVA_HOME.
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

.\gradlew.bat :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am start -n com.edgetx.droidui/.EdgeTxActivity
```

Then grant everything that can be granted without a dialog, once - see
[Permissions](#permissions) for what this does and what it cannot do:

```powershell
.\setup_permissions.ps1
```

### How the EdgeTX library gets built

`app/build.gradle.kts` registers the task **`buildEdgeTxSimulator`** (group
`edgetx`). For every ABI it configures and builds the EdgeTX CMake project,
strips the result and stages it at
`app/build/edgetx-libs/<abi>/libedgetx-st16mk3-simulator.so`, which is wired in
as a `jniLibs` source directory.

The firmware is a fixed dependency of the app, so an ordinary build does not
compile it: the task runs only when a staged library is missing. Rebuild it with
`-Pedgetx.build=true`; `-Pedgetx.dir=<checkout>` points the task at an EdgeTX
tree, and `-Pedgetx.skipBuild=true` forces the packaging-only path.

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

### Sticks on a DJI remote

A DJI remote's own joystick is not dispatched as `MotionEvent`s at all, so the
path above never sees it. Its sticks are read twice, and the reader that matters
is the raw one:

* the DJI SDK publishes a stick position only while it moves, 8-14 new values a
  second (`DjiMsdkBridge`);
* the joystick's USB interface ("DJI Virtual Joystick", `2ca3:1501`) produces a
  new report every 2.5 ms, and `RcRawJoystick` takes that interface away from the
  kernel driver and reads it - 400 reports a second.

While that reader is running the SDK's copies of the same axes are ignored, and
everything the reports do not carry (the flight-mode switch, the C buttons, the
remote's log) still comes from the SDK and Android — except on the RC Plus, whose
row reads all of that from DJI's protocol stream and never touches the SDK (see
*RcModel.packetOnly* below).

The reader is restarted by the app itself when it stops. That is what keeps the
sticks alive when **DJI Fly is started**: while `dji.go.v5` runs in the
background it subscribes to the same remote through the same SDK, and the SDK's
keys arrive late or not at all (see the silence notes in `DjiMsdkBridge`). The
raw reader does not go through the SDK, so it is the one source that keeps
working, and it now claims its interface back a second after a stream that broke
off (backing off to one attempt every 15 s while the device cannot be claimed):

```
raw joystick: claimed /dev/bus/usb/001/007 interface 0 (quiet)
raw joystick: the reader stopped after 184703 report(s); restarting it in 1000 ms
raw joystick: the sticks, dials and buttons now come from the raw reports
```

That reader exists only with the USB permission for the joystick, which the app
asks for while its window is up and remembers once it is granted. Without it the
sticks come from the SDK alone - and while DJI Fly is running, they stop with it.

A third reader covers exactly that hole: DJI's own protocol stream, the one its
apps read the remote's controls from. `RcProtocolService` subscribes to it the way
DJI's apps do - `ProtocolManager.getDefault()`, a pack rule for `cmd(6, 174)`, and
a listener - and runs in a process named after one of DJI's own apps (`dji.go.v5`
by default), because that library only hands packets to such a process. It needs no
permission, no SDK and no USB interface, and the 17-byte packet carries the same
six axes (16-bit, centred on 1024) the raw report does, in its own order:

```
0-3   button/switch bits     4  status
5-6   right horizontal       7-8   right vertical
9-10  left vertical         11-12  left horizontal
13-14 left wheel           15-16  right wheel
```

Those classes are not public API, and on a remote whose firmware keeps them on the
boot class path an app targeting SDK 33 sees **every one of their members filtered
out** of reflection: `ProtocolManager` comes back with no members at all, and
`Pack` with nothing but its `Parcelable` overrides. DJI's own apps are not
affected - the reference implementation targets SDK 28 - and the exemption they
use (`VMRuntime.setHiddenApiExemptions`) cannot even be reached at 33, so the
classes are read from a **copy of the dex they live in**: the dex containing them
is taken out of `/system/framework/framework.jar` into the app's cache and loaded
through a `DexClassLoader` whose parent refuses this one package, and classes
loaded that way belong to the app's domain, where nothing is filtered. The
firmware's own copy stays the fallback. When the subscription fails, what the
library actually looks like is written to `EdgeTX/protocol-api.txt` - SDK level,
target SDK, the exemption's result, the class list of the copy, and every member
of the classes involved.

The packet's full deflection is 1024 either way, and the native side takes the SDK's
660 (see `kDjiStickRange`), so the axes are scaled on the way in - unscaled they
would go flat over the last third of the stick's travel.

The sticks are taken over from it automatically (`RcProtocolInputs`), and only
while the raw reports are not arriving — the raw reader stays the faster one
whenever it is alive, and the SDK's 8-14 Hz copy stands aside for as long as these
packets do arrive. The two wheels come with them under a stricter rule: a dial the
SDK *moves* stays the SDK's, and only a dial it never moves is taken from the
packet. A resting value the dial key hands out does not count — the RC Pro 2, whose
MSDK v5 support ends before the remote, publishes no dial at all, and one RC Plus
has a dial key that hands out one value and never another. Which is what makes
P1/P2 move there once the raw reports are not arriving, and only a *movement* is
published so a dial the packet takes over does not jump at start-up:

```
sticks: DJI's protocol stream attached as the fallback (process dji.go.v5)
sticks: DJI's protocol stream is delivering
sticks: the protocol stream went quiet - the sticks fall back to the raw reports
dials: the left dial comes from DJI's protocol stream - the SDK does not move it
```

The packet's button field is a reader of its own for **every button the other
readers can miss**, and on the RC Plus it is the reader for all of them: that
remote's row sets `packetOnly`, so no SDK is registered and no log is read there
(see `RcModel.packetOnly`). The reference app for the remote works the same way —
a pack rule for `cmd(6, 174)` and nothing else. Its C1/C2/C3 have no SDK key at
all, its takeoff / ARM button answers neither the SDK key nor its log, and its log
line is written at the release and needs `READ_LOGS`. Bit 10 of the field is the
ARM button, and the rest is laid out like this (measured from DJI's own parser):
| bit | control | bit | control |
|-----|---------|-----|---------|
| 4 | pause | 11-15 | 5-way centre/right/left/down/up |
| 5 | go-home | 16-17 | 3-position switch code |
| 6 | photo | 18-21 | C1 / C2 / C3 / C4 |
| 7 | focus | | |
| 8 | record (video) | | |
| 9 | FN | | |
| **10** | **takeoff / ARM (授权灯)** | | |

Bits 16-17 carry a three-position code as well. It is decoded the way the reference
reader decodes it (`RC Plus Control 1.15`, `com.rcplus.keyprobe`, whose native part
hands the field back untouched: `2 -> one end, 0 -> the middle, 1 -> the other end`),
and code 3, which is not a position of a three-position switch, is dropped. The RC
Plus uses this code for SB, and because it registers no SDK the packet is the reader
for good there — exactly the reference app's behaviour. The packet reader still backs
off if its code changes too frequently to represent a physical switch (SB then keeps
the position it is at). On a remote that has the SDK as well, the SDK takes over
permanently as soon as it reports a real end position, so packet and SDK values
cannot alternate later if the stream blinks. The RC Plus 2 stays on the SDK because
its packet field was observed cycling while the lever was still.

What start-up did need is the middle problem: the SDK and packet field can open with
the *middle* before they have seen the lever, and the firmware comes up with a
three-position switch in the middle as well, so SB showed the middle for the first
seconds of every start whatever the lever was at. Two things fix that — the last
position is saved across restarts and put back before the boot
(`joystick.switches`, see `joystick.cpp`), and a middle from either reader is ignored
until it has reported one of the ends at least once in the session:

```
joystick: switch SB restored to down
dji: SB stays put - the SDK opened with the middle, and no reader has seen an end yet
```

The switch is also remembered across restarts (`joystick.switches`, see
`joystick.cpp`): the firmware comes up with a three-position switch in the middle,
and the saved position is put back before the boot, so the first frames already show
the end the lever is at even before any reader has answered.

The ARM bit is read both ways round, because which one the firmware sends is not
measured: a press counts when the bit goes high (a ~200 ms pulse, like the SDK's
key), and a bit that has been high for longer than a press can last (2 s) counts
again the moment it goes out - that is the LED's latched state changing, which is
one more press of the same button. A firmware that sends the button active-low
rests high and drops for the press; the drop then counts, and the rise that follows
within the same window is recognised as that press's release rather than a second
press - counting both would toggle `SA` straight back, which looks like a button
that does nothing. The reference reader maps this bit as a level, and this keeps
the two shapes from cancelling each other out. The log says which shape it was:

```
dji: ARM bit in com.dji.agras went out after 18234 ms - latched level press
dji: ARM bit in dji.go.v5 returned after 180 ms - release, not another press
```

The ARM edge is tracked independently for each process, including its initial
baseline and active-low timing. Every reader can report the press, while
`RcButtons` merges copies of the same physical press so the switch moves once:

```
dji: takeoff pressed (seen by the protocol stream (dji.go.v5))
dji: C1 from DJI's protocol stream
dji: pause from DJI's protocol stream, merged with the log one
```

While those packets are arriving (within the last second) the remote's own log
stands aside for the buttons the packet has been seen to carry: its line is only
written at the *release*, so with both readers wired a long press became two
toggles — which is what "C1/C2/C3 sometimes do nothing" was. The rule is per
button on purpose: a blanket "the stream is alive, so everything comes from it"
also took the log away from a button whose bit this firmware never sets, and the
ARM button is exactly the one that cannot afford it — the log is its only other
reader. The log comes back by itself a second after the packets stop (on the RC
Plus it is not read at all, see above):

```
dji: the remote's own log stands aside for DJI's protocol stream
```

Not taken from the packet while the raw reports are alive: the 5-way (they carry
it at 400 Hz, and a press-only copy of a direction would step the rotary twice).
On the RC Plus, which has no SDK reader left, bits 11..15 take the 5-way over the
moment those reports stop — the raw reader's press is already delivered while it
runs, so the bits are only tracked there. Focus / FN have no EdgeTX switch here.

Which of the four process names is bound is the reference reader's decision, condition
for condition: industry when that app ships with the firmware, then agriculture-X,
then — on every remote that is not the RC Pro (`rm510`), and where DJI Fly is absent
or the agriculture app is there — the agriculture process, and DJI Fly's own for
everything else. "There" means *preinstalled*: the reference asks the package
manager for `FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP`, because one remote can carry
DJI Fly and the agriculture app side by side and a sideloaded copy must not weigh
the same as the firmware's own.

On a remote read from the packet alone (`RcModel.packetOnly`: the RC Plus and
anything this port has not been measured on) that rule is the whole of the choice:
exactly that one service is bound and nothing else — the same single listener DJI's
own app would be, so an untouched system and one with DJI Fly flashed in are read
the same way, and its first packet is the state the remote is in. Every axis, every
button bit, the 5-way directions and the flight-mode code come from that one stream:

```
rc protocol: preinstalled dji.go.v5=true, agras=true, agrasx=false, industry=false; listening in com.dji.agras
rc protocol: the reference reader uses com.dji.agras here - that is the only process bound
```

The remotes that also have the SDK treat the chosen name as a guess: if it has not
produced a packet after 6 s, the other three are bound as well; the button field
keeps its own winner, and the chosen process takes it over as soon as it speaks, so
a fallback that answered first cannot keep the other protocol buttons away from it.
The ARM bit is tracked per process there and is not blocked by that field winner.
The general field itself is judged by whether it *moves*: every process sends the
value its stream opens with, and a process whose field carries nothing sends that
value and never another. None of that applies where only the one process is bound.

The first time each bit of the field is seen set it is named once in the log, so a
"this button does nothing" report can say which bit the button actually uses even
when this app has no switch for it:

```
dji: DJI's protocol stream uses button bit 10 (0x400): takeoff / ARM -> SA
dji: DJI's protocol stream uses button bit 17 (0x20000): three-position switch code -> SB
```

Only frames that carry the six axes are used. The endpoint also delivers other
reports, and one of those decoded as a stick position is six zeroes: -100% on all
four stick channels, with the interface still claimed so the SDK's copies are
ignored too. A frame is therefore used only when its payload reads like the stick
report measured here - the full 18 bytes, with at least four of the six axis
fields inside their travel. Anything else is logged once with its bytes and
skipped, and the interface is given back if no stick report arrives at all:

```
raw joystick: frame 1 is not a stick report (only 0 of 6 axes read like a stick
    (LH=0 LV=0 RH=0 RV=0 dialL=0 dialR=0, id 02 0E), ignored: 02 0E 3A 01 00 ...
raw joystick: nothing usable for 1000 ms (240 frame(s) ignored), giving the
    interface back
```

### Remote-controller buttons

Which switch a button drives is a property of the remote, not of the button:
the same C1 is `SF` on one remote and `SG` on another, the photo button steps
`SE` on one and `SF` on the next, and the go-home button is either a switch of
its own or the modifier that makes the 5-way trim a stick. Every remote this
port has been run on therefore has a row in
[`RcModel.java`](app/src/main/java/com/edgetx/droidui/RcModel.java) (which
row the SDK buttons use, and how its data behaves) and in
[`rc_model.h`](app/src/main/cpp/rc_model.h) (the switch types written into
`radio.yml`). Both read `Build.DEVICE`, so they cannot disagree.

| Remote | `Build.DEVICE` | video / pause / photo | C1 / C2 / C3 | round | go-home | flight mode top |
|--------|----------------|-----------------------|--------------|-------|---------|-----------------|
| RC Plus 2 | `rc701` | SC / SD / SE | SF / SG / SH | — | 5-way trim modifier (H) | `SWITCH_TWO` |
| RC Pro | `rm510`, `rm510b`, `rm510bv` | SD / SC / SF | SG / SH / SI | SE | switch SA | `SWITCH_ONE` |
| RC Plus | `rm700`, `rm700_enterprise` | SC / SD / SE | SF / SG / SH | — | 5-way trim modifier (return-home) | `SWITCH_TWO` |
| RC Pro 2 | `rc520` | SD / SC / SF | SG / SH / SI | — | switch SA | `SWITCH_TWO` |

Anything else gets the RC Plus's row and says so in the log. The rows for
the RC Pro, the RC Plus 2 and the RC Plus were settled by pressing every button
on the remote; the RC Pro 2 row is its family's layout. The RC Plus shares the
RC Plus 2's switches but **not** its readers: it takes every control from DJI's
protocol stream, the way its reference app does, and neither the SDK nor the
remote's own log is used there at all (`RcModel.packetOnly`). That is what makes
its C1/C2/C3 (which the SDK never publishes), its pause and flight-mode buttons
(which the SDK answers late) and its takeoff button work without the
`READ_LOGS` grant the log path needs — SB follows the packet's three-position
code on that remote and the SDK on the others (see the three-position notes
above). An unrecognised remote is handled exactly the same way, because those
readers need neither an SDK key nor a permission. Only the buttons are shared
otherwise: the RC Plus runs Android 10, so the grant its SD card needs is its own
(see below).

The six side buttons L1/L2/L3/R1/R2/R3 are not in the DJI SDK at all — the
only supported way to read them is Android key events
([dji-sdk/Mobile-SDK-Android-V5#704](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/704)),
which is what `joystick.cpp: kPositionKeys` does: `F1..F3` pick the positions
of `SJ`, `F4..F6` those of `SI`.

At startup the app logs the remote and the whole layout, so a button on the
wrong switch is distinguishable from a button that never arrived:

```
dji: remote RC Pro (rm510 / DJI RC Pro): video SD, pause SC, photo SF, C1 SG, C2 SH, C3 SI (log only), round SE; go-home switch SA; flight mode top = SWITCH_ONE; the remote's log may move switches
```

Then it asks the SDK which of its keys this remote actually has, and logs the
answer as one line (`+` has it, `-` does not) plus a second line naming the keys
that exist here but have no switch on this model (an RC Pro 2 in this example):

```
dji: this remote's SDK keys (+ has it, - does not): record+ shutter+ shutterLongPress- pause+ goHome+ takeoff+ C1+ C2+ C3+ C4- playback- rcSwitch- rightWheel+ scrollWheel+ leftDial+ rightDial+ fiveWay+ flightMode+ softSwitchMode- transform-
dji: this remote has keys with no EdgeTX switch here: rightWheel - see RcModel if one of them should drive something
dji: the SDK reports remote controller type RC_PRO
```

That is what makes a remote this port has never been measured on workable
without pressing every button by hand: what it has is reported by the SDK, and
what it drives comes from its row in `RcModel`. A key that exists but has no
switch is listened to anyway and says so once when it is pressed, which is the
difference between "the button is dead" and "nothing here is wired to it":

```
dji: 'rightWheel' fired - this remote has that key and the app has no EdgeTX switch for it yet (see RcModel)
```

Two things the SDK documentation settles, and this app follows:

* The L/R row of six buttons is **not** in the SDK. DJI's article *How to listen
  the L/R series button on RC Plus* says they are Android buttons whose key codes
  are `F1`..`F6`, read with `onKeyDown`/`onKeyUp` — which is `joystick.cpp`:
  `kPositionKeys`. `KeyRCSwitchButtonDown` is the *photo/video mode* switch, not
  one of them.
* MSDK v5 does **not** support the RC Pro 2 (DJI's answer to dji-sdk/Mobile-SDK-Android-V5#561),
  so on that remote the SDK reports little or nothing: its buttons come from Android
  key events and the remote's own log (both are in place), and its dials from DJI's
  protocol stream once the raw reports are not arriving (see *Sticks on a DJI remote*).

MSDK 5.18 carries the same four custom buttons in two more keys, and both are
watched because a remote's own key for one of them is not always there:

* `KeyRcButtonEventPro` (`RcCustomButtonEvent.isC1Click`..`isC4Click`) is one event
  per press, merged the way the remote's log is.
* `KeyRcHardwareState` (`RcCustomButtonHardwareStatus.isC1Click`..) is a level
  pushed every few hundred milliseconds, merged over a wider window because its
  copy of a press can land after the exact readers have pressed *and* released.

Both are acted on only while that button's own key is absent, not subscribed to
(the RC Pro's C3) or in one of the SDK's silent waves, so a press the exact
reader delivers is never counted twice. That is what gives C1/C2/C3 a reader on a
remote whose Boolean keys never answer, without the `READ_LOGS` grant the log
path needs.

The edge is what a listener acts on, not the position of the callback: a press
is `true` after anything but `true`, and it counts even as a key's *first*
callback. The SDK drops a key to null in a wave and re-delivers it with a null
old value (`true` after null is a press, not a snapshot), and a key that has no
cached value to open the subscription with used to have its first press swallowed
as the snapshot it never was — which is one of the two ways the takeoff button
could look dead.

The takeoff button — the one with the red/green LED — is `SA` on the Plus family.
On the RC Plus 2 the SDK publishes it as `KeyRCAuthLedButtonDown` (one ~200 ms
pulse per press). The RC Plus reads it from the packet's ARM bit instead (bit 10
of the button field, see above), because its SDK never publishes that key at all;
a name in its log that reads like takeoff (`TAKEOFF`, `TAKEOFF_LANDING`,
`ONEKEYTAKEOFF`, `AUTHLED`…) is no longer needed there. On a remote whose packet
does not carry it, the same switch is still read four ways: the SDK listener, the
SDK's cached value (polled with the sticks, one key per 200 ms), the action the
SDK's button event names (`KeyRcButtonEventPro` →
`RcButtonAction.ONEKEYTAKEOFFLANDING`, which is the one path that needs no
`READ_LOGS`) and that takeoff-shaped name in the remote's own log. The name that
matched is logged the first time, so one run says which one it was:

```
dji: the remote's own log calls the takeoff button 'AUTH_LED'
dji: switch 0 (takeoff) -> high [from log]
dji: takeoff from the button-action event - the remote's own key for that button is not delivering
```

The action-event copy is taken only while the remote's own key has never
answered at all or is in one of the SDK's silent waves, so on the RC Plus 2 — where
that key is the reader — the event cannot move `SA` twice for one press: RcButtons
also merges the copies it does see.

Every button the app does not map is logged once (`unmapped button keycode 131
(F5)`, `the remote logged an unknown button 'PLAYBACK'`), which is how a new
remote's row is filled in.

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

**The USB permission dialog.** Android asks before letting an app open a USB
device, and the same goes for the RC's own joystick. Both dialogs are shown **once
per device per answer**:

* **确定** (OK) is remembered for good - it is saved per `vendor:product`, so this
  dialog never comes back on later launches either. Ticking *连接…后一律打开* in the
  dialog additionally makes the system itself grant the device on every attach.
* **取消** (Cancel) is honoured for the rest of that launch and **asked again on the
  next one**, so a refused module is not dead forever.
* The one exception to "OK is not asked again": a saved OK the system no longer backs
  with a permission (a reboot without the *一律打开* tick, or a module that
  re-enumerated). Android cannot re-grant that silently, so the dialog comes back
  **once per launch**, with a hint to tick *一律打开* - better than a module that
  stays silent with no way back.
* A dialog is only raised while the EdgeTX window is on screen: asked for from the
  background, Android refuses the request without ever showing it, and that refusal
  would look like the user's own answer.

The saved answers live in the app's `usb` preferences; clearing the app's data
makes both devices ask again.

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
| DJI's protocol stream (every control on the RC Plus, and the sticks elsewhere when the raw reports are not) | **keeps flowing**: its listener runs in a bound service process |
| The joystick's raw USB reports | **keeps flowing**: read from a thread of this process |
| Android input events (a gamepad, or any controller whose events Android dispatches) | **stops**: only a focused window receives them, and a service has no window |
| Touch / the UI itself | stops until the app is opened again |

Watch it from outside with the link heartbeat (every 10 s):

```
link: firmware running, module tx 4213 B rx 0 B dropped 0 B, port open, window detached
```

`window detached` with `tx` still climbing is the whole point of the service.

## Permissions

Everything that can be granted at install time is granted by one script, so the
app stops putting dialogs in front of the user. It needs adb only - no root, no
system app - and every grant it makes is stored by Android and survives a reboot:

```powershell
.\setup_permissions.ps1                  # grant what can be granted
.\setup_permissions.ps1 -Apk <apk> -Restart   # install, grant, relaunch
```

It grants `ACCESS_FINE_LOCATION` (the remote's own GPS, `RcGps`),
`WRITE_EXTERNAL_STORAGE` (the card on Android 10) or the `MANAGE_EXTERNAL_STORAGE`
app-op (Android 11+), and `READ_LOGS` (the remote's own button log, `RcDpadLog` -
not used on the RC Plus, which reads no log) where the build lets shell hand it
out. It then verifies each one and prints the state, so a grant that the remote
refuses is visible rather than silent.

**The USB device permission is the one thing that cannot be pre-granted.** Android
hands a USB device grant out only through a dialog, or to a system app, and the
grant itself is the system's: it is dropped when the device is detached and at
every reboot. What *is* kept is the device's default app - the "use by default for
this USB device" tick in that dialog, which is offered because the app declares the
joystick (`2ca3:1501`) and the UART bridges in `res/xml/usb_device_filter.xml`
next to a `USB_DEVICE_ATTACHED` activity (`UsbAttachActivity`). Ticked once, the
system hands those devices over at boot and starts that activity instead of asking,
so no dialog comes back after any reboot. The app says this once on screen
(`EdgeTxApplication.hintUsbDefaultOnce`) the first time it has to raise the dialog;
nothing else is possible from an app, because the setting behind that tick is only
reachable with a signature-level permission.

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
| RC Pro / RC Plus | 10 | `WRITE_EXTERNAL_STORAGE`, a runtime dialog the app raises at start-up |
| RC Plus 2 | 11 | "All files access" (`MANAGE_EXTERNAL_STORAGE`), a system settings page the app opens at start-up |

The app asks once per launch until the grant exists, and boots with the card in
its own directory if it is never given. Grant it from the shell instead - this is
what `setup_permissions.ps1` does after installing:

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

* **The sticks stop after DJI Fly was started.** They come from the joystick's raw USB
  reports (see *Sticks on a DJI remote*), and `raw joystick: the reader stopped after … report(s);
  restarting it in 1000 ms` followed by `claimed` is the app putting that reader back. Without
  that permission they come from the SDK alone, which DJI Fly starves while it runs - so the USB
  dialog for "DJI Virtual Joystick" must be allowed, not refused.
* **The sticks sit at -100% and do not move.** All four stick channels at their bottom is the
  raw reader being fed frames that are not stick positions - every frame the endpoint delivered
  used to be decoded as one, so a status report pinned the sticks to the bottom and kept the
  SDK's copies switched off at the same time. Such frames are now skipped, and the log says
  what they were:

  ```
  raw joystick: frame 1 is not a stick report (only 0 of 6 axes read like a stick (...), id 02 0E), ignored: ...
  raw joystick: nothing usable for 1000 ms (240 frame(s) ignored), giving the interface back
  ```

  If the sticks still do not move, those two lines are what a report needs: they hold the
  bytes the device sends instead of the stick report while it is linked to an aircraft.
* **The UI is blank / the process dies right after launch.** Look for a
  tombstone first: `adb logcat -b crash`.
* **`Gradle requires JVM 17 or later`.** Set `JAVA_HOME` (see *Building*).
* **`EdgeTX simulator library not found`** while configuring the app module —
  the staging directory has no library for an ABI listed in `abiFilters`.
  Build every ABI you ship, or drop the extra ABI.
* **Link errors on `simuSetSplashStartup`, `edgetxAndroidTakeAudio`,
  `edgetxAndroidSetAuxSerialSink`, or `simuStart(bool)`.** The `.so` that was
  built is not from the same EdgeTX checkout as the sources: check
  `CMAKE_HOME_DIRECTORY` in `app/build/edgetx-build/<abi>/CMakeCache.txt`, and
  pass the right tree with `-Pedgetx.dir=...`. A CMake build directory remembers
  its source tree, so delete `app/build/edgetx-build/<abi>` when switching trees.
* Log tags: `EdgeTXUI` (host), `EdgeTXSim` (firmware `TRACE()` output).

## License

GPLv2, like EdgeTX. This project links and redistributes EdgeTX code and its
bundled SD-card content (themes, sounds, scripts), all of which are GPLv2.
