plugins { alias(libs.plugins.android.library); alias(libs.plugins.legacy.kapt) }
android {
 namespace = "com.rtspmonitor.data"
 compileSdk { version = release(37) }
 defaultConfig { minSdk = 29; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner";  }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 
}
dependencies { implementation(libs.coroutines)
testImplementation(libs.junit)
implementation(project(":core"))
implementation(libs.room); implementation(libs.room.ktx); kapt(libs.room.compiler)
 }

dependencies { androidTestImplementation("androidx.test.ext:junit:1.2.1"); androidTestImplementation("androidx.test:runner:1.6.2") }
