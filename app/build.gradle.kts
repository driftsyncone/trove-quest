import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.meta.spatial.plugin)
}

// Version: version.properties (see the comment there and README "Updates").
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appVersionCode =
    (findProperty("trove.versionCode") as String?)?.toInt() ?: versionProps.getProperty("versionCode").toInt()
val appVersionName = (findProperty("trove.versionName") as String?) ?: versionProps.getProperty("versionName")

// Release signing from the environment or a git-ignored keystore.properties (never committed).
// Updates only install over a build signed with the same key, see README "Signing".
val keystoreProps =
    Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

fun signing(env: String, key: String): String? = System.getenv(env) ?: keystoreProps.getProperty(key)

val releaseStoreFile = signing("TROVE_KEYSTORE", "storeFile")

android {
  namespace = "app.trove.quest"
  compileSdk = 36
  compileSdkMinor = 1

  defaultConfig {
    applicationId = "app.trove.quest"
    // Horizon OS is Android 14 (API 34); Meta's Spatial SDK samples pin min and target to 34.
    minSdk = 34
    //noinspection OldTargetApi,ExpiredTargetSdkVersion
    targetSdk = 34
    versionCode = appVersionCode
    versionName = appVersionName
    // Quest headsets are arm64 only; drops the other ABIs' copies of bundled native libs.
    ndk { abiFilters += "arm64-v8a" }
  }

  packaging { resources.excludes.add("META-INF/LICENSE*") }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }

  signingConfigs {
    if (releaseStoreFile != null) {
      create("release") {
        storeFile = rootProject.file(releaseStoreFile)
        storePassword = signing("TROVE_KEYSTORE_PASSWORD", "storePassword")
        keyAlias = signing("TROVE_KEY_ALIAS", "keyAlias")
        keyPassword = signing("TROVE_KEY_PASSWORD", "keyPassword")
      }
    }
  }

  buildTypes {
    release {
      // Unsigned (not installable) unless a release keystore is configured.
      if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    // What scripts/publish-apk.sh ships (README "Publishing"): release-like (not debuggable, so Compose
    // and ART run at full speed, BuildConfig.DEBUG = false) but signed with the SAME debug key the earlier
    // published debug builds used, so installed copies update in place. Same applicationId and version
    // properties. No R8 yet (a separate decision; minify stays off).
    create("publish") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      isDebuggable = false
      isJniDebuggable = false
      isMinifyEnabled = false
      isShrinkResources = false
      matchingFallbacks += listOf("release")
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)

  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.isdk)

  implementation(libs.media3.exoplayer)
  implementation(libs.media3.datasource.okhttp)
  // HLS transcodes from the server (play-tracking branch: /media/scene/<id>/stream.m3u8), seekable unlike mp4.
  implementation(libs.media3.exoplayer.hls)
  implementation(libs.okhttp)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.zxing.core) // QR code for device pairing (pure Java, no Android deps)

  testImplementation(libs.junit)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.kotlinx.coroutines.test)
}

spatial {
  // No Spatial Editor scenes: everything is built in code, so there is no export step
  // (and the Spatial Editor CLI does not exist for Linux).
  allowUsageDataCollection.set(false)
}
