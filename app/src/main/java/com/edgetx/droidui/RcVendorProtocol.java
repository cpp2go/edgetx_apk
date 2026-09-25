package com.edgetx.droidui;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic probe for DJI's own protocol library, off unless the probe file exists.
 *
 * <p>Why it exists: on this remote the buttons and the flight-mode switch are not Android key
 * events (no input device carries them - see the notes on gpio-keys), they are not broadcasts
 * (the remote's own service sends its key actions with {@code setPackage("com.dpad.service")}, so
 * only its own package receives them), and they are in no world-readable sysfs node. The DJI
 * SDK does deliver them, but only outside its quiet spells - so the remote's own button log
 * (needing the adb-only READ_LOGS grant) is the only wave-proof source there is.
 *
 * <p>The remote's own service gets them from DJI's protocol library
 * ({@code com.dji.protocol.*}, implemented in {@code /system/framework/protocol.jar}) and it
 * learns the key names from {@code KeyCodeManager.ACTION_MAP_FIRST4_BYTE} - including
 * {@code ACTION_KEY_SWITCH_MODE}, which is the flight-mode switch the SDK keeps losing. If those
 * classes are on this app's class path too, the same packets can be read here with no permission,
 * no SDK and no log access at all - which would be the end of the whole grant story.
 *
 * <p>This only looks and logs: it makes no call and changes nothing.
 */
final class RcVendorProtocol {

    private static final String TAG = "EdgeTXUI";

    /** Same trigger file as the other verbose input logs (see DjiMsdkBridge.PROBE_TRIGGER). */
    private static final String TRIGGER = "hid_probe";

    /** The set the remote's own key handler listens to: {@code new PackRule.Builder().cmd(6, 174)}. */
    private static final int KEY_CMD_SET = 6;
    private static final int KEY_CMD_ID = 174;

    /** The classes worth asking about, most interesting first. */
    private static final String[] CANDIDATES = {
            "com.dji.protocol.ProtocolManager",
            "com.dji.protocol.ProtocolBase",
            "com.dji.protocol.ProtocolCommonGetVersion",
            "com.dji.protocol.Pack",
            "com.dji.protocol.PackFilter",
            "com.dji.protocol.PackRule",
            "com.dji.protocol.PackUtil",
            "com.dji.protocol.IPackListener",
            "com.dji.protocol.DataConfig",
            "com.dji.protocol.DataConfig$CMDTYPE",
            "com.dji.protocol.DataConfig$NEEDACK",
            "com.dji.protocol.ECode",
            "com.dji.protocol.CallBack",
    };

    private RcVendorProtocol() {}

    /** Called from {@code EdgeTxApplication.onCreate}; does nothing without the trigger file. */
    static void start(Context context) {
        try {
            if (!new File(context.getExternalFilesDir(null), TRIGGER).isFile()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }

        Log.i(TAG, "vendor protocol: checking whether DJI's protocol library is on this app's "
                + "class path");
        for (String name : CANDIDATES) {
            try {
                final Class<?> cls = Class.forName(name);
                final ClassLoader loader = cls.getClassLoader();
                Log.i(TAG, "vendor protocol: " + name + " IS visible (loader "
                        + (loader == null ? "boot class path" : loader.getClass().getName()) + ")");
                Log.i(TAG, "vendor protocol:   declared methods=" + cls.getDeclaredMethods().length
                        + " fields=" + cls.getDeclaredFields().length
                        + " constructors=" + cls.getDeclaredConstructors().length);
                for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                    Log.i(TAG, "vendor protocol:   field " + field.getName() + " : "
                            + simpleName(field.getType()));
                }
                for (Method method : cls.getDeclaredMethods()) {
                    Log.i(TAG, "vendor protocol:   method " + signature(method));
                }
                final Object[] constants = cls.getEnumConstants();
                if (constants != null) {
                    final StringBuilder names = new StringBuilder();
                    for (Object constant : constants) {
                        names.append(constant).append(' ');
                    }
                    Log.i(TAG, "vendor protocol:   enum constants: " + names);
                }
            } catch (Throwable t) {
                Log.i(TAG, "vendor protocol: " + name + " is not visible (" + t + ")");
            }
        }

        // Where the library actually lives: the classes resolve, so this says which jar on the
        // boot class path they came out of.
        try {
            final Class<?> cls = Class.forName("com.dji.protocol.ProtocolManager");
            Log.i(TAG, "vendor protocol: ProtocolManager comes from "
                    + cls.getProtectionDomain().getCodeSource().getLocation());
        } catch (Throwable t) {
            Log.i(TAG, "vendor protocol: could not ask where ProtocolManager comes from: " + t);
        }

        // The members above came back empty because the platform hides everything that is not a
        // public API from reflection - and this vendor library is not a public API. The classic
        // way around that is to exempt its package, which is one hidden call of its own; if it is
        // still allowed on this build, the library can be used as its own apps use it.
        try {
            final Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            final Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            vmRuntime.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[] {"Lcom.dji.protocol"});
            Log.i(TAG, "vendor protocol: setHiddenApiExemptions(Lcom.dji.protocol) succeeded");
        } catch (Throwable t) {
            Log.i(TAG, "vendor protocol: setHiddenApiExemptions failed: " + t);
        }

