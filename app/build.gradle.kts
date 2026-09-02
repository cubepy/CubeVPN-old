import java.util.Properties
import java.util.Base64

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

fun localProp(key: String): String = localProps.getProperty(key, "")

/**
 * A white-label value, in precedence order:
 *
 *  1. `-PBRAND_X_B64=<base64 of UTF-8>` — the encoding-proof channel, and the one the build
 *     service should use for anything non-ASCII. A brand name in Persian cannot be passed
 *     safely as a plain `-P`: the Gradle client decodes argv with the platform charset, which
 *     is not UTF-8 in a bare container, and the name arrives as replacement characters.
 *  2. `-PBRAND_X=` — fine for ASCII (package names, Telegram handles).
 *  3. `secrets.properties` — for local builds.
 *  4. CubeVPN's own value, so a plain checkout builds the CubeVPN app unchanged.
 *
 * Presence beats emptiness at every level: `-PBRAND_TON_WALLET=` explicitly clears the wallet
 * rather than falling through to CubeVPN's, which is how a reseller who takes no TON donations
 * turns that card off.
 */
fun brandProp(key: String, fallback: String): String {
    val b64Key = "${key}_B64"
    if (project.hasProperty(b64Key)) {
        val raw = (project.property(b64Key) as String).trim()
        if (raw.isNotEmpty()) {
            return String(Base64.getDecoder().decode(raw), Charsets.UTF_8).trim()
        }
        return ""
    }
    if (project.hasProperty(key)) return (project.property(key) as String).trim()
    if (secrets.containsKey(key)) return secrets.getProperty(key).trim()
    return fallback
}

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
    // Read as UTF-8 explicitly: Properties.load(InputStream) decodes ISO-8859-1, which turns a
    // Persian brand name into mojibake.
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
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
        // Every brand needs its own package name so a reseller's app installs alongside
        // another's rather than replacing it. Overridden per build; defaults to CubeVPN.
        applicationId = brandProp("BRAND_APPLICATION_ID", "net.cubevpn.app")
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DONATION_CARD_NUMBER", bcString(localProp("DONATION_CARD_NUMBER")))
        buildConfigField("String", "DONATION_CARD_HOLDER", bcString(localProp("DONATION_CARD_HOLDER")))

        // These three differ per brand as much as the name does — a reseller's app has to talk
        // to their panel, seed their free configs and update from their channel — so they take
        // a -P override like the BRAND_* values, falling back to secrets.properties.
        buildConfigField("String", "DEFAULT_SUB_URL", bcString(brandProp("DEFAULT_SUB_URL", "")))
        // Base URL of the account API (see docs/api-contract.md).
        buildConfigField("String", "API_BASE_URL", bcString(brandProp("API_BASE_URL", "")))
        // "owner/repo" for the About screen's update checker. Leave blank to disable.
        buildConfigField("String", "UPDATE_REPO", bcString(brandProp("UPDATE_REPO", "")))
        // A brand's own update feed (its panel). Takes precedence over UPDATE_REPO; see
        // UpdateChecker for the JSON shape.
        buildConfigField("String", "UPDATE_URL", bcString(brandProp("UPDATE_URL", "")))

        // White-label identity. See Brand.kt — user-facing copy uses placeholders, never these
        // values directly. Blank falls back to CubeVPN's own, so a plain checkout is unchanged.
        val brandAppName = brandProp("BRAND_APP_NAME", "CubeVPN")
        buildConfigField("String", "BRAND_APP_NAME", bcString(brandAppName))
        buildConfigField("String", "BRAND_BOT", bcString(brandProp("BRAND_BOT", "cubevvpn_bot")))
        buildConfigField("String", "BRAND_SUPPORT", bcString(brandProp("BRAND_SUPPORT", "cube_sup")))
        buildConfigField("String", "BRAND_CHANNEL", bcString(brandProp("BRAND_CHANNEL", "cube_vpnn")))
        buildConfigField(
            "String", "BRAND_TON_WALLET",
            bcString(brandProp("BRAND_TON_WALLET", "UQBP3uD9kE9UgTWrH2BiVDmurQZOvVl8awinVzqnBmenUQUq"))
        )

        // A promotional build that stops working on its own, as "yyyy-MM-dd". Blank — every
        // ordinary build — never expires. This is deliberately baked in rather than checked
        // against a server: a trial handed to one reseller isn't worth a service to run, and a
        // build with nothing to phone home to can't be broken by our own downtime.
        buildConfigField("String", "BRAND_EXPIRES_AT", bcString(brandProp("BRAND_EXPIRES_AT", "")))

        // Identifies which brand is calling, for a build that talks to the shared platform
        // rather than to a panel of its own. Sent as a header on every account request; blank
        // for CubeVPN's own build, whose API_BASE_URL already points somewhere unambiguous.
        buildConfigField("String", "BRAND_KEY", bcString(brandProp("BRAND_KEY", "")))

        // Where the app asks whether this brand is still licensed. Blank — CubeVPN's own build,
        // and any reseller on our panel, whose app is already gated by the panel it talks to —
        // means the question is never asked. See License.kt.
        buildConfigField("String", "LICENSE_URL", bcString(brandProp("LICENSE_URL", "")))

        // The launcher label and the widget picker's description can't read BuildConfig, so
        // they're generated as resources here instead of living in res/values/strings.xml.
        resValue("string", "app_name", brandAppName)
        resValue("string", "widget_description", "Connect or disconnect $brandAppName without opening the app")
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
        // app_name / widget_description are generated per brand in defaultConfig.
        resValues = true
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

// The brand's name as it appears in the APK's file name. Kept separate from BRAND_APP_NAME
// because the launcher label may be Persian, and a download whose file name is Persian is
// awkward to hand around; the build service passes a plain ASCII slug like "NovaVPN".
val brandFileSlug: String = brandProp("BRAND_SLUG", "CubeVPN")
    .replace(Regex("[^A-Za-z0-9._-]"), "")
    .ifEmpty { "app" }

// Name built APKs "<Brand>-v<version>-<abi|universal>-<buildType>.apk" instead of AGP's
// default "app-arm64-v8a-release.apk", so a release asset (and what a browser offers to
// save it as) reads like a real build of that brand, not a generic filename.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                val abi = output.filters
                    .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                    ?.identifier
                    ?: "universal"
                output.outputFileName.set("$brandFileSlug-v$appVersionName-$abi-${variant.buildType}.apk")
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