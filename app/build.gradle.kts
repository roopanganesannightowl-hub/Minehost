import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives in keystore/keystore.properties (gitignored).
// Without it, release builds fall back to unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * The one signing identity every published MineHost release uses. It was
 * minted for the CI secrets (KEYSTORE_BASE64) and signed v0.01, v0.02 and
 * v0.03.
 *
 * It is deliberately the CI keystore and not the other way round: Android
 * refuses to update an app signed by a different certificate, so switching an
 * already-published app onto a new key would force every existing user to
 * uninstall and lose their worlds. A local build signed with anything else
 * produces an APK that cannot be installed over a released one, which is
 * exactly the trap this constant exists to catch.
 */
val canonicalReleaseCertSha256 = "51a56dfd590be94d55837c1f8ed5b174716d37d30810826ef3483c3d7205981f"

android {
    namespace = "com.minehost.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.minehost.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.03"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file("keystore/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            buildConfigField("boolean", "LOCAL_RUNTIME_BUILD", "false")
        }
        create("local") {
            dimension = "distribution"
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            targetSdk = 28
            buildConfigField("boolean", "LOCAL_RUNTIME_BUILD", "true")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-splashscreen:1.2.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for local unit tests (android.jar only stubs it).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Fails a release build whose APK is not signed with [canonicalReleaseCertSha256].
 *
 * Without this, swapping the keystore is silent: the build succeeds and the
 * APK only fails much later, on a phone, as INSTALL_FAILED_UPDATE_INCOMPATIBLE.
 * Skips (with a warning) when apksigner cannot be located so the check never
 * blocks an environment that lacks build-tools.
 */
val verifyReleaseSignature by tasks.registering {
    group = "verification"
    description = "Fails if a release APK is not signed with the canonical release certificate."

    // Resolved at configuration time: the doLast closure may not touch the
    // Gradle script object or the configuration cache rejects the build.
    val expectedCert = canonicalReleaseCertSha256
    val buildDir = layout.buildDirectory.get().asFile
    val sdkDir: String? = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
    val apksigner: File? = sdkDir?.let { sdk ->
        File(sdk, "build-tools").listFiles()
            ?.filter { File(it, "apksigner").isFile }
            ?.sortedBy { it.name }
            ?.lastOrNull()
            ?.let { File(it, "apksigner") }
    }

    doLast {
        val apks = listOf(
            File(buildDir, "outputs/apk/standard/release/app-standard-release.apk"),
            File(buildDir, "outputs/apk/local/release/app-local-release.apk")
        ).filter { it.isFile }
        if (apks.isEmpty()) {
            println("verifyReleaseSignature: no release APKs found, skipping.")
            return@doLast
        }
        val signer = apksigner
        if (signer == null) {
            println("verifyReleaseSignature: apksigner not found, skipping signature check.")
            return@doLast
        }
        val wrong = mutableListOf<String>()
        apks.forEach { apk ->
            // ProcessBuilder rather than Project.exec: no project access needed.
            val process = ProcessBuilder(
                signer.absolutePath, "verify", "--print-certs", apk.absolutePath
            ).start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            val digest = stdout.lineSequence()
                .firstOrNull { it.contains("SHA-256 digest") }
                ?.substringAfter("SHA-256 digest:")
                ?.trim()
                ?.lowercase()
            when {
                digest == null -> println("verifyReleaseSignature: no digest in ${apk.name}, skipping it.")
                digest != expectedCert -> wrong += "${apk.name} is signed $digest"
                else -> println("verifyReleaseSignature: ${apk.name} uses the canonical certificate.")
            }
        }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Release APK signed with the wrong certificate:")
                    wrong.forEach { appendLine("  - $it") }
                    appendLine("Expected: $expectedCert")
                    appendLine("An APK signed with a different key cannot be installed over a released one")
                    appendLine("(INSTALL_FAILED_UPDATE_INCOMPATIBLE). keystore/keystore.properties must point at the")
                    appendLine("same keystore as the CI KEYSTORE_BASE64 secret. If that keystore is lost, recover it")
                    appendLine("instead of generating a new one - a new key breaks every existing install.")
                }
            )
        }
    }
}

tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Release") }.configureEach {
    finalizedBy(verifyReleaseSignature)
}
