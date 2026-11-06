plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose"); id("app.cash.paparazzi") }
android {
 namespace = "com.internetdeco.decocontrol"
 compileSdk { version = release(37) { minorApiLevel = 0 } }
 buildToolsVersion = "36.0.0"
 defaultConfig {
  applicationId = "com.internetdeco.decocontrol"
  minSdk = 26; targetSdk = 36; versionCode = 6; versionName = "1.1.1"
  ndk { abiFilters += "arm64-v8a" }
 }
 signingConfigs {
  create("stable") {
   storeFile = file(System.getenv("ID_SIGNING_STORE_FILE") ?: "release-signing.jks")
   keyAlias = System.getenv("ID_SIGNING_KEY_ALIAS") ?: "internetdeco"
   storePassword = System.getenv("ID_SIGNING_PASSWORD")
   keyPassword = System.getenv("ID_SIGNING_PASSWORD")
  }
 }
 buildTypes {
  release { isMinifyEnabled = false; isShrinkResources = false; signingConfig = signingConfigs.getByName("stable"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true; buildConfig = true }
 packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA"); jniLibs.useLegacyPackaging = false }
 testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.09.00"))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3:1.5.0-beta02")
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.core:core-ktx:1.19.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
 implementation("com.github.mwiede:jsch:2.28.0")
 implementation("org.bouncycastle:bcprov-jdk18on:1.84")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach { maxHeapSize="768m";maxParallelForks=1 }
