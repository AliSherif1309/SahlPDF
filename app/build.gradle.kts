import com.android.build.api.variant.FilterConfiguration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every published APK must have a higher code than every Sahl PDF 1.x APK (all below 10000),
// so installed copies update in place. ABI builds get their own block of codes.
val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val baseCode = 10000 + run
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

android {
    namespace = "com.sahl.pdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aly.sahlpdf" // kept so existing installs update in place
        minSdk = 26
        targetSdk = 35
        versionCode = baseCode
        versionName = "1.$run"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abiCodes.keys.toTypedArray())
            isUniversalApk = true
        }
    }

    val ks = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ks != null) create("sahl") {
            storeFile = file(ks)
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = "sahl"
            keyPassword = System.getenv("KEYSTORE_PASS")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("sahl") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val offset = abiCodes[abi]
            if (offset != null) output.versionCode.set(offset * 10000 + baseCode)
        }
    }
}

dependencies {
    // PDFium (BSD-3-Clause) through the Apache-2.0 PdfiumAndroidKt binding.
    implementation("io.legere:pdfiumandroid:1.0.35")
    // PdfiumAndroidKt declares coroutines as compileOnly, so the app must provide it at runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Optional Google Play Services ML Kit Document Scanner (~300KB client; falls back gracefully if Play Services is absent).
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    // Apache-2.0 PDFBox-Android for reading & editing native embedded PDF annotations (Xodo, Adobe, etc.).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}
