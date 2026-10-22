plugins { id("com.android.application") }

android {
    namespace = "app.drizziq"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "app.drizziq"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "app.drizziq.PhoneExercise"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes { release { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // DWD RV is ODIM HDF5; decoding this format warrants a maintained reader.
    implementation("io.jhdf:jhdf:0.8.6")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
