import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

val llamaCppDir = file("src/main/cpp/llama.cpp")
val hasLlamaCpp = llamaCppDir.resolve("CMakeLists.txt").exists()

android {
    namespace = "com.suhel.llamabro.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        if (hasLlamaCpp) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                    )
                    abiFilters("arm64-v8a")
                }
            }
        }
    }

    if (hasLlamaCpp) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        logger.warn(
            "llama.cpp missing at ${llamaCppDir.path}; skipping native CMake. " +
                "Run scripts/fetch-llama-cpp.sh before assemble* / release builds.",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/libmlkitcommonpipeline.so",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
