plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

/*
 * Release imzalama.
 *
 * TUM SURUMLER AYNI ANAHTARLA imzalanmak ZORUNDA: PackageInstaller farkli imzali
 * bir guncellemeyi reddeder, yani sessiz guncelleme calismaz ve 50 otobuse elle
 * gidilmesi gerekir.
 *
 * Degerler gradle.properties'ten (veya -P ile komut satirindan) gelir; anahtar
 * dosyasi ve parolalar DEPOYA GIRMEZ.
 */
val releaseKeystore = (project.findProperty("RELEASE_KEYSTORE") as String?)?.takeIf { it.isNotBlank() }

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
        /*
         * BU YAZILIMIN VAR OLDUGU EN ERKEN AN.
         *
         * Saat capasi icin ZEMIN degeri: bundan onceki bir "sunucu zamani" fiziksel
         * olarak imkansizdir, yani ya bozuk bir onbellek yaniti ya da kasitli bir
         * saldiridir (bkz. ClockManager - geriye alinan saat suresi dolmus reklami
         * diriltir). Iki durumda da reddedilmesi gerekir.
         *
         * SABIT tutuluyor, System.currentTimeMillis() DEGIL: her derlemede degisen
         * bir deger Gradle onbellegini ve yeniden uretilebilirligi bozar. CI gercek
         * derleme zamanini -PbuildTimeMs=... ile gecebilir; surum cikarken bu taban
         * da guncellenir.
         */
        buildConfigField(
            "long",
            "BUILD_TIME_MS",
            "${(project.findProperty("buildTimeMs") as String?)?.toLongOrNull() ?: 1788220800000L}L"
        )
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = project.findProperty("RELEASE_KEYSTORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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


/*
 * Imzalanmamis release derlemesini SESSIZCE uretmeyi engelle.
 *
 * Imzasiz APK cihaza kurulamaz; onceden `./gradlew assembleRelease` sorunsuz
 * gorunup app-release-UNSIGNED.apk uretiyordu ve hata ancak sahada, provizyon
 * sirasinda ortaya cikiyordu. Simdi derleme burada, anlasilir bir mesajla duruyor.
 */
if (releaseKeystore == null) {
    gradle.taskGraph.whenReady {
        val releaseIsteniyor = allTasks.any {
            it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease")
        }
        if (releaseIsteniyor) {
            throw GradleException(
                """
                Release derlemesi icin imzalama anahtari tanimli degil.

                android/gradle.properties dosyasina ekleyin (veya -P ile gecin):
                  RELEASE_KEYSTORE=/guvenli/yol/reklam.jks
                  RELEASE_KEYSTORE_PASSWORD=...
                  RELEASE_KEY_ALIAS=reklam
                  RELEASE_KEY_PASSWORD=...

                Anahtar yoksa once uretin:
                  keytool -genkey -v -keystore reklam.jks -keyalg RSA -keysize 2048 \
                          -validity 10000 -alias reklam

                DIKKAT: Bu anahtari kaybederseniz sessiz guncelleme bir daha CALISMAZ;
                her otobuse elle gidip uygulamayi kaldirip yeniden kurmak gerekir.
                Yedekleyin.

                Yalnizca deneme yapacaksaniz imzasiz yerine debug kullanin:
                  ./gradlew assembleDebug
                """.trimIndent()
            )
        }
    }
}
