plugins { id("com.android.application") version "9.4.1" apply false }

val formatter =
    configurations.create("formatter") {
        isCanBeConsumed = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }

dependencies { formatter("com.facebook:ktfmt:0.64") }

val kotlinSources =
    files(
        fileTree("app/src") { include("**/*.kt") },
        fileTree(".") { include("*.gradle.kts", "app/*.gradle.kts") },
    )

fun registerFormatter(name: String, checkOnly: Boolean) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description =
            if (checkOnly) "Check Kotlin formatting without modifying files"
            else "Format Kotlin sources and Gradle scripts"
        classpath = formatter
        mainClass.set("com.facebook.ktfmt.cli.Main")
        args("--kotlinlang-style")
        if (checkOnly) args("--dry-run", "--set-exit-if-changed")
        args(kotlinSources.files.sorted().map { it.absolutePath })
    }

registerFormatter("formatCheck", true)

registerFormatter("formatKotlin", false)

tasks.register("verifyLocal") {
    group = "verification"
    description = "Run formatting, JVM tests, lint and debug/release/device-test APK builds"
    dependsOn(
        "formatCheck",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":app:assembleRelease",
        ":app:assembleDebugAndroidTest",
    )
}
