package com.edgetx.droidui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Exists only to carry {@code res/xml/usb_device_filter.xml}.
 *
 * <p>Android asks an app before letting it open a USB device, and does not remember the
 * answer across a reboot - unless the app is recognised as the device's own app, which the
 * system works out from an activity that handles {@code USB_DEVICE_ATTACHED} together with
 * a matching device filter. Declaring this is what gives the permission dialog its "use for
 * this device by default" tick; with that ticked, {@link RcRawJoystick} is handed the
 * joystick's interface at start-up instead of asking again after every reboot.
 *
 * <p>So this class must never do anything else. The device it matches is the RC's internal
 * joystick, present from boot, which means the system may start this activity at any time -
 * and it runs in its own process ({@code :usbattach}) that
 * {@link EdgeTxApplication#onCreate} deliberately leaves empty: booting the firmware and
 * keying the RF module because a joystick was attached is not a price a permission dialog
 * is worth paying.
 */
public class UsbAttachActivity extends Activity {

    private static final String TAG = "EdgeTXUI";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final Intent intent = getIntent();
        Log.i(TAG, "usb: attach intent " + (intent == null ? "(none)" : intent.getAction())
                + " - nothing to do here, the firmware is not started");
        finish();
    }
}
