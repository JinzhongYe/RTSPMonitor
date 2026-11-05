plugins { alias(libs.plugins.android.library) }
android {
 ndkVersion = "27.0.12077973"
 namespace = "com.rtspmonitor.media"
 compileSdk { version = release(37) }
 defaultConfig { minSdk = 29; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner";  }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 
}
dependencies { implementation(libs.coroutines)
testImplementation(libs.junit)
implementation(project(":core"))
implementation(libs.vlc)
 }

val buildNative by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("bash", "tools/build-native.sh")
    inputs.files(rootProject.file("tools/build-native.sh"), file("src/main/cpp/recorder.c"))
    outputs.files(file("src/main/jniLibs/arm64-v8a/librtsp_recorder.so"), file("src/main/jniLibs/x86_64/librtsp_recorder.so"))
}
tasks.named("preBuild") { dependsOn(buildNative) }

dependencies { androidTestImplementation("androidx.test.ext:junit:1.2.1"); androidTestImplementation("androidx.test:runner:1.6.2") }

dependencies {
    androidTestImplementation(project(":data"))
    androidTestImplementation(libs.room)
}