        // The one call the remote's own service makes before it can listen for anything. If the
        // hidden-API policy refuses it, reflection is the wrong tool for this whole route and
        // that is worth knowing now rather than after a client is written.
        try {
            final Class<?> cls = Class.forName("com.dji.protocol.ProtocolManager");
            Log.i(TAG, "vendor protocol: after the exemption, ProtocolManager declares "
                    + cls.getDeclaredMethods().length + " methods");
            final Method getDefault = cls.getMethod("getDefault");
            final Object instance = getDefault.invoke(null);
            Log.i(TAG, "vendor protocol: ProtocolManager.getDefault() returned " + instance);
            Log.i(TAG, "vendor protocol: isEnable() = " + cls.getMethod("isEnable").invoke(instance));
        } catch (Throwable t) {
            Log.i(TAG, "vendor protocol: ProtocolManager.getDefault() is not callable: " + t);
            return;
        }

        listen();
    }

    /**
     * The filter the remote's own service uses, found in its own code:
     * {@code new PackRule.Builder().cmd(6, 174).build()}. Everything the remote reports about its
     * keys arrives as a pack in that set, the flight-mode switch included - so this is the one
     * place that could replace both the SDK and the log.
     */
    private static void listen() {
        try {
            final Class<?> managerCls = Class.forName("com.dji.protocol.ProtocolManager");
            final Class<?> filterCls = Class.forName("com.dji.protocol.PackFilter");
            final Class<?> ruleCls = Class.forName("com.dji.protocol.PackRule");
            final Class<?> builderCls = Class.forName("com.dji.protocol.PackRule$Builder");
            final Class<?> listenerCls = Class.forName("com.dji.protocol.IPackListener");

            final Object manager = managerCls.getMethod("getDefault").invoke(null);
            final Object filter = filterCls.getMethod("obtain").invoke(null);
            final Object builder = builderCls.getConstructor().newInstance();
            builderCls.getMethod("cmd", int.class, int.class).invoke(builder, KEY_CMD_SET,
                    KEY_CMD_ID);
            final Object rule = builderCls.getMethod("build").invoke(builder);
            filterCls.getMethod("addRule", ruleCls).invoke(filter, rule);
            Log.i(TAG, "vendor protocol: built a filter for cmdSet " + KEY_CMD_SET + " cmdId "
                    + KEY_CMD_ID);

            // The clean client would be a subclass of this, the way the remote's own service does
            // it: it decodes the callback parcels for us and needs no reflection.
            for (String name : new String[] {"com.dji.protocol.IPackListener$Stub",
                    "com.dji.protocol.IPackListener$Stub$Proxy", "com.dji.protocol.IPackListener$Default"}) {
                try {
                    final Class<?> cls = Class.forName(name);
                    Log.i(TAG, "vendor protocol: " + name + " exists, declares "
                            + cls.getDeclaredMethods().length + " methods, "
                            + cls.getDeclaredConstructors().length + " constructors");
                    for (Method method : cls.getDeclaredMethods()) {
                        Log.i(TAG, "vendor protocol:   " + signature(method));
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "vendor protocol: " + name + " does not exist (" + t + ")");
                }
            }

            final Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerCls.getClassLoader(), new Class<?>[] {listenerCls},
                    (proxy, method, args) -> {
                        // The library does not keep the listener itself: it sends a binder to the
                        // protocol service and the service calls back on that binder. So this is
                        // the object that has to be real, and its parcels are the packets.
                        if ("asBinder".equals(method.getName())) {
                            return CALLBACK;
                        }
                        Log.i(TAG, "vendor protocol: listener got " + method.getName()
                                + " (" + (args == null ? "no arguments" : args[0]) + ")");
                        return null;
                    });
            managerCls.getMethod("addPackListener", filterCls, listenerCls)
                    .invoke(manager, filter, listener);
            Log.i(TAG, "vendor protocol: listening for cmdSet " + KEY_CMD_SET + " cmdId "
                    + KEY_CMD_ID + " -> key packets");
        } catch (Throwable t) {
            Log.i(TAG, "vendor protocol: could not listen for key packets: "
                    + Log.getStackTraceString(t));
        }
    }

    /** The binder the protocol service calls back on: every call it makes is logged raw. */
    private static final android.os.Binder CALLBACK = new android.os.Binder() {
        @Override
        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply,
                int flags) {
            try {
                Log.i(TAG, "vendor protocol: callback code=" + code + " " + parcel(data));
            } catch (Throwable t) {
                Log.i(TAG, "vendor protocol: callback code=" + code + " unreadable: " + t);
            }
            if (reply != null) {
                try {
                    reply.writeNoException();
                } catch (Throwable ignored) {
                    // Nothing to do about a reply we cannot write.
                }
            }
            return true;
        }
    };

    /** A parcel as hex, so a callback whose layout is unknown still says something. */
    private static String parcel(android.os.Parcel data) {
        final byte[] bytes = data.marshall();
        final StringBuilder hex = new StringBuilder();
        for (int i = 0; i < bytes.length && i < 64; i++) {
            hex.append(String.format("%02X ", bytes[i]));
        }
        return "[" + bytes.length + " bytes] " + hex;
    }

    /** One key pack, read field by field and written as the name of the control it means. */
    private static void logPack(Object pack) {
        try {
            final Class<?> cls = pack.getClass();
            final byte[] data = (byte[]) field(cls, pack, "data");
            if (data == null) {
                return;
            }
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < data.length && i < 12; i++) {
                hex.append(String.format("%02X ", data[i]));
            }
            final int code = data.length >= 4
                    ? (data[0] & 0xFF) | (data[1] & 0xFF) << 8 | (data[2] & 0xFF) << 16
                            | (data[3] & 0xFF) << 24
                    : -1;
            Log.i(TAG, "vendor protocol: key pack cmdSet=" + field(cls, pack, "cmdSet")
                    + " cmdId=" + field(cls, pack, "cmdId")
                    + " sender=" + field(cls, pack, "senderType") + "/"
                    + field(cls, pack, "senderId")
                    + " data[" + data.length + "]=" + hex + "-> code " + code + " ("
                    + controlName(code) + ")");
        } catch (Throwable t) {
            Log.i(TAG, "vendor protocol: could not read a key pack: " + t);
        }
    }

    /** One named field of a pack, or null when there is no such field. */
    private static Object field(Class<?> cls, Object instance, String name) {
        try {
            return cls.getField(name).get(instance);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code KeyCodeManager.ACTION_MAP_FIRST4_BYTE} from the remote's own service: the first four
     * bytes of a key pack, and the name the remote gives that control.
     */
    private static String controlName(int code) {
        switch (code) {
            case 4: return "pause";
            case 5: return "go home";
            case 6: return "shutter";
            case 7: return "focus";
            case 8: return "record";
            case 9: return "fn";
            case 10: return "auth led";
            case 11: return "5-way middle";
            case 12: return "5-way right";
            case 13: return "5-way left";
            case 14: return "5-way down";
            case 15: return "5-way up";
            case 16: return "flight-mode switch";
            case 17: return "key 17";
            case 18: return "C1";
            case 19: return "C2";
            case 20: return "C3";
            case 21: return "C4";
            default: return "not a button";
        }
    }

    /** The public methods a class declares itself, as short readable signatures. */
    private static List<String> signaturesOf(Class<?> cls) {
        final List<String> out = new ArrayList<>();
        try {
            for (Method method : cls.getDeclaredMethods()) {
                out.add(signature(method));
            }
        } catch (Throwable t) {
            out.add("(could not list methods: " + t + ")");
        }
        return out;
    }

    /** One method as it reads in the source, parameters and return type included. */
    private static String signature(Method method) {
        final StringBuilder text = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) {
            text.append(simpleName(parameter)).append(',');
        }
        if (text.charAt(text.length() - 1) == ',') {
            text.setLength(text.length() - 1);
        }
        return text.append(") -> ").append(simpleName(method.getReturnType())).toString();
    }

    /** A parameter or return type as it reads in the source, arrays included. */
    private static String simpleName(Class<?> type) {
        if (type.isArray()) {
            return simpleName(type.getComponentType()) + "[]";
        }
        final String name = type.getName();
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
