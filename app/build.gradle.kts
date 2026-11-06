plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }
android {
 ndkVersion = "27.0.12077973"
 namespace = "com.rtspmonitor.app"
 compileSdk { version = release(37) }
 defaultConfig { minSdk = 29; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += listOf("arm64-v8a", "x86_64") }; applicationId = "com.rtspmonitor.app"; targetSdk = 37; versionCode = 7; versionName = "0.7.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
}
dependencies { implementation(libs.room); implementation(libs.coroutines)
testImplementation(libs.junit)
implementation(project(":core"))
implementation(project(":data")); implementation(project(":media"))
implementation(platform(libs.compose.bom)); implementation(libs.compose.material3); implementation(libs.compose.ui); implementation(libs.activity); implementation(libs.lifecycle)
 }

dependencies { androidTestImplementation("androidx.test.ext:junit:1.2.1"); androidTestImplementation("androidx.test:runner:1.6.2") }
