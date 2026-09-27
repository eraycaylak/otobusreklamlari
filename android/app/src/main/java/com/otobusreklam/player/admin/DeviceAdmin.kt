package com.otobusreklam.player.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.otobusreklam.player.Config

/**
 * Cihaz sahibi yetkilerinin tek toplandigi yer.
 * Yetkiler yoksa her cagri sessizce no-op olur; uygulama yine calisir ama
 * kiosk, sessiz guncelleme ve otomatik WiFi devre disi kalir.
 */
class DeviceAdmin(private val context: Context) {

    private val dpm: DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private val admin = ComponentName(context, AdminReceiver::class.java)

    val isDeviceOwner: Boolean
        get() = try {
            dpm?.isDeviceOwnerApp(context.packageName) == true
        } catch (_: Exception) { false }

    /**
     * Provizyondan sonra bir kez, sonra her acilista cagrilir (idempotent).
     *
     * HER POLITIKA KENDI try'INDA - VE BASARISIZLIK PANELE GIDIYOR.
     *
     * Eskiden 8 bagimsiz politika TEK bir runCatching icindeydi. Ucuz bir AOSP
     * ROM'unda (kirpilmis DPM, setSystemUpdatePolicy destegi olmayan surumler tipik)
     * ilki SecurityException firlatinca kendisinden SONRAKI hepsi sessizce atlaniyordu:
     * kiosk paket listesi yazilmiyor, HOME kalici tercihi yazilmiyor, keyguard
     * kapatilmiyor, durum cubugu acik kaliyor. Uygulama yine aciliyor ve heartbeat
     * YESIL gonderiyordu - problem ancak bir otobuste TV'de kilit ekrani goruldugunde
     * ya da bir yolcu kumandayla uygulamadan cikinca ortaya cikiyordu.
     *
     * @return uygulanamayan politikalarin adlari (bos = hepsi tamam)
     */
    fun applyPolicies(): List<String> {
        val dpm = dpm
        val config = Config(context)
        if (dpm == null) {
            config.policyErrors = "DevicePolicyManager yok"
            return listOf("DevicePolicyManager yok")
        }
        if (!isDeviceOwner) {
            Log.w(TAG, "cihaz sahibi DEGIL - kiosk/sessiz guncelleme/WiFi devre disi")
            config.policyErrors = "cihaz sahibi DEGIL"
            return listOf("cihaz sahibi DEGIL")
        }

        val hatalar = mutableListOf<String>()
        fun politika(ad: String, blok: () -> Unit) {
            try {
                blok()
            } catch (e: Exception) {
                Log.e(TAG, "politika uygulanamadi: $ad", e)
                hatalar += ad
            }
        }

        // Kiosk icin izin verilen tek paket biziz
        politika("lockTaskPackages") {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        }

        // HOME'u kalici olarak biz devralalim: uygulama cokerse Android bizi
        // yeniden baslatir. Sistemin bize verdigi bedava watchdog budur.
        politika("home") {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin, home,
                ComponentName(context, "com.otobusreklam.player.player.PlayerActivity")
            )
        }

        // Izinleri otomatik ver: sahada kimse onay ekranina basmayacak
        politika("permissionPolicy") {
            dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
        }
        // IKISI DE veriliyor: Android 12+ FINE'i COARSE olmadan kabul etmiyor.
        politika("konumIzni") {
            grant(android.Manifest.permission.ACCESS_FINE_LOCATION)
            grant(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            politika("bildirimIzni") { grant("android.permission.POST_NOTIFICATIONS") }
        }

        // Ekran surekli acik kalsin (cihaz zaten surekli beslemede)
        politika("stayOn") {
            dpm.setGlobalSetting(
                admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                (BATTERY_PLUGGED_AC or BATTERY_PLUGGED_USB or BATTERY_PLUGGED_WIRELESS).toString()
            )
        }
        // ADB BILINCLI OLARAK ACILMIYOR: 50 cihazda kalici acik ADB,
        // paylasilan AP VLAN'inda herkesin cihaza baglanabilmesi demektir.
        // Servis gerektiginde tekniker ilgili cihazda elle acar.

        // minSdk 24 oldugu icin API 23 (M) kontrolu GEREKSIZDI: her zaman dogru.
        politika("keyguard") { dpm.setKeyguardDisabled(admin, true) }
        politika("statusBar") { dpm.setStatusBarDisabled(admin, true) }

        // Sistem guncellemeleri yayin saatinde cihazi kapatmasin
        politika("systemUpdatePolicy") {
            dpm.setSystemUpdatePolicy(
                admin,
                android.app.admin.SystemUpdatePolicy.createWindowedInstallPolicy(180, 300) // 03:00-05:00
            )
        }

        config.policyErrors = hatalar.joinToString(", ")
        if (hatalar.isNotEmpty()) {
            Log.e(TAG, "UYGULANAMAYAN POLITIKALAR: ${hatalar.joinToString(", ")}")
        }
        return hatalar
    }

