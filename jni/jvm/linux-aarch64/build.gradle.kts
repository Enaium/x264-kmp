/*
 * Per-OS/arch JNI artifact: linux-aarch64.
 * Ships libx264_jni.so as a classpath resource at
 * /cn/enaium/x264/native/linux-aarch64/, which NativeLoader
 * (in :x264-kmp's jvmMain) extracts and System.load()s at runtime.
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val jniOs = "linux"
val jniArch = "aarch64"
val classifier = "$jniOs-$jniArch"
val libFile = "libx264_jni.so"
val resourceDir = "cn/enaium/x264/native/$classifier"

val canBuildHere = OperatingSystem.current().isLinux

val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary = tasks.register<Exec>("configureJniLibrary") {
    group = "build"
    description = "cmake-configures x264_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniInclude = if (javaHome.isNotEmpty()) "$javaHome/include" else ""
    // A runner that is not itself aarch64 needs the cross toolchain CI installs;
    // without the flags CMake would write a host-arch binary into an artifact
    // that promises another architecture.
    val crossFlags = if (System.getProperty("os.arch").lowercase() in listOf("aarch64", "arm64")) {
        emptyList()
    } else {
        listOf(
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            "-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc",
        )
    }
    commandLine(
        listOf(
            cmakeExecutable,
            rootProject.file("jni").absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_JNI=ON",
            "-DJNI_INCLUDE_DIR=$jniInclude",
            "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/linux",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outDir.absolutePath}",
            "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${outDir.absolutePath}",
        ) + crossFlags,
    )
}

val buildJniLibrary = tasks.register<Exec>("buildJniLibrary") {
    group = "build"
    description = "Builds $libFile for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--target", "x264_jni", "--config", "Release")
    inputs.files(
        rootProject.file("jni/CMakeLists.txt"),
        rootProject.file("jni/config.h.in"),
        rootProject.file("jni/x264_config.h.in"),
        rootProject.file("jni/x264_kmp.c"),
        rootProject.file("jni/include/x264_kmp.h"),
        rootProject.file("jni/x264_jni.c"),
    )
    inputs.file(rootProject.file("x264/x264.h"))
    inputs.dir(rootProject.file("x264/common"))
    inputs.dir(rootProject.file("x264/encoder"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    // Copy the archive through its provider instead of the task's output
    // collection: the latter is filtered by an include pattern and was empty
    // on the Windows runner, which silently produced a jar without the DLL.
    from(nativeOutputDir.map { it.file(libFile) }) {
        into(resourceDir)
    }
}

val hasSigningKey = rootProject.extra["hasSigningKey"] as Boolean

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (hasSigningKey) signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "x264-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("x264-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI shared library for x264-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by NativeLoader; not intended to be depended on directly.",
        )
        url.set("https://github.com/Enaium/x264-kmp")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("GPL-2.0-or-later")
                url.set("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Enaium")
            }
        }
        scm {
            url.set("https://github.com/Enaium/x264-kmp")
            connection.set("scm:git:git@github.com:Enaium/x264-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/x264-kmp.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/x264-kmp/issues")
        }
    }
}
