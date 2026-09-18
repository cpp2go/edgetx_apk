import java.util.Properties

plugins {
    id("com.android.application")
}

// ---------------------------------------------------------------------------
// DJI Mobile SDK (optional)
//
// The RC Plus 2's 5-way switch directions are reported as ABS_HAT0X/HAT0Y motion
// events, which the RC firmware never dispatches to apps, so Android's input
// layer cannot see them. The DJI Mobile SDK is the only documented API that knows
// about them, so it is wired up here as an opt-in extra.
//
//   local.properties:
//       dji.msdk=true          -> package the 132 MB SDK (default: off)
//       dji.appKey=<App Key>   -> from developer.dji.com, bound to the package
//                                 name + signing certificate fingerprint
//
// With the flag off, the build and the resulting APK are unchanged.
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val djiMsdkEnabled = ((project.findProperty("dji.msdk") as String?) ?: localProps.getProperty("dji.msdk") ?: "false").toBoolean()
val djiMsdkVersion = (project.findProperty("dji.msdkVersion") as String?) ?: localProps.getProperty("dji.msdkVersion") ?: "5.18.0"
val djiAppKey = (project.findProperty("dji.appKey") as String?) ?: localProps.getProperty("dji.appKey") ?: ""

// ---------------------------------------------------------------------------
// Target ABI
//
// Only the DJI RC Plus 2 (rc701) is supported, and it is 64-bit ARM. Building a
// single ABI keeps the APK small - the DJI SDK alone contributes 60+ shared
// objects per ABI. Override when an emulator build is needed:
//
//     ./gradlew :app:assembleRelease -Pabis=arm64-v8a,x86_64
// ---------------------------------------------------------------------------
val targetAbis = ((findProperty("abis") as String?) ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.edgetx.droidui"
    compileSdk = 36

    // Installed NDK version (see local NDK directory).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.edgetx.droidui"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

        // The DJI Mobile SDK reads its App Key from this manifest meta-data
        // (SDKManager.registerApp() takes no argument). Empty when MSDK is unused.
        manifestPlaceholders["djiAppKey"] = djiAppKey

        externalNativeBuild {
            cmake {
                // EdgeTX-like target uses C++17. Keep exceptions/RTTI on for Lua.
                cppFlags += listOf("-std=c++17", "-fexceptions", "-Wall")
                arguments += listOf("-DANDROID_STL=c++_static")

                // Says that this build has the DJI SDK in it: the firmware's start then
                // waits for the SDK to report the RC's controls before EdgeTX boots, so
                // its boot checks see real stick and switch positions (see
                // joystick.cpp: inputsReady()). Without the SDK there is nothing to wait
                // for and the gate is compiled out.
                if (djiMsdkEnabled) cppFlags += "-DEDGETX_MSDK=ON"
            }
        }

        ndk {
            // The RC Plus 2 is arm64-v8a only (see targetAbis above).
            abiFilters += targetAbis
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug keystore so the release APK can be installed
            // directly on a phone. Swap in a real keystore for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // The DJI SDK contributes ~300 MB of shared objects for a single ABI.
            // AGP stores native libraries uncompressed by default from API 24 on,
            // which makes the APK enormous. Compressing them (this is what
            // extractNativeLibs="true" means) roughly halves it, and the DJI docs
            // ask for extractNativeLibs="true" from MSDK 5.17.0 anyway.
            useLegacyPackaging = true

            // The SDK only ships as one 132 MB blob, but this app uses nothing
            // beyond the Key-Value API (sticks, buttons). Everything below was
            // removed one group at a time and the app re-run to confirm
            // SDKManager.init(), registerApp() and the stick/button listeners all
            // still work. 168.5 MB -> 111.1 MB.
            //
            // Measured boundary - these look removable but are NOT, each one was
            // tried and made SDKManager.init() throw UnsatisfiedLinkError:
            //   libFlightRecordEngine.so  DT_NEEDED of libdjisdk_jni.so, so
            //                             dropping it breaks the Key-Value JNI
            //                             (native_get_sync) even though we never
            //                             use flight records
            //   libDJIUpgradeCore.so      + libDJIUpgradeJNI.so - loaded by
            //                             CSDKManager.setServerUrlMode() at init
            //   libsqlcipher.so           System.loadLibrary("sqlcipher") at init
            //   libDJIFlySafeCore-CSDK.so loaded at init
            //
            // Before adding anything here, check what links against it:
            //   llvm-readelf -d <lib>.so | grep NEEDED
            // A missing dependency is only fatal if the depending library is
            // actually dlopen'ed - that is why the ffmpeg group can go even though
            // the (never loaded) libDJIOpus.so needs it.
            excludes += listOf(
                // Live streaming: Agora, private RTMP, WebRTC, RTSP, NDI.
                "**/libagora-*.so",
                "**/libmrtc_*.so",
                "**/libndi.so",
                "**/libopuspilot.so",
                // FFmpeg, used only to decode/encode those streams.
                "**/libavcodec.so",
                "**/libavdevice.so",
                "**/libavfilter.so",
                "**/libavformat.so",
                "**/libavresample.so",
                "**/libswresample.so",
                "**/libswscale.so",
                // DJI Cloud API: nothing in the init path touches it.
                "**/libcloud_access_jni.so",
                // Waypoint missions: a separate manager the Key-Value API never
                // instantiates; only libdjiwpv2-CSDK.so links against it, and that
                // is never loaded either.
                "**/libwpmz_jni.so",
                "**/libDJIWaypointV2Core-CSDK.so",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// EdgeTX simulator library
//
// The UI is the real EdgeTX firmware, built as a shared library by the EdgeTX
// CMake project and packaged next to our thin host. Override the locations with
// -Pedgetx.dir=... / -Pedgetx.abis=... if your checkout differs.
// ---------------------------------------------------------------------------
val edgetxDir = (findProperty("edgetx.dir") as String?) ?: "E:/develop/dev/edgetx_esp32"
val sdkDir = (findProperty("sdk.dir") as String?)
    ?: System.getenv("ANDROID_HOME")
    ?: "E:/develop/android-sdk"
val ndkDir = "$sdkDir/ndk/28.2.13676358"
val cmakeExe = "$sdkDir/cmake/3.22.1/bin/cmake.exe"
val simuPcb = (findProperty("edgetx.pcb") as String?) ?: "TX16SMK3"
val simuName = (findProperty("edgetx.simuName") as String?) ?: "st16mk3"
val simuLibName = "libedgetx-$simuName-simulator.so"
val simuAbis = ((findProperty("edgetx.abis") as String?) ?: targetAbis.joinToString(","))
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

// Python interpreter for EdgeTX's build-time code generators (pydantic +
// libclang). Defaults to the virtualenv inside the EdgeTX checkout.
val simuPython = (findProperty("edgetx.python") as String?)

// Where the staged per-ABI libraries live (packaged as jniLibs).
val simuLibsDir = layout.buildDirectory.dir("edgetx-libs")
android {
    // `srcDir(Any)` is deprecated in AGP 9; `directories` is the supported API
    // and takes a path string.
    sourceSets.getByName("main").jniLibs.directories.add(simuLibsDir.get().asFile.absolutePath)
}

fun runCommand(what: String, cmd: String, args: List<String>, extraPath: String) {
    // Run through cmd.exe so the parent environment (SystemRoot, ...) is inherited;
    // only PATH is extended (libclang for find_clang.py, ninja from the SDK CMake).
    val line = "set \"PATH=" + extraPath + ";%PATH%\" && \"" + cmd + "\" " +
        args.joinToString(" ")
    println("> $what")

    // Note: the Gradle daemon's stdio is not the client's console, so capture the
    // child output ourselves and echo it back through Gradle's logger.
    val logFile = File(
        project.layout.buildDirectory.get().asFile,
        "edgetx-build/${what.replace(Regex("[^A-Za-z0-9]+"), "_")}.log"
    )
    logFile.parentFile.mkdirs()

    val pb = ProcessBuilder("cmd.exe", "/c", line)
    pb.redirectErrorStream(true)
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile))
    val code = pb.start().waitFor()

    if (code != 0) {
        println(logFile.readText().takeLast(6000))
        throw GradleException("$what failed with exit code $code")
    }
}

val buildEdgeTxSimulator = tasks.register("buildEdgeTxSimulator") {
    group = "edgetx"
    description = "Builds the EdgeTX simulator shared library for each ABI."

    val libsDir = simuLibsDir.get().asFile
    outputs.dir(libsDir)
    // The inner CMake/Ninja build is incremental, so always invoking it is cheap;
    // this also avoids Gradle skipping the task and leaving an ABI unbuilt.
    outputs.upToDateWhen { false }

    doLast {
        val python = simuPython ?: File(edgetxDir, ".venv/Scripts/python.exe").absolutePath
        // find_clang.py locates libclang through PATH on Windows.
        val clangNative = File(edgetxDir, ".venv/Lib/site-packages/clang/native").absolutePath
        // The SDK's CMake package also ships ninja; the Gradle daemon's PATH may
        // not have it, so make both explicit.
        val cmakeBinDir = File(cmakeExe).parentFile.absolutePath
        val ninjaExe = File(cmakeBinDir, "ninja.exe").absolutePath
        val extraPath = listOf(clangNative, cmakeBinDir).joinToString(";")

        for (abi in simuAbis) {
            val buildDir = File(layout.buildDirectory.get().asFile, "edgetx-build/$abi")
            buildDir.parentFile.mkdirs()

            runCommand(
                "configure EdgeTX ($abi)", cmakeExe,
                listOf(
                    "-S", edgetxDir,
                    "-B", buildDir.absolutePath,
                    "-G", "Ninja",
                    "-DCMAKE_TOOLCHAIN_FILE=$ndkDir/build/cmake/android.toolchain.cmake",
                    "-DCMAKE_MAKE_PROGRAM=$ninjaExe",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-24",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DPCB=$simuPcb",
                    "-DNATIVE_BUILD=1",
                    "-DEdgeTX_SUPERBUILD=0",
                    "-DDISABLE_COMPANION=1",
                    "-DTRANSLATIONS=EN",
                    "-DLUA=ON",
                    "-DANDROID_SIMU_NAME=$simuName",
                    "-DPython3_EXECUTABLE=$python",
                ), extraPath
            )

            runCommand(
                "build EdgeTX ($abi)", cmakeExe,
                listOf("--build", buildDir.absolutePath, "--target", "edgetx_simu_core"), extraPath
            )

            val built = File(buildDir, simuLibName)
            if (!built.isFile) throw GradleException("Expected library not found: $built")

            // Drop the debug info: ~99 MB -> ~14 MB (the library is normally
            // built with -g and nothing here needs symbols at runtime).
            val stripExe = File(
                ndkDir,
                "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
            ).absolutePath
            if (File(stripExe).isFile) {
                runCommand("strip EdgeTX ($abi)", stripExe, listOf("--strip-debug", built.absolutePath), extraPath)
            }

            val abiOut = File(libsDir, abi).apply { mkdirs() }
            built.copyTo(File(abiOut, simuLibName), overwrite = true)
            println("staged $abi -> ${File(abiOut, simuLibName)}")
        }

    }
}

// ---------------------------------------------------------------------------
// Reusing an already built simulator library
//
// The EdgeTX checkout only builds on a machine that carries the Android patches
// (see README). With -Pedgetx.skipBuild=true the task above is not run and
// whatever is already staged in build/edgetx-libs/<abi>/ is packaged as is:
//
//     gradlew :app:assembleDebug -Pedgetx.skipBuild=true
// ---------------------------------------------------------------------------
val skipEdgeTxBuild = ((findProperty("edgetx.skipBuild") as String?) ?: "false").toBoolean()

if (skipEdgeTxBuild) {
    logger.lifecycle("EdgeTX build skipped: packaging the libraries staged in ${simuLibsDir.get().asFile}")
} else {
    tasks.named("preBuild") { dependsOn(buildEdgeTxSimulator) }
}

// ---------------------------------------------------------------------------
// DJI Mobile SDK dependencies
//
// The `-provided` artifact is a compile-only stub set, so DjiMsdkBridge.java
// always compiles. The real SDK (a 132 MB AAR carrying the native libraries) is
// only packaged when dji.msdk=true.
// ---------------------------------------------------------------------------
dependencies {
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:$djiMsdkVersion")

    // RF module on a USB serial port. Supports CDC-ACM and the usual
    // USB-serial bridges (FTDI, CP210x, CH34x, PL2303), which is what a module
    // or a module adapter presents to the RC.
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    if (djiMsdkEnabled) {
        implementation("com.dji:dji-sdk-v5-aircraft:$djiMsdkVersion")
        // The SDK's analytics module probes permissions through
        // androidx.core.app.ActivityCompat. This app is pure native and depends on
        // no AndroidX of its own, so without this the SDK dies with a
        // NoClassDefFoundError as soon as it is initialised.
        implementation("androidx.core:core:1.13.1")
    }
}
