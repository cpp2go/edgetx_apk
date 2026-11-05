package com.edgetx.droidui;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * The RC's own control stream, read the way DJI's own apps read it - the last reader, and the
 * only one that needs neither a permission nor the SDK.
 *
 * <p><b>Why.</b> The sticks already have two readers: the raw USB reports (400 per second, see
 * {@link RcRawJoystick}) and the DJI SDK (8-14 per second). The first one is only there while
 * this app can hold the joystick's USB interface, which DJI's own app takes away, and the second
 * is both slow and lost in the SDK's silent waves. The buttons have the SDK, the remote's own log
 * (a hand grant) and the raw reports - and the takeoff / ARM button on the RC Plus answers none
 * of them. What is left is the packet behind all of them: the RC firmware's own control packet,
 * which DJI's libraries route through {@code com.dji.protocol} ({@code /system/framework/protocol.jar}).
 *
 * <p><b>How it is read.</b> Exactly what DJI's own apps do - a pack rule for {@code cmd(6, 174)}
 * and a listener on the protocol manager:
 *
 * <pre>
 *   ProtocolManager.getDefault()
 *   PackFilter.obtain().addRule(new PackRule.Builder().cmd(6, 174).build())
 *   protocolManager.addPackListener(filter, listener)
 * </pre>
 *
 * <p>None of those classes are public API, so all of it is reflection, and the hidden-API
 * exemption for {@code Lcom/dji/protocol/} is what makes the lookup work at all. The listener is
 * a dynamic proxy (the interface cannot be implemented at compile time) whose {@code asBinder()}
 * is the {@link Binder} at the bottom of this class: the protocol manager wraps that binder in
 * its own AIDL proxy and transacts on it, so the packets arrive in {@code onTransact} as a
 * parceled {@code Pack} - transaction 1 is {@code onSuccess(Pack)}, 2 is {@code onFailure(ECode)}.
 *
 * <p><b>The packet</b> (17 bytes, the layout read out of DJI's own parser):
 *
 * <pre>
 *   0-3   32-bit button/switch field, one bit per control (see DjiMsdkBridge.onProtocolBits)
 *   4     extended status - not used here
 *   5-6   right horizontal    9-10  left vertical     13-14  left wheel
 *   7-8   right vertical     11-12  left horizontal   15-16  right wheel
 * </pre>
 *
 * Six 16-bit little-endian axes, all centred on 1024 and with the same range the SDK and the raw
 * reports use, so nothing but the centre comes off. The button field travels as it is: which bit
 * means which control, and when it may move a switch, is {@link DjiMsdkBridge}'s business.
 *
 * <p><b>Which process.</b> The library only hands packets to a process named after one of DJI's
 * own apps, so this service is declared once per name (see the manifest, and the three empty
 * subclasses at the bottom of this file). Which of them a given remote answers is not documented
 * and differs per DJI app installed on it, so all of them are tried and the first process that
 * produces a packet wins - see {@link RcProtocolInputs}. It is a started foreground service for
 * the same reason the reference implementation is one: the process has to stay alive on its own,
 * with the app in the background and with the UI gone.
 *
 * <p>Nothing else happens in those processes - {@link EdgeTxApplication} starts no runtime there.
 * A packet that cannot be read is dropped rather than guessed at.
 */
public class RcProtocolService extends Service {

    private static final String TAG = "EdgeTXUI";

    /** The process the base class runs in - DJI Fly. The other three are the subclasses below. */
    static final String PROCESS = "dji.go.v5";

    /**
     * Every process name the library may answer on. Which one a given remote uses depends on the
     * DJI app installed on it (consumer / agriculture / industry), and nothing documents the
     * rule, so all four are tried and the first that produces a packet wins.
     */
    static final String[] PROCESSES = {
            PROCESS, "com.dji.agras", "com.dji.agrasx", "com.dji.industry.pilot",
    };

    /** Where the app process is told about a new set of controls. */
    static final String EXTRA_SINK = "sink";

    /** Who sent a message: these four processes all look alike otherwise. */
    static final String KEY_PROCESS = "process";

    /** The packet DJI's apps read the RC's controls from: pack set 6, command 174. */
    private static final int CMD_SET = 6;
    private static final int CMD_ID = 174;

    /** Four button bytes, one status byte, six 16-bit axes. */
    private static final int PACKET_LEN = 17;

    /** The axes are 16-bit values around this centre, as the SDK's are around zero. */
    private static final int AXIS_CENTRE = 1024;

    /** The listener's interface name and its two transactions. */
    private static final String DESCRIPTOR = "com.dji.protocol.IPackListener";
    private static final int TX_ON_SUCCESS = 1;
    private static final int TX_ON_FAILURE = 2;

    private Messenger mSink;
    private Class<?> mPackClass;
    private Field mDataField;
    private Method mDataGetter;
    private Parcelable.Creator<?> mCreator;

    /** The protocol objects, held so nothing the manager registered can be collected. */
    private Object mKeep;

    /** Whether the payload accessor has been looked for (it needs a real pack to look at). */
    private boolean mPayloadLooked;

    /** This process's name, said in every message so only one of the four is listened to. */
    private String mProcess = "?";

    /** Last packet's four stick values, so an unchanged packet costs no message. */
    private final int[] mLast = new int[4];
    private boolean mAny;

    /** Last packet's button/switch field, sent when it changes. */
    private int mLastBits;
    private boolean mBitsKnown;

    private boolean mFirstPacket;

    /** Where the protocol classes are taken from: an app-domain copy when one could be made. */
    private ClassLoader mLoader;

    /** The dex copies behind {@link #mLoader}, so the API dump can list what they hold. */
    private final java.util.List<File> mCopied = new java.util.ArrayList<>();

    /** Said in the API dump, so a failure says which reader it came from. */
    private String mSource = "the framework's own classes";

    /** The result of the hidden-API exemption, for the same reason. */
    private String mExempt = "not tried";

    private final Binder mListenerBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == TX_ON_SUCCESS) {
                data.enforceInterface(DESCRIPTOR);
                final boolean present = data.readInt() != 0;
                if (present) {
                    readPack(data);
                }
                return true;
            }
            if (code == TX_ON_FAILURE) {
                data.enforceInterface(DESCRIPTOR);
                Log.w(TAG, "rc protocol: the manager reported a failure");
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mProcess = processName();
        subscribe();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null) {
            final Parcelable extra = intent.getParcelableExtra(EXTRA_SINK);
            if (extra instanceof Messenger) {
                mSink = (Messenger) extra;
            }
        }
        return new Binder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mSink = null;
        Log.i(TAG, "rc protocol: the listener in " + mProcess + " is gone");
        super.onDestroy();
    }

    // ------------------------------------------------------------ the subscription --

    private void subscribe() {
        try {
            // Two ways to see members of DJI's classes: ask for the exemption DJI's own apps
            // get, or use a copy of their dex, which is never filtered. Both are tried.
            mExempt = exemptHiddenApis();
            openDjiClasses();

            final Class<?> managerClass = djiClass("com.dji.protocol.ProtocolManager");
            final Class<?> filterClass = djiClass("com.dji.protocol.PackFilter");
            final Class<?> ruleClass = djiClass("com.dji.protocol.PackRule");
            final Class<?> builderClass = djiClass("com.dji.protocol.PackRule$Builder");
            final Class<?> listenerClass = djiClass("com.dji.protocol.IPackListener");

            mPackClass = djiClass("com.dji.protocol.Pack");
            try {
                final Field creatorField = mPackClass.getDeclaredField("CREATOR");
                creatorField.setAccessible(true);
                mCreator = (Parcelable.Creator<?>) creatorField.get(null);
            } catch (Throwable t) {
                Log.w(TAG, "rc protocol: Pack.CREATOR not usable - " + describe(t));
            }

            final Object manager = manager(managerClass);
            final Object filter = call(filterClass, null, "obtain");

            final java.lang.reflect.Constructor<?> constructor =
                    builderClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            final Object builder = constructor.newInstance();
            // cmd() is fluent: it returns the builder the rule is built from.
            final Object prepared = call(builderClass, builder, "cmd",
                    new Class<?>[] {int.class, int.class},
                    new Object[] {CMD_SET, CMD_ID});
            final Object rule = call(builderClass, prepared, "build");
            call(filterClass, filter, "addRule", new Class<?>[] {ruleClass}, new Object[] {rule});

            final ClassLoader loader = listenerClass.getClassLoader() != null
                    ? listenerClass.getClassLoader() : ClassLoader.getSystemClassLoader();
            final Object listener = Proxy.newProxyInstance(loader,
                    new Class<?>[] {listenerClass}, (proxy, method, args) -> {
                        // The manager transacts on this binder; a manager that calls the
                        // listener directly (same process) is covered as well.
                        if ("onSuccess".equals(method.getName())
                                && args != null && args.length == 1) {
                            onPackObject(args[0]);
                            return null;
                        }
                        return "asBinder".equals(method.getName()) ? mListenerBinder : null;
                    });

            call(managerClass, manager, "addPackListener",
                    new Class<?>[] {filterClass, listenerClass},
                    new Object[] {filter, listener});
            mKeep = new Object[] {manager, filter, listener, mCreator};
            Log.i(TAG, "rc protocol: listening in " + mProcess + " (cmd " + CMD_SET + '/'
                    + CMD_ID + ')');
            EdgeTxApplication.announce("协议监听已连上 (" + mProcess + ")");
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: DJI's protocol library is not usable in " + mProcess
                    + " - " + describe(t));
            final String where = dumpApi();
            EdgeTxApplication.announce("协议不可用 (" + mProcess + "): " + describe(t)
                    + (where == null ? "" : "\nAPI: " + where));
        }
    }

    /**
     * Write down what this remote's protocol library actually looks like.
     *
     * <p>Its API is not the one DJI's own apps are built against on every firmware:
     * {@code ProtocolManager.getDefault()} does not exist on the RC Plus at all (measured), and
     * {@code Pack} declares no fields there, so nothing can be assumed about names. The classes
     * on the framework jar are listed, and every one of them is described field by field and
     * method by method, into a file that can be read from the SD card:
     * {@code EdgeTX/protocol-api.txt}, and the app's own external files directory as well.
     *
     * @return where it was written, or null when nothing could be written
     */
    private String dumpApi() {
        final StringBuilder text = new StringBuilder(4096);
        text.append("process: ").append(mProcess).append('\n');
        text.append("sdk: ").append(Build.VERSION.SDK_INT);
        text.append(", target sdk: ").append(targetSdk()).append('\n');
        text.append("hidden api exemption: ").append(mExempt).append('\n');
        text.append("classes from: ").append(mSource).append('\n');

        // What the copy actually holds: a manager that is not called what we expect is in here.
        text.append("\n== com/dji/protocol classes in the copy ==\n");
        for (File dex : mCopied) {
            try {
                final dalvik.system.DexFile file = new dalvik.system.DexFile(dex);
                try {
                    final java.util.Enumeration<String> names = file.entries();
                    while (names.hasMoreElements()) {
                        final String name = names.nextElement();
                        if (name.startsWith("com.dji.protocol.")) {
                            text.append(name).append('\n');
                        }
                    }
                } finally {
                    file.close();
                }
            } catch (Throwable t) {
                text.append('(').append(dex.getName()).append(": ").append(describe(t))
                        .append(")\n");
            }
        }

        for (String name : API_CLASSES) {
            text.append("\n== ").append(name).append('\n');
            try {
                final Class<?> type = djiClass(name);
                text.append("  loader: ").append(type.getClassLoader()).append('\n');
                text.append("  extends ").append(type.getSuperclass()).append('\n');
                for (Class<?> implemented : type.getInterfaces()) {
                    text.append("  implements ").append(implemented.getName()).append('\n');
                }
                for (Field field : type.getDeclaredFields()) {
                    text.append("  F ").append(java.lang.reflect.Modifier.toString(field.getModifiers()))
                            .append(' ').append(field.getType().getSimpleName()).append(' ')
                            .append(field.getName()).append('\n');
                }
                for (java.lang.reflect.Constructor<?> constructor
                        : type.getDeclaredConstructors()) {
                    text.append("  C ").append(constructor).append('\n');
                }
                for (Method method : type.getDeclaredMethods()) {
                    text.append("  M ").append(java.lang.reflect.Modifier.toString(method.getModifiers()))
                            .append(' ').append(method.getReturnType().getSimpleName()).append(' ')
                            .append(method.getName()).append('(');
                    final Class<?>[] parameters = method.getParameterTypes();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i > 0) text.append(',');
                        text.append(parameters[i].getSimpleName());
                    }
                    text.append(")\n");
                }
            } catch (Throwable t) {
                text.append("  (").append(describe(t)).append(")\n");
            }
        }

        final byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final java.util.List<File> targets = new java.util.ArrayList<>();
        try {
            final File dir = new File("/storage/emulated/0/EdgeTX");
            if (dir.isDirectory()) {
                targets.add(new File(dir, "protocol-api.txt"));
            }
        } catch (Throwable ignored) {
            // the card is not there
        }
        try {
            final File dir = getExternalFilesDir(null);
            if (dir != null) {
                targets.add(new File(dir, "protocol-api.txt"));
            }
        } catch (Throwable ignored) {
            // no external directory
        }

        String written = null;
        boolean first = false;
        for (File file : targets) {
            try {
                first = !file.exists();
                final java.io.FileOutputStream out = new java.io.FileOutputStream(file);
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                if (written == null) {
                    written = file.getAbsolutePath();
                }
                Log.i(TAG, "rc protocol: API written to " + file);
            } catch (Throwable t) {
                Log.w(TAG, "rc protocol: could not write " + file + " - " + describe(t));
            }
        }
        if (written != null && first) {
            EdgeTxApplication.announce("协议 API 已写入 " + written);
        }
        return written;
    }

    /** The classes the listener is built from, in the order the reference implementation uses. */
    private static final String[] API_CLASSES = {
            "com.dji.protocol.ProtocolManager",
            "com.dji.protocol.PackFilter",
            "com.dji.protocol.PackRule",
            "com.dji.protocol.PackRule$Builder",
            "com.dji.protocol.Pack",
            "com.dji.protocol.IPackListener",
            "com.dji.protocol.ECode",
    };

    /**
     * Find where a received pack keeps its payload bytes.
     *
     * <p>The reference implementation reads a field called {@code data} on
     * {@code com.dji.protocol.Pack}, but that is one build of the library: on the RC Plus,
     * {@code Pack} declares <em>no</em> fields at all (measured - the field list came back empty
     * on screen), so the payload is either in a superclass or in the class the CREATOR actually
     * instantiates. Both are searched, by name first and then by type, and the runtime class is
     * what is looked at - not the static one.
     */
    private void findPayloadAccessor(Class<?> type) {
        for (String name : new String[] {"data", "mData", "payload", "mPayload", "bytes",
                                         "buffer", "content", "mContent"}) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    final Field field = c.getDeclaredField(name);
                    if (field.getType() == byte[].class) {
                        field.setAccessible(true);
                        mDataField = field;
                        Log.i(TAG, "rc protocol: the payload is " + c.getSimpleName() + '.'
                                + name + " (runtime class " + type.getName() + ')');
                        return;
                    }
                } catch (NoSuchFieldException ignored) {
                    // the next one up
                } catch (Throwable t) {
                    Log.w(TAG, "rc protocol: " + c.getSimpleName() + '.' + name
                            + " not usable - " + describe(t));
                }
            }
        }

        // Not under a name we know: any byte[] field of the class or its superclasses.
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() != byte[].class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    mDataField = field;
                    Log.i(TAG, "rc protocol: the payload is " + c.getSimpleName() + '.'
                            + field.getName() + " (runtime class " + type.getName() + ')');
                    return;
                } catch (Throwable ignored) {
                    // keep looking
                }
            }
        }

        // Or a getter that returns one.
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == byte[].class) {
                    try {
                        method.setAccessible(true);
                        mDataGetter = method;
                        Log.i(TAG, "rc protocol: the payload comes from " + c.getSimpleName() + '.'
                                + method.getName() + "() (runtime class " + type.getName() + ')');
                        return;
                    } catch (Throwable ignored) {
                        // keep looking
                    }
                }
            }
        }

        // Nothing usable: say what the class actually looks like, so the next build can read it.
        EdgeTxApplication.announce("协议: " + describePack(type));
        Log.w(TAG, "rc protocol: no payload accessor - " + describePack(type));
    }

    /** A compact one-line description of a pack class, for the screen. */
    private static String describePack(Class<?> type) {
        final StringBuilder text = new StringBuilder();
        text.append(type.getSimpleName());
        final Class<?> parent = type.getSuperclass();
        if (parent != null && parent != Object.class) {
            text.append('<').append(parent.getSimpleName());
        }
        text.append(" 字段:");
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                text.append(' ').append(field.getName()).append(':')
                        .append(field.getType().getSimpleName());
            }
        }
        text.append(" 方法:");
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                final Class<?> result = method.getReturnType();
                if (method.getParameterCount() == 0
                        && (result == byte[].class || result == int[].class
                            || result.getSimpleName().contains("Buffer"))) {
                    text.append(' ').append(method.getName()).append("():")
                            .append(result.getSimpleName());
                }
            }
        }
        return text.toString();
    }

    /** A static or instance call on one of DJI's classes, public or not. */
    private static Object call(Class<?> owner, Object target, String name, Class<?>[] types,
                               Object[] args) throws Exception {
        final Method method = method(owner, name, types);
        return method.invoke(target, args);
    }

    /**
     * The protocol manager, however this build names its way to it.
     *
     * <p>{@code getDefault()} is what DJI's newer libraries have - the RC Plus's has no such
     * method at all (measured), so the other names the same library has used are tried, and as a
     * last resort a static field of the manager's own type.
     */
    private static Object manager(Class<?> managerClass) throws Exception {
        final StringBuilder answeredNothing = new StringBuilder();
        for (String name : new String[] {"getDefault", "getInstance", "instance", "create",
                                         "getManager"}) {
            Method accessor;
            try {
                accessor = method(managerClass, name);
            } catch (NoSuchMethodException ignored) {
                continue;
            }
            final Object manager = accessor.invoke(null);
            if (manager != null) {
                Log.i(TAG, "rc protocol: the manager comes from " + name + "()");
                return manager;
            }
            answeredNothing.append(answeredNothing.length() == 0 ? "" : ", ")
                    .append(name).append("()");
        }
        // Whatever this build calls it: any static method that hands the manager back.
        for (Method accessor : managerClass.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(accessor.getModifiers())
                    || accessor.getParameterCount() != 0
                    || !managerClass.isAssignableFrom(accessor.getReturnType())) {
                continue;
            }
            try {
                accessor.setAccessible(true);
                final Object manager = accessor.invoke(null);
                if (manager != null) {
                    Log.i(TAG, "rc protocol: the manager comes from " + accessor.getName() + "()");
                    return manager;
                }
            } catch (Throwable ignored) {
                // the next one
            }
        }
        for (Field field : managerClass.getDeclaredFields()) {
            if (field.getType() == managerClass
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                final Object manager = field.get(null);
                if (manager != null) {
                    Log.i(TAG, "rc protocol: the manager is the static field " + field.getName());
                    return manager;
                }
            }
        }
        throw new NoSuchMethodException(answeredNothing.length() == 0
                ? managerClass.getName() + " has no instance"
                : managerClass.getName() + " answered null from " + answeredNothing);
    }

    private static Object call(Class<?> owner, Object target, String name) throws Exception {
        return call(owner, target, name, new Class<?>[0], new Object[0]);
    }

    /** {@code getMethod} only finds public members; these classes are not obliged to have them. */
    private static Method method(Class<?> owner, String name, Class<?>... types)
            throws NoSuchMethodException {
        Method method;
        try {
            method = owner.getMethod(name, types);
        } catch (NoSuchMethodException e) {
            method = owner.getDeclaredMethod(name, types);
        }
        method.setAccessible(true);
        return method;
    }

    /**
     * Hidden API is hidden even when the class is on the boot classpath, and this is what DJI's
     * own apps rely on.
     *
     * <p>It only works for a remote whose firmware does not enforce the restrictions on this
     * app's target SDK - the reference implementation targets 28, this one targets 33, and on
     * 33 the exemption cannot even be reached (its own lookup is refused). Both routes are
     * tried; {@link #openDjiClasses()} is the one that does not depend on the firmware.
     *
     * @return what happened, for the API dump
     */
    private static String exemptHiddenApis() {
        try {
            final Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            final Object instance = runtime.getDeclaredMethod("getRuntime").invoke(null);
            final Method exempt = runtime.getDeclaredMethod("setHiddenApiExemptions",
                    String[].class);
            exempt.invoke(instance, (Object) new String[] {"Lcom/dji/protocol/"});
            return "ok";
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: could not exempt DJI's hidden API - " + describe(t));
            return "failed - " + describe(t);
        }
    }

    /**
     * DJI's protocol classes, taken from a copy of the dex they live in.
     *
     * <p>The firmware keeps them on the boot class path, where an app targeting SDK 33 sees
     * every member of them filtered out of reflection: {@code ProtocolManager} came back with
     * <em>no</em> members at all and {@code Pack} with nothing but its {@code Parcelable}
     * overrides (measured). The same classes loaded from a dex in this app's own cache belong to
     * the application domain, and nothing is filtered there, so they are what the subscription
     * is built on when they can be had. Nothing is guessed: the dex is only copied when it
     * really contains the class, and the framework's own copy stays as the fallback.
     */
    private void openDjiClasses() {
        final java.util.List<File> dexes = new java.util.ArrayList<>();
        for (File jar : protocolJars()) {
            java.util.zip.ZipFile zip = null;
            try {
                zip = new java.util.zip.ZipFile(jar);
                final java.util.Enumeration<? extends java.util.zip.ZipEntry> entries =
                        zip.entries();
                while (entries.hasMoreElements()) {
                    final java.util.zip.ZipEntry entry = entries.nextElement();
                    final String name = entry.getName();
                    if (!name.startsWith("classes") || !name.endsWith(".dex")) {
                        continue;
                    }
                    if (!holds(zip, entry, PROTOCOL_PACKAGE)) {
                        continue;
                    }
                    final File dex = copyDex(zip, entry, jar.getName() + '.' + name);
                    if (dex != null) {
                        dexes.add(dex);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "rc protocol: cannot open " + jar + " - " + describe(t));
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (Throwable ignored) {
                        // nothing left to close
                    }
                }
            }
        }
        if (dexes.isEmpty()) {
            mSource = "the framework's own classes (no copy)";
            return;
        }
        mCopied.addAll(dexes);

        final StringBuilder path = new StringBuilder();
        for (File dex : dexes) {
            if (path.length() > 0) {
                path.append(File.pathSeparatorChar);
            }
            path.append(dex.getAbsolutePath());
        }
        try {
            final ClassLoader loader = copied(path.toString());
            Class.forName("com.dji.protocol.ProtocolManager", false, loader);
            mLoader = loader;
            mSource = path.toString();
            Log.i(TAG, "rc protocol: DJI's classes come from " + mSource);
        } catch (Throwable t) {
            mSource = "the framework's own classes (the copy would not load - " + describe(t) + ')';
            Log.w(TAG, "rc protocol: " + mSource);
        }
    }

    /** The package the copied classes are read for. */
    private static final String PROTOCOL_PACKAGE = "Lcom/dji/protocol/";

    /** The jars that can hold the protocol library: the framework, and anything DJI named. */
    private static java.util.List<File> protocolJars() {
        final java.util.List<File> jars = new java.util.ArrayList<>();
        jars.add(new File("/system/framework/framework.jar"));
        final File[] listed = new File("/system/framework").listFiles();
        if (listed != null) {
            for (File file : listed) {
                final String name = file.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".jar") && (name.contains("dji") || name.contains("protocol"))
                        && !jars.contains(file)) {
                    jars.add(file);
                }
            }
        }
        return jars;
    }

    /** Whether a dex holds a type descriptor - dex strings are plain ASCII, so bytes will do. */
    private static boolean holds(java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry,
                                 String text) {
        final byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final byte[] buffer = new byte[64 * 1024];
        java.io.InputStream in = null;
        try {
            in = zip.getInputStream(entry);
            int filled = 0;
            int read;
            while ((read = in.read(buffer, filled, buffer.length - filled)) > 0) {
                filled += read;
                if (filled < needle.length) {
                    continue;
                }
                for (int at = 0; at <= filled - needle.length; at++) {
                    int i = 0;
                    while (i < needle.length && buffer[at + i] == needle[i]) {
                        i++;
                    }
                    if (i == needle.length) {
                        return true;
                    }
                }
                // Keep the tail: a match may straddle two reads.
                System.arraycopy(buffer, filled - needle.length + 1, buffer, 0, needle.length - 1);
                filled = needle.length - 1;
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: cannot search " + entry.getName() + " - " + describe(t));
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing left to close
                }
            }
        }
    }

    /**
     * One dex out of a jar, into this app's cache - the same copy is reused next time.
     *
     * <p>The four protocol processes all do this at once and share one cache directory, so the
     * copy is written under a per-process name and only then renamed into place.
     */
    private File copyDex(java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry, String name) {
        final File target = new File(getCacheDir(), name);
        try {
            if (target.isFile() && target.length() == entry.getSize()) {
                return target;
            }
            // Loading code from a writable file is refused on newer releases.
            final File partial = new File(getCacheDir(),
                    name + '.' + android.os.Process.myPid() + ".part");
            final java.io.InputStream in = zip.getInputStream(entry);
            try {
                final java.io.FileOutputStream out = new java.io.FileOutputStream(partial);
                try {
                    final byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (!partial.renameTo(target)) {
                // Another process won the rename; its copy is as good as this one.
                if (target.isFile() && target.length() == entry.getSize()) {
                    partial.delete();
                    target.setReadOnly();
                    return target;
                }
                partial.setReadOnly();
                return partial;
            }
            target.setReadOnly();
            return target;
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: cannot copy " + entry.getName() + " - " + describe(t));
            return null;
        }
    }

    /**
     * A loader for the copied dexes that cannot reach the firmware's own copy of the classes.
     *
     * <p>A {@code DexClassLoader} asks its parent first, and the parent has these classes on the
     * boot class path - the answer would be the filtered copy again. The parent used here is one
     * that refuses exactly this package, so the loaders' own dexes are what answers, and every
     * other class still comes from the framework.
     */
    private ClassLoader copied(String path) {
        final ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        final ClassLoader refused = new ClassLoader(boot) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("com.dji.protocol.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        return new dalvik.system.DexClassLoader(path, getCacheDir().getAbsolutePath(), null,
                refused);
    }

    /**
     * One of DJI's protocol classes - from the copy when there is one, from the firmware else.
     *
     * <p>Once the copy is in use every class has to come from it: half a subscription built on
     * the copy and half on the filtered firmware classes would not fit together, so a class the
     * copy does not have is an error, not a fallback.
     */
    private Class<?> djiClass(String name) throws ClassNotFoundException {
        if (mLoader == null) {
            return Class.forName(name);
        }
        try {
            return Class.forName(name, false, mLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException(name + " is missing from the copy");
        }
    }

    // ------------------------------------------------------------------- the packet --

    /** One parceled {@code Pack}: the protocol manager wraps this binder in an AIDL proxy. */
    private void readPack(Parcel data) {
        try {
            onPackObject(mCreator.createFromParcel(data));
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: could not read a packet - " + describe(t));
        }
    }

    private void onPackObject(Object pack) {
        try {
            if (!mPayloadLooked && pack != null) {
                mPayloadLooked = true;
                // The runtime class, not Pack: on this library Pack declares no fields at all
                // and whatever the CREATOR instantiates is what carries them.
                findPayloadAccessor(pack.getClass());
            }
            final byte[] bytes = mDataField != null
                    ? (byte[]) mDataField.get(pack)
                    : mDataGetter != null ? (byte[]) mDataGetter.invoke(pack) : null;
            onPacket(bytes);
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: could not read a packet - " + describe(t));
        }
    }

    private void onPacket(byte[] bytes) {
        if (bytes == null || bytes.length < PACKET_LEN) {
            return;
        }
        if (!mFirstPacket) {
            mFirstPacket = true;
            Log.i(TAG, "rc protocol: the first packet arrived in " + mProcess);
            EdgeTxApplication.announce("协议已开始收数据 (" + mProcess + ")");
        }
        final Messenger sink = mSink;
        if (sink == null) {
            return;
        }

        // Bytes 0-3 are the button/switch field, one bit per control (see the class notes).
        final int bits = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8)
                | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 24);
        if (!mBitsKnown || bits != mLastBits) {
            mBitsKnown = true;
            mLastBits = bits;
            final Message message = Message.obtain(null, RcProtocolInputs.MSG_BUTTONS, bits, 0);
            message.setData(header());
            send(sink, message);
        }

        // The packet names the axes in its own order; these are the four sticks in the order
        // the firmware uses (left horizontal, left vertical, right horizontal, right vertical).
        final int[] sticks = {
                axis(bytes, 11),   // left horizontal
                axis(bytes, 9),    // left vertical
                axis(bytes, 5),    // right horizontal
                axis(bytes, 7),    // right vertical
        };
        if (mAny && Arrays.equals(sticks, mLast)) {
            return;
        }
        System.arraycopy(sticks, 0, mLast, 0, sticks.length);
        mAny = true;

        final Message message = Message.obtain(null, RcProtocolInputs.MSG_STICKS);
        final Bundle payload = header();
        payload.putIntArray(RcProtocolInputs.KEY_STICKS, sticks);
        message.setData(payload);
        send(sink, message);
    }

    /** Every message says which process it came from, so the app listens to one of the four. */
    private Bundle header() {
        final Bundle bundle = new Bundle();
        bundle.putString(KEY_PROCESS, mProcess);
        return bundle;
    }

    /** One message to the app process; a dead app process only costs the next packet. */
    private static void send(Messenger sink, Message message) {
        try {
            sink.send(message);
        } catch (Throwable t) {
            // The app process is gone; the binding is rebuilt when it is back.
        }
    }

    /** One 16-bit little-endian axis, relative to the centre the RC reports it around. */
    private static int axis(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8)) - AXIS_CENTRE;
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    // -------------------------------------------------------------- staying alive --

    /** This app's target SDK - it decides the hidden-API enforcement, so the dump says it. */
    private int targetSdk() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0)
                    .applicationInfo.targetSdkVersion;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** This process's name: the library looks at it, and the log has to say which one won. */
    private String processName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final String name = Application.getProcessName();
                if (name != null) {
                    return name;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: could not read the process name", t);
        }
        return getPackageName();
    }
}
