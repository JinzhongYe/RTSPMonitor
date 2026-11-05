plugins { alias(libs.plugins.android.library) }
android {
 namespace = "com.rtspmonitor.core"
 compileSdk { version = release(37) }
 defaultConfig { minSdk = 29;  }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 
}
dependencies { implementation(libs.coroutines)
testImplementation(libs.junit)
 }