    private fun grant(permission: String) {
        runCatching {
            dpm?.setPermissionGrantState(
                admin, context.packageName, permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    /** Kiosk moduna gir. Kullanici cikamaz, durum cubugu ve HOME tusu calismaz. */
    fun enterKiosk(activity: Activity) {
        if (!isDeviceOwner) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm?.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            activity.startLockTask()
        }.onFailure { Log.w(TAG, "kiosk moduna girilemedi: ${it.message}") }
    }

    /**
     * Noktadaki AP profilini cihaza yaz.
     *
     * Android 10+ ta normal uygulamalarda addNetwork HER ZAMAN -1 doner.
     * Cihaz sahibi bu kisitlamadan muaftir - bu yuzden DO olmadan otobus
     * kendi basina agi bulamaz ve tum sistem coker.
     */
    @Suppress("DEPRECATION")
    fun ensureWifi(ssid: String, psk: String): Boolean {
        if (ssid.isBlank()) return false
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false

        /*
         * IZIN ONCE KONTROL EDILIYOR - lint bunu hakli olarak istiyor.
         *
         * configuredNetworks/addNetwork, ACCESS_FINE_LOCATION olmadan SecurityException
         * firlatir. Izni applyPolicies() cihaz sahibi yetkisiyle otomatik veriyor, ama
         * SIRA GARANTILI DEGIL: provizyon yolunda applyPolicies hemen once cagriliyor,
         * BootReceiver'da da oyle - yine de cihaz sahibi olmadigimiz (ya da yetkinin
         * elle kaldirildigi) bir ROM'da bu cagri patlar.
         *
         * Istisna zaten runCatching ile yakalaniyordu, yani uygulama cokmuyordu - ama
         * sonuc "WiFi yazilamadi" olarak gorunup SEBEBI kayboluyordu. Izin yoksa acikca
         * soyluyoruz: sahada "neden aga baglanmiyor?" sorusunun ilk kontrol noktasi bu.
         */
        val izin = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (izin != PackageManager.PERMISSION_GRANTED) {
            Log.e(
                TAG,
                "ACCESS_FINE_LOCATION YOK - WiFi profili yazilamaz. " +
                    "Cihaz sahibi miyiz? (isDeviceOwner=$isDeviceOwner) applyPolicies() izni otomatik verir."
            )
            return false
        }

        /*
         * ACIK try/catch - runCatching DEGIL.
         *
         * Iki sebep:
         *  1. lint'in MissingPermission denetimi, SecurityException'in ACIKCA
         *     yakalandigini gormek istiyor. runCatching bir inline fonksiyon ve
         *     icindeki catch(Throwable) lint tarafindan bu koruma olarak taninmiyor -
         *     yani hata gercekten ele alinmis olsa bile uyari devam ediyordu.
         *  2. Iki basarisizlik farkli seyler anlatiyor ve sahada farkli mudahale
         *     gerektiriyor: IZIN reddedildi (cihaz sahibi degiliz / yetki kaldirilmis)
         *     ile ROM/surucu hatasi. Tek bir "yazilamadi" satiri ikisini ayirmiyordu.
         */
        return try {
            if (!wm.isWifiEnabled) wm.setWifiEnabled(true)

            val quoted = "\"$ssid\""

            /*
             * VAR OLAN PROFIL KOR KABUL EDILMEZ - HER ZAMAN YENIDEN YAZILIR.
             *
             * Onceden burada yalnizca SSID esitligine bakilip `enableNetwork` cagriliyor
             * ve `true` donuluyordu; `psk` parametresi o dalda HIC OKUNMUYORDU. Sonucu
             * agir, cunku bu fonksiyonun VAR OLMA SEBEBINI iptal ediyordu:
             *
             *  1. PAROLA ROTASYONU SESSIZ NO-OP: ACTION_NETWORK yayini tam olarak
             *     "AP parolasi degisti" durumu icin eklendi. Rotasyonun normal hali
             *     ayni SSID + yeni parolaydir; bu dal yeni parolayi hic yazmiyor,
             *     ustelik cagirana `wifi=yazildi` diye BASARI donduruyordu. Yani
             *     felaketi onlemek icin yazilan ozellik, felaketin ta kendisiydi.
             *  2. BOZUK PROFIL DUZELTILEMIYOR: teknisyen Settings'ten elle yanlis
             *     parolayla baglanmayi denerse cihazda ayni adli bozuk bir profil kalir
             *     ve bir daha ASLA duzeltilemez (depoda removeNetwork cagrisi yoktu).
             *
             * preSharedKey geri okundugunda maskelenir ("*"), yani "parola ayni mi?"
             * diye karsilastirmak MUMKUN DEGIL. Tek dogru yol: sil ve yeniden yaz.
             */
            wm.configuredNetworks?.filter { it.SSID == quoted }?.forEach {
                if (!wm.removeNetwork(it.networkId)) {
                    Log.w(TAG, "eski WiFi profili silinemedi (networkId=${it.networkId}) - uzerine yazilacak")
                }
            }

            val conf = WifiConfiguration().apply {
                SSID = quoted
                if (psk.isBlank()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$psk\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                // Baska aglarla yarismasin, otomatik baglansin
                priority = 1
                status = WifiConfiguration.Status.ENABLED
            }
            val id = wm.addNetwork(conf)
            if (id == -1) {
                Log.e(TAG, "WiFi profili EKLENEMEDI - cihaz sahibi degil misiniz? (isDeviceOwner=$isDeviceOwner)")
                return false
            }
            wm.enableNetwork(id, false)
            runCatching { wm.saveConfiguration() }   // API 26+ no-op olabilir, onemli degil
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "WiFi profili yazilamadi: IZIN REDDEDILDI (cihaz sahibi mi? isDeviceOwner=$isDeviceOwner)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "WiFi profili yazilamadi (ROM/surucu hatasi?)", e)
            false
        }
    }


    /** ensureWifi + gercek ilisklendirme beklemesinin sonucu. */
    enum class WifiSonuc { YAZILAMADI, YAZILDI_BAGLANMADI, BAGLANDI, BELIRSIZ }

    /**
     * SU AN hedef AP'ye bagli miyiz?
     *
     * `addNetwork` yalnizca konfigurasyonun BICIMSEL gecerliligini dogrular; parolanin
     * DOGRU oldugunu dogrulamaz. Yani "profil yazildi" ile "aga baglanabiliyor" ayni
     * sey DEGIL - ve provizyon betigi tam olarak bu ikisini karistiriyordu: yanlis
     * parolayla provizyonlanmis bir stick "wifi=yazildi" raporu veriyor, checklist
     * geciyor, otobuse montaj ediliyor ve aga BIR DAHA HIC baglanmiyordu.
     *
     * DIKKAT: Android 8.1+ konum izni olmadan SSID yerine "<unknown ssid>" doner.
     * O durumda "baglanmadi" demek YANLIS olur; BELIRSIZ donuyoruz.
     */
    @Suppress("DEPRECATION")
    fun wifiBagliMi(ssid: String): WifiSonuc {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return WifiSonuc.BELIRSIZ
        return try {
            val info = wm.connectionInfo ?: return WifiSonuc.YAZILDI_BAGLANMADI
            val ad = info.ssid?.trim('"').orEmpty()
            when {
                ad == ssid -> WifiSonuc.BAGLANDI
                ad.isBlank() || ad.contains("unknown", ignoreCase = true) -> WifiSonuc.BELIRSIZ
                // Baska bir aga bagliyiz: bu da "hedefe baglanamadi" demektir.
                else -> WifiSonuc.YAZILDI_BAGLANMADI
            }
        } catch (e: SecurityException) {
            WifiSonuc.BELIRSIZ
        } catch (e: Exception) {
            Log.w(TAG, "WiFi durumu okunamadi: ${e.message}")
            WifiSonuc.BELIRSIZ
        }
    }

    /**
     * Profili yaz VE gercekten ilisklendigini bekle.
     *
     * ANA IS PARCACIGINDAN CAGIRMAYIN: icinde bekleme var. Cagiranlar arka planda
     * (provizyon aliciisinin goAsync'i, App.onCreate'in arka plan is parcacigi,
     * BootReceiver'in goAsync'i).
     *
     * Basarisizlikta config.lastError'a yaziyoruz: sahada "neden aga baglanmiyor?"
     * sorusunun cevabi panelde gorunsun. Yanlis parola bu yolla tespit edilir -
     * addNetwork'un donusu bunu ASLA gostermez.
     */
    fun wifiHazirla(ssid: String, psk: String, beklemeMs: Long = 15_000L): WifiSonuc {
        if (ssid.isBlank()) return WifiSonuc.YAZILAMADI
        val config = Config(context)
        if (!ensureWifi(ssid, psk)) {
            config.hataEkle("wifi", "WiFi profili YAZILAMADI (cihaz sahibi mi? izin var mi?)")
            return WifiSonuc.YAZILAMADI
        }

        val bitis = android.os.SystemClock.elapsedRealtime() + beklemeMs
        var son = WifiSonuc.YAZILDI_BAGLANMADI
        while (android.os.SystemClock.elapsedRealtime() < bitis) {
            son = wifiBagliMi(ssid)
            if (son == WifiSonuc.BAGLANDI) return son
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return son
            }
        }
        if (son == WifiSonuc.YAZILDI_BAGLANMADI) {
            val mesaj = "WiFi ilisklendirilemedi: \"$ssid\" - parola yanlis olabilir " +
                "(profil yazildi ama ${beklemeMs / 1000} sn icinde baglanti kurulmadi)"
            config.hataEkle("wifi", mesaj)
            Log.e(TAG, mesaj)
        }
        return son
    }

    /**
     * SISTEM SAATINI YAZ.
     *
     * Neden gerekli: ClockManager'dan gecen damgalar dogru olur ama GECMEYEN her sey
     * sistem saatini kullanir - Room alanlari, dosya zaman damgalari, logcat, TLS
     * gecerlilik kontrolu. Bu cihazlarda pil destekli RTC yok; guc kesintisinden sonra
     * sistem saati 1970'e duser ve saha teshisi imkansizlasir.
     *
     * API 28+ ve yalnizca cihaz sahibi icin. Otomatik zaman acikken sistem reddeder;
     * o durumda zaten bir sorun yok.
     */
    fun setTime(epochMs: Long): Boolean {
        if (!isDeviceOwner) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching { dpm?.setTime(admin, epochMs) == true }
            .getOrElse { Log.w(TAG, "sistem saati yazilamadi: ${it.message}"); false }
    }

    /** Gece kontrollu yeniden baslatma ve watchdog son caresi. */
    fun reboot(): Boolean {
        if (!isDeviceOwner) return false
        // dpm.reboot() API 24 ile geldi ve minSdk de 24: kontrol gereksizdi.
        return runCatching { dpm?.reboot(admin); true }.getOrElse { false }
    }

    val hasInstallPermission: Boolean
        get() = isDeviceOwner ||
            context.packageManager.checkPermission(
                android.Manifest.permission.INSTALL_PACKAGES, context.packageName
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "DeviceAdmin"
        const val BATTERY_PLUGGED_AC = 1
        const val BATTERY_PLUGGED_USB = 2
        const val BATTERY_PLUGGED_WIRELESS = 4
    }
}
