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

    /** Provizyondan sonra bir kez, sonra her acilista cagrilir (idempotent). */
    fun applyPolicies() {
        val dpm = dpm ?: return
        if (!isDeviceOwner) {
            Log.w(TAG, "cihaz sahibi DEGIL - kiosk/sessiz guncelleme/WiFi devre disi")
            return
        }
        runCatching {
            // Kiosk icin izin verilen tek paket biziz
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

            // HOME'u kalici olarak biz devralalim: uygulama cokerse Android bizi
            // yeniden baslatir. Sistemin bize verdigi bedava watchdog budur.
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin, home,
                ComponentName(context, "com.otobusreklam.player.player.PlayerActivity")
            )

            // Izinleri otomatik ver: sahada kimse onay ekranina basmayacak
            dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
            grant(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                grant("android.permission.POST_NOTIFICATIONS")
            }

            // Ekran surekli acik kalsin (cihaz zaten surekli beslemede)
            dpm.setGlobalSetting(
                admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                (BATTERY_PLUGGED_AC or BATTERY_PLUGGED_USB or BATTERY_PLUGGED_WIRELESS).toString()
            )
            // ADB BILINCLI OLARAK ACILMIYOR: 50 cihazda kalici acik ADB,
            // paylasilan AP VLAN'inda herkesin cihaza baglanabilmesi demektir.
            // Servis gerektiginde tekniker ilgili cihazda elle acar.

            // minSdk 24 oldugu icin API 23 (M) kontrolu GEREKSIZDI: her zaman dogru.
            // Olu bir kosul, okuyana "bu cagri bazi cihazlarda atlaniyor" izlenimi
            // verir ve gercek surum kontrollerinin ciddiyetini azaltir.
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)

            // Sistem guncellemeleri yayin saatinde cihazi kapatmasin
            dpm.setSystemUpdatePolicy(
                admin,
                android.app.admin.SystemUpdatePolicy.createWindowedInstallPolicy(180, 300) // 03:00-05:00
            )
        }.onFailure { Log.e(TAG, "politikalar uygulanamadi", it) }
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

        return runCatching {
            if (!wm.isWifiEnabled) wm.setWifiEnabled(true)

            val quoted = "\"$ssid\""
            wm.configuredNetworks?.firstOrNull { it.SSID == quoted }?.let {
                wm.enableNetwork(it.networkId, false)
                return true
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
                Log.e(TAG, "WiFi profili EKLENEMEDI - cihaz sahibi degil misiniz?")
                return false
            }
            wm.enableNetwork(id, false)
            runCatching { wm.saveConfiguration() }
            true
        }.getOrElse {
            // SecurityException ayri raporlanir: "izin yok" ile "ROM reddetti" farkli
            // sorunlar ve sahada farkli mudahale gerektiriyor.
            if (it is SecurityException) {
                Log.e(TAG, "WiFi profili yazilamadi: IZIN REDDEDILDI (SecurityException)", it)
            } else {
                Log.e(TAG, "WiFi profili yazilamadi", it)
            }
            false
        }
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
