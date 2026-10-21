// libass module for NuvioTV - atlas-based subtitle rendering pipeline.
// Based on Plezy by edde746 (https://github.com/edde746/plezy),
// which is a fork of libass-android by peerless2012
// (https://github.com/peerless2012/libass-android).
// See ATTRIBUTION.md for full credits.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nuvio.tv.libass"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLIBASS_CACHE_DIR=${layout.buildDirectory.get().asFile}/libass-prebuilt"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    // CompileOnly: the app ships forked media3 AARs from app/libs/; this module
    // only needs the API at compile time. The AAR paths are relative to *this* module.
    compileOnly(files(
        "../app/libs/lib-common-release.aar",
        "../app/libs/lib-exoplayer-release.aar",
        "../app/libs/lib-extractor-release.aar"
    ))
    // Guava is needed at compile time because media3's Tracks.groups returns ImmutableList.
    // The app ships Guava at runtime.
    compileOnly("com.google.guava:guava:33.3.1-android")
}
