plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kevinluo.autoglm"
    compileSdk = 34

    lint {
        disable += "PropertyEscape"
    }

    defaultConfig {
        applicationId = "com.kevinluo.autoglm"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("release.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "AutoGLM Dev")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (file("release.keystore").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    
    // Enable JUnit 5 for Kotest property-based testing
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
        unitTests.isReturnDefaultValues = true
    }
}

// Copy dev_profiles.json to assets for debug builds
android.applicationVariants.all {
    val variant = this
    if (variant.buildType.name == "debug") {
        val copyDevProfiles = tasks.register("copyDevProfiles${variant.name.replaceFirstChar { it.uppercase() }}") {
            val devProfilesFile = rootProject.file("dev_profiles.json")
            val assetsDir = file("src/main/assets")
            
            doLast {
                if (devProfilesFile.exists()) {
                    assetsDir.mkdirs()
                    devProfilesFile.copyTo(File(assetsDir, "dev_profiles.json"), overwrite = true)
                    println("Copied dev_profiles.json to assets")
                } else {
                    println("dev_profiles.json not found, skipping")
                }
            }
        }
        
        tasks.named("merge${variant.name.replaceFirstChar { it.uppercase() }}Assets") {
            dependsOn(copyDevProfiles)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    
    // Kotlin Coroutines for async operations
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    
    // Security for encrypted preferences
    implementation(libs.androidx.security.crypto)
    
    // OkHttp for API communication
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    
    // Retrofit for API communication
    implementation(libs.retrofit)
    
    // Kotlin Serialization for JSON parsing
    implementation(libs.kotlinx.serialization.json)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
