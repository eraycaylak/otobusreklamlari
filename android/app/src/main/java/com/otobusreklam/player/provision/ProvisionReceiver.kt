package com.otobusreklam.player.provision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.otobusreklam.player.BuildConfig
import com.otobusreklam.player.Config
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.sync.NetworkWatcher
import java.security.MessageDigest

/**
 * Cihaza kimligini, tokenini ve ag bilgilerini yazan tek nokta.
 *
 *   adb shell am broadcast -a com.otobusreklam.player.PROVISION \
 *     -n com.otobusreklam.player/.provision.ProvisionReceiver \
 *     --es secret "..." --es deviceId "OTOBUS-014" --es token "..." \
 *     --es baseUrl "http://10.20.0.10:8080" --es ssid "..." --es psk "..."
 *
 * GUVENLIK: Alici exported olmak zorunda (adb shell baska bir uygulamadir),
 * ama iki katmanla korunuyor:
 *   1) SADECE cihaz henuz provizyonlanmamisken kabul eder
 *   2) PROVISION_SECRET eslesmezse reddeder (sabit zamanli karsilastirma)
 * Provizyon bittikten sonra her yayin sessizce yok sayilir; yeniden provizyon
 * icin fabrika ayarlarina donmek gerekir.
 */
class ProvisionReceiver : BroadcastReceiver() {

    /** resultData YALNIZCA sirali yayinlarda yazilabilir; degilse istisna firlatir. */
    private fun reply(text: String) {
        if (isOrderedBroadcast) resultData = text
    }

    override fun onReceive(context: Context, intent: Intent) {
        val config = Config(context)

        if (config.provisioned) {
            Log.w(TAG, "REDDEDILDI: cihaz zaten provizyonlanmis")
            reply("REDDEDILDI: zaten provizyonlanmis")
            return
        }

        val secret = intent.getStringExtra("secret").orEmpty()
        if (!constantTimeEquals(secret, BuildConfig.PROVISION_SECRET)) {
            Log.w(TAG, "REDDEDILDI: provizyon sirri yanlis")
            reply("REDDEDILDI: sir yanlis")
            return
        }

        val deviceId = intent.getStringExtra("deviceId").orEmpty()
        val token = intent.getStringExtra("token").orEmpty()
        val baseUrl = intent.getStringExtra("baseUrl").orEmpty()
        val apiUrl = intent.getStringExtra("apiUrl").orEmpty().ifBlank { baseUrl }
        val ssid = intent.getStringExtra("ssid").orEmpty()
        val psk = intent.getStringExtra("psk").orEmpty()

        if (deviceId.isBlank() || token.isBlank() || baseUrl.isBlank()) {
            Log.e(TAG, "REDDEDILDI: deviceId/token/baseUrl zorunlu")
            reply("REDDEDILDI: eksik alan")
            return
        }

        config.provision(deviceId, token, baseUrl, apiUrl, ssid, psk)
        Log.i(TAG, "provizyon tamam: $deviceId -> $baseUrl")

        val admin = DeviceAdmin(context)
        admin.applyPolicies()
        val wifiOk = admin.ensureWifi(ssid, psk)

        NetworkWatcher(context).apply {
            start()
            triggerIfAlreadyConnected()
        }

        // adb ciktisinda gorunsun: sahada teknisyen bunu okuyup dogrulayacak
        reply(buildString {
            append("TAMAM cihaz=$deviceId ")
            append("sahip=${if (admin.isDeviceOwner) "evet" else "HAYIR!"} ")
            append("wifi=${if (wifiOk) "yazildi" else "YAZILAMADI!"}")
        })
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = MessageDigest.getInstance("SHA-256").digest(a.toByteArray())
        val bb = MessageDigest.getInstance("SHA-256").digest(b.toByteArray())
        return MessageDigest.isEqual(ba, bb) && a.isNotEmpty()
    }

    private companion object { const val TAG = "ProvisionReceiver" }
}
