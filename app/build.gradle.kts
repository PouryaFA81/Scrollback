// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing: keystore/ is gitignored and holds release.jks + keystore.properties (passwords).
// Without it, assembleRelease still builds, just unsigned.
val releaseKeys = rootProject.file("keystore/keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "app.scrollback"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.scrollback"
        minSdk = 33
        targetSdk = 37
        versionCode = 14
        versionName = "0.14.0"
    }

    signingConfigs {
        if (releaseKeys != null) {
            create("release") {
                storeFile = rootProject.file("keystore/" + releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeys != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Three APKs per build: arm64-v8a (modern phones, e.g. the A54), armeabi-v7a (old 32-bit) and universal.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // v0.13 libVLC: ARM only (x86 phones are practically gone; the player shows a notice there),
        // and compressed in the APK (extracted on install) so downloads stay as small as possible.
        jniLibs.excludes += setOf("lib/x86/**", "lib/x86_64/**")
        jniLibs.useLegacyPackaging = true
        resources.pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE", "META-INF/NOTICE")
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/BCKEY.*", "META-INF/BC*.SF", "META-INF/BC*.DSA", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.hls) // v0.12: Jellyfin HLS transcoding
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.coroutines.guava)
    implementation(libs.okhttp)
    implementation(libs.coil.network.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.coroutines.android)
    // v0.7: SFTP + SMB2/3 network storage
    implementation(libs.sshj)
    implementation(libs.smbj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.nanohttpd)
    // v0.13: full movie player (LGPL-2.1+, dynamically linked .so files)
    implementation(libs.libvlc)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.kxml2)
    testImplementation(libs.okhttp.mockwebserver)
}
