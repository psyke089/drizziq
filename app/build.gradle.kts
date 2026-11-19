plugins { id("com.android.application") }

// Signing values come from ~/.gradle/gradle.properties (drizziq.*) or the CI environment.
// The dedicated release key lives at ~/.android/keys/drizziq/drizziq-release.jks. It replaced
// the old public debug identity, so installs from before the rotation need one reinstall.
fun signingValue(property: String, env: String): String? =
    providers.gradleProperty(property).orNull ?: System.getenv(env)

val releaseStoreFile = signingValue("drizziq.storeFile", "DRIZZIQ_STORE_FILE")

android {
    namespace = "app.drizziq"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "app.drizziq"
        minSdk = 24
        targetSdk = 37
        versionCode = 16
        versionName = "1.7.1"
        testInstrumentationRunner = "app.drizziq.PhoneExercise"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("drizziq.storePassword", "DRIZZIQ_STORE_PASSWORD")
                keyAlias = signingValue("drizziq.keyAlias", "DRIZZIQ_KEY_ALIAS") ?: "drizziq"
                keyPassword = signingValue("drizziq.keyPassword", "DRIZZIQ_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    lint {
        warningsAsErrors = true
    }
    sourceSets.getByName("androidTest").assets.directories.add("src/test/resources")
}

dependencies {
    // DWD RV is ODIM HDF5; decoding this format warrants a maintained reader.
    implementation("io.jhdf:jhdf:0.15.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}

val generateIcons =
    tasks.register<Exec>("generateIcons") {
        group = "build"
        description = "Generate native vectors from the editable SVG icon sources"
        val sources = rootProject.fileTree("artwork/icons") { include("**/*.svg") }
        inputs.files(sources)
        inputs.file(rootProject.file("tools/generate_icons.py"))
        outputs.files(
            sources.map {
                file("src/main/res/drawable/ic_${it.nameWithoutExtension.replace('-', '_')}.xml")
            }
        )
        outputs.file(file("src/main/java/app/drizziq/IconAssets.kt"))
        outputs.file(file("src/main/res/mipmap-anydpi/ic_launcher.xml"))
        commandLine("python3", rootProject.file("tools/generate_icons.py").absolutePath)
    }

tasks.named("preBuild") { dependsOn(generateIcons) }
