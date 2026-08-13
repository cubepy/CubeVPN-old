import java.util.Properties
import java.io.FileInputStream

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

fun localProp(key: String): String = localProps.getProperty(key, "")

fun bcString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c.code in 32..126 -> append(c)
            else -> append("\\u%04x".format(c.code))
        }
    }
    append('"')
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) load(FileInputStream(f))
}

// CI passes -PreleaseVersionName=<tag without the leading v> when building off a `vX.Y.Z`
// git tag (see .github/workflows/release.yml), so a tagged release's version metadata always
// matches the tag instead of relying on someone remembering to bump this file by hand — a
// mismatch here is exactly why a past "v1.2.1" release still installed/showed as 1.2.0.
val releaseVersionName = (findProperty("releaseVersionName") as String?)?.trim()?.takeIf { it.isNotEmpty() }
val appVersionName = releaseVersionName ?: "1.2.0"

fun versionCodeFromName(name: String): Int {
    val parts = name.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 1_000_000 + minor * 1_000 + patch
}
val appVersionCode = releaseVersionName?.let(::versionCodeFromName) ?: 4

android {
    namespace = "net.cubevpn.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "net.cubevpn.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SUB_URL", "\"${secrets.getProperty("DEFAULT_SUB_URL", "")}\"")
        buildConfigField("String", "DONATION_CARD_NUMBER", bcString(localProp("DONATION_CARD_NUMBER")))
        buildConfigField("String", "DONATION_CARD_HOLDER", bcString(localProp("DONATION_CARD_HOLDER")))
        // Base URL of the CubeVPN account API (see docs/api-contract.md). Set in secrets.properties.
        buildConfigField("String", "API_BASE_URL", bcString(secrets.getProperty("API_BASE_URL", "")))
        // "owner/repo" for the About screen's update checker. Leave blank to disable.
        buildConfigField("String", "UPDATE_REPO", bcString(secrets.getProperty("UPDATE_REPO", "")))
    }

    signingConfigs {
        create("release") {
            val kf = System.getenv("KEYSTORE_FILE")
            if (kf != null) {
                storeFile = file(kf)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // Also build a single "universal" APK bundling both ABIs, for devices/stores
            // that need one file instead of picking a per-ABI split.
            isUniversalApk = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Name built APKs "CubeVPN-v<version>-<abi|universal>-<buildType>.apk" instead of AGP's
// default "app-arm64-v8a-release.apk", so a GitHub release asset (and what a browser
// offers to save it as) reads like a real CubeVPN build, not a generic filename.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                val abi = output.filters
                    .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                    ?.identifier
                    ?: "universal"
                output.outputFileName.set("CubeVPN-v$appVersionName-$abi-${variant.buildType}.apk")
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(files("libs/gozarcore.aar"))
    implementation("androidx.compose.material:material-icons-extended")
    implementation("dev.chrisbanes.haze:haze:1.6.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation(libs.junit)
    // org.json ships with Android but is stubbed out in unit tests; the real implementation lets
    // JsonSubscription be tested on the JVM against actual panel payloads.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}