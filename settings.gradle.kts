import java.io.File
import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "banread-android"

include(":core-model")

fun detectAndroidSdkDir(rootDir: File): File? {
    val envHome = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
    if (envHome != null) {
        val dir = File(envHome)
        if (dir.isDirectory) return dir
    }
    val localPropsFile = File(rootDir, "local.properties")
    if (localPropsFile.isFile) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        val sdkDirProp = props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
        if (sdkDirProp != null) {
            val dir = File(sdkDirProp)
            if (dir.isDirectory) return dir
        }
    }
    return null
}

val androidSdkDir = detectAndroidSdkDir(rootDir)
if (androidSdkDir != null) {
    println("[settings.gradle.kts] Android SDK detected at ${androidSdkDir.absolutePath}; including Android modules.")
    include(
        ":module-library",
        ":module-reader",
        ":module-companion",
        ":module-ai",
        ":module-sync",
        ":module-viz",
        ":app"
    )
} else {
    println(
        "[settings.gradle.kts] Android SDK not found (no ANDROID_HOME, ANDROID_SDK_ROOT, or local.properties sdk.dir). " +
            "Including :core-model only; point ANDROID_HOME or sdk.dir to enable Android modules."
    )
}
