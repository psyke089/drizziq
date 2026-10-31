plugins { id("com.android.application") }

android {
    namespace = "app.drizziq"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "app.drizziq"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.3.0"
        testInstrumentationRunner = "app.drizziq.PhoneExercise"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes { release { isMinifyEnabled = false } }
    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
    }
}

dependencies {
    // DWD RV is ODIM HDF5; decoding this format warrants a maintained reader.
    implementation("io.jhdf:jhdf:0.8.6")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

val generateIcons by
    tasks.registering(Exec::class) {
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
