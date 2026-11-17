import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Everything that carries the product name: application id and the pairing link scheme.
val brand = Properties().apply { rootProject.file("brand.properties").inputStream().use(::load) }

// The version lives in version.properties; -PappVersionCode / -PappVersionName override it (used for update tests).
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

// Release signing comes from the environment (CI secrets) or Gradle properties, never from the repository. Without all four
// values the release build is simply unsigned (that is what pull requests and local builds produce).
fun signingValue(name: String): String? = (findProperty(name) as String?) ?: System.getenv(name)
val releaseKeystore = signingValue("PAYGATE_KEYSTORE_FILE")
val releaseKeystorePassword = signingValue("PAYGATE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("PAYGATE_KEY_ALIAS")
val releaseKeyPassword = signingValue("PAYGATE_KEY_PASSWORD")
val releaseSigned = listOf(releaseKeystore, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.varizpardaz.listener.app"
    compileSdk = 36

    defaultConfig {
        applicationId = brand.getProperty("brand.applicationId")
        minSdk = 26
        targetSdk = 36
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: appVersion.getProperty("versionCode").toInt()
        versionName = (findProperty("appVersionName") as String?) ?: appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PAIR_SCHEME", "\"${brand.getProperty("brand.pairScheme")}\"")
        buildConfigField("String", "PAIR_HOST", "\"${brand.getProperty("brand.pairHost")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigned) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v2 is enough for every phone this app supports (Android 8+). v3 adds the ability to rotate the signing key later.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Debug and release can be installed side by side on a test phone.
            applicationIdSuffix = ".debug"
            // The local mock server speaks plain http. Never true in release.
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // AGP can record the git commit and remote in the APK; that differs between checkouts and clones.
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // "A newer version exists" checks would turn every upstream release into a CI failure. Versions are
        // bumped deliberately (see docs/release.md), not by lint.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // Nothing in the APK may depend on who built it or when, so that anyone can rebuild the app from source and get the same
    // files (docs/release.md). The dependency blob and the git info AGP can add are opaque to an auditor and differ between checkouts.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**", "/META-INF/*.version")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Room writes its schema as JSON so every migration can be reviewed and tested against the previous version.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The instrumented tests run the shared contract fixtures and the regex-dialect cases on a real regex engine
// (Android's ICU, not the JVM's), so they are packaged as assets of the test APK.
val sharedFixtures: String = (findProperty("fixturesDir") as String?)
    ?: rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath

android.sourceSets.getByName("androidTest").assets.srcDirs(
    sharedFixtures,
    rootProject.layout.projectDirectory.dir("core/src/test/resources/regex").asFile.absolutePath,
)

// A signed release carries the application id for good: Android treats another id as another app, so an installed phone could
// never be updated to it. Refuse to sign a release while the id is still the provisional one (brand.properties).
gradle.taskGraph.whenReady {
    val releasing = allTasks.any { it.project == project && it.name in setOf("assembleRelease", "bundleRelease", "packageRelease") }
    if (releasing && releaseSigned && brand.getProperty("brand.applicationIdFinal") != "true") {
        throw GradleException(
            "Refusing to sign a release: brand.applicationId is still provisional. Decide the id, then set brand.applicationIdFinal=true in brand.properties.",
        )
    }
}
