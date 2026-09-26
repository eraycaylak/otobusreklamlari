plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.otobusreklam.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.otobusreklam.player"
        // API 24: ucuz stick'lerin buyuk cogunlugu Android 7+.
        // DevicePolicyManager.reboot() de API 24 ile geldi.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "MANIFEST_PUBLIC_KEY",
            "\"${project.findProperty("MANIFEST_PUBLIC_KEY") ?: ""}\""
        )
        buildConfigField(
            "String",
            "PROVISION_SECRET",
            "\"${project.findProperty("PROVISION_SECRET") ?: ""}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // NOT: signingConfig'i kendi anahtarinizla doldurun.
            // Sessiz guncelleme icin TUM surumler AYNI anahtarla imzalanmali,
            // yoksa PackageInstaller guncellemeyi reddeder.
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // API 24 hedeflenirken java.time gibi yeni API'lerin kullanilabilmesi icin
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        // NOT: Media3'un @UnstableApi isareti Kotlin'in -opt-in mekanizmasiyla
        // calismiyor ("not an opt-in requirement marker" uyarisi verir).
        // Dogru yol, kullanan sinifa androidx.annotation.OptIn koymaktir;
        // bkz. PlayerActivity.
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all { it.testLogging { events("passed", "skipped", "failed") } }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.work)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.eddsa)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Birim testleri: Daypart, Weighting, ClockMath, SyncPlan, RolloutGroup
    // Bu siniflar bilerek Android'e bagimsiz tutuldu; Robolectric/emulator gerekmez.
    //   ./gradlew :app:testDebugUnitTest
    testImplementation(libs.junit)
}
