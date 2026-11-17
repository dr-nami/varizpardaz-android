import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is pure Kotlin/JVM (no Android imports) so it runs in plain unit tests and on the phone.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // Ed25519 for config verification. Pure Java and independent of the platform JCA provider,
    // because Android only ships Ed25519 from API 33 and minSdk is 26.
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The shared contract fixtures live outside this module. Override with -PfixturesDir=... if the
// app is ever split into its own repository and the fixtures are vendored elsewhere.
val fixturesDir: String = (findProperty("fixturesDir") as String?)
    ?: rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath

tasks.test {
    useJUnitPlatform()
    systemProperty("paygate.fixturesDir", fixturesDir)
    inputs.dir(fixturesDir).withPropertyName("contractFixtures")
    // The end-to-end test runs the Node mock server, so a change to it must re-run the tests (not come from cache).
    inputs.files(fileTree("../tools/mock-server") { exclude("node_modules/**", ".state/**") }).withPropertyName("mockServer")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
