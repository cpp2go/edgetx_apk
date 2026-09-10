plugins {
    id("com.android.application")
}

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

        externalNativeBuild {
            cmake {
                // EdgeTX-like target uses C++17. Keep exceptions/RTTI on for Lua.
                cppFlags += listOf("-std=c++17", "-fexceptions", "-Wall")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

        ndk {
            // arm64 for real hardware, x86_64 for the local emulator, and
            // armeabi-v7a because many phones report only 32-bit ARM
            // (64-bit kernel with a 32-bit userspace).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
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
val simuAbis = ((findProperty("edgetx.abis") as String?) ?: "armeabi-v7a,arm64-v8a,x86_64")
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

tasks.named("preBuild") { dependsOn(buildEdgeTxSimulator) }
