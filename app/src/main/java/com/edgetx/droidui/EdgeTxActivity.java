package com.edgetx.droidui;

import android.Manifest;
import android.app.NativeActivity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * The launcher activity: Android's {@link NativeActivity}, plus the one permission request that
 * cannot be made from anywhere else.
 *
 * <p>The app itself still runs from NativeActivity - this subclass only adds {@code onCreate} and
 * {@code onRequestPermissionsResult} - so nothing about the native side changes; the manifest
 * names this class instead of {@code android.app.NativeActivity} and everything after that
 * (lib_name meta-data, the callbacks) is inherited.
 *
 * <p>The permission is the Android 10 storage one. Android 11 and later use "All files access",
 * which the user grants once in system settings (see {@link EdgeTxApplication}), but Android 10
 * has no such thing: the simulated SD card in {@code /storage/emulated/0/EdgeTX} is written with
 * {@code WRITE_EXTERNAL_STORAGE} there, and a runtime permission can only be requested from an
 * activity. On the RC Pro (Android 10) that is what puts the card where a file manager can reach
 * it, the way it already is on every other remote.
 *
 * <p>Android 11+ returns immediately: the permission is not even declared for those versions
 * (maxSdkVersion 29 in the manifest), and cannot be requested.
 */
public class EdgeTxActivity extends NativeActivity {

    private static final String TAG = "EdgeTXUI";

    /** Only ever used for the one request below. */
    private static final int REQUEST_STORAGE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Before 6.0 it comes with the install, from 11 on it is all-files access instead.
            return;
        }

        try {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Log.i(TAG, "sdcard: asking for WRITE_EXTERNAL_STORAGE, "
                    + "Android 10 has no all-files access");
            requestPermissions(
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        } catch (Throwable t) {
            // Never let a permission prompt take the app down: without it the card simply stays
            // in the app's own directory, which the link logs at start-up.
            Log.w(TAG, "sdcard: could not ask for WRITE_EXTERNAL_STORAGE", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        final boolean granted = results != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        // The card's root is decided when the link starts, which has already happened by the
        // time this answer arrives - so a grant takes effect on the next start, and saying so
        // is the point of this line.
        Log.i(TAG, "sdcard: WRITE_EXTERNAL_STORAGE " + (granted
                ? "granted - the card moves to /storage/emulated/0/EdgeTX on the next start"
                : "denied - the card stays in the app's own directory"));
    }
}
