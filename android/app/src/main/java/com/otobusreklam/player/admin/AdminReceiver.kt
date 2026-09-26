package com.otobusreklam.player.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * `adb shell dpm set-device-owner com.otobusreklam.player/.admin.AdminReceiver`
 * komutunun hedefledigi bilesen.
 *
 * Cihaz sahibi (device owner) olmadan bu proje YURUMEZ:
 *   1. Sessiz APK guncellemesi - yoksa her duzeltmede 50 stick'e elle dokunursun,
 *      yani USB tasima probleminin aynisina donersin
 *   2. WiFi'ye sessiz baglanma - Android 10+ hedefleyen normal uygulamada
 *      WifiManager.addNetwork her zaman -1 doner; DO/PO/sistem uygulamalari muaf
 *   3. Kiosk kilidi, durum cubugunu kapatma, uzaktan reboot
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "cihaz sahibi etkinlestirildi")
        DeviceAdmin(context).applyPolicies()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "cihaz sahibi KALDIRILDI - sessiz guncelleme ve kiosk artik calismaz")
    }

    private companion object { const val TAG = "AdminReceiver" }
}
