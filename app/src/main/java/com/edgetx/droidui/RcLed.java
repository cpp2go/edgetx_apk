package com.edgetx.droidui;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Drives the RC's RGB indicator - the red/green light on the takeoff button.
 *
 * <p>There is no DJI Mobile SDK key for it (every LED key in MSDK 5.18 is on the
 * aircraft, camera or gimbal), and the framework route is closed as well: the RC runs
 * Android 11, where {@code android.hardware.lights.LightsManager} is still a system
 * API, and asking the running lights service for the light list is refused with
 * <i>getLights requires CONTROL_DEVICE_LIGHTS_PERMISSION</i> - a signature|privileged
 * permission this app cannot hold.
 *
 * <p>The permission check lives in the framework service rather than in the HAL, so the
 * light HAL is addressed directly. Its AIDL stub class cannot be loaded by name (it is
 * a hidden system API), hence the parcel is built by hand. Measured layout, confirmed
 * against the vendor HAL's own log line and by reading the kernel LED nodes back:
 *
 * <pre>
 *   writeInterfaceToken("android.hardware.light.ILights")
 *   int  lightId
 *   int  1                    // presence, as writeTypedObject would write it
 *   int  20                   // length of the LightState bytes
 *   int  color                // ARGB
 *   int  flashMode            // 0 = keep the light steady
 *   int  flashOnMs
 *   int  flashOffMs
 *   int  brightnessMode       // 0 = user
 * </pre>
 *
 * <p>Transaction 1 is setLightState and 2 is getLights. Getting this wrong is harmless
 * but silent: with a missing presence int the HAL reads the colour as zero and switches
 * the light off.
 *
 * <p>Note that the lookup currently only succeeds because the vendor policy for
 * {@code service_manager find} on hal_light_service is permissive; a stricter policy
 * would turn this into a no-op, which is why every step is best-effort.
 */
final class RcLed {
    private static final String TAG = "EdgeTXUI";

    private static final String HAL_SERVICE = "android.hardware.light.ILights/default";
    private static final String DESCRIPTOR = "android.hardware.light.ILights";
    private static final int TX_SET_LIGHT_STATE = 1;

    /** The RGB light as the HAL lists it - see `dumpsys lights`. */
    private static final int RGB_LIGHT_ID = 3;

    /** Matches the button: low is the red state, high the green one. */
    static final int RED = 0xFFFF0000;
    static final int GREEN = 0xFF00FF00;

    private static IBinder sBinder;
    private static boolean sBroken;

    private RcLed() {}

    /** Best-effort: a working LED is a nicety, never something the app can fail on. */
    static synchronized void setColor(int argb) {
        if (sBroken) {
            return;
        }
        if (sBinder == null) {
            sBinder = lookup();
            if (sBinder == null) {
                return;
            }
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (send(sBinder, RGB_LIGHT_ID, argb)) {
                return;
            }
        }
        // A dead binder is worth one more look-up; anything else just means this build
        // of the RC will not let us have the LED.
        sBinder = lookup();
        if (sBinder == null || !send(sBinder, RGB_LIGHT_ID, argb)) {
            sBroken = true;
            Log.i(TAG, "dji: rc led unavailable");
        }
    }

    /**
     * The flash fields are always written as "steady": the HAL's other two modes were tried
     * on device (timed, which the vendor maps onto the kernel's blink delay_on/delay_off,
     * and the hardware pattern) and they drive the same module, so neither gets us anywhere.
     */
    private static boolean send(IBinder binder, int lightId, int argb) {
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            writeToken(data);
            data.writeInt(lightId);
            data.writeInt(1);      // presence
            data.writeInt(20);     // LightState length in bytes
            data.writeInt(argb);   // colour
            data.writeInt(0);      // flashMode: steady
            data.writeInt(0);      // flashOnMs
            data.writeInt(0);      // flashOffMs
            data.writeInt(0);      // brightnessMode: user
            binder.transact(TX_SET_LIGHT_STATE, data, reply, 0);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "dji: rc led write failed", t);
            return false;
        }
    }

    /** The light HAL's binder, looked up the same way the framework service would. */
    private static IBinder lookup() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, HAL_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "dji: rc led lookup failed", t);
            return null;
        }
    }

    /**
     * Parcel#writeInterfaceToken writes the caller's strict-mode policy - an int the
     * reader discards - followed by the interface name as a string16, which is what
     * Parcel#writeString produces, so the hidden method is only a convenience.
     */
    private static void writeToken(Parcel data) {
        try {
            final Method write = Parcel.class.getMethod("writeInterfaceToken", String.class);
            write.invoke(data, DESCRIPTOR);
        } catch (Throwable t) {
            data.writeInt(0);
            data.writeString(DESCRIPTOR);
        }
    }
}
