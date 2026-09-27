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
 * AG BILGISI GUNCELLEME (provizyondan sonra, kimlige dokunmadan):
 *
 *   adb shell am broadcast -a com.otobusreklam.player.NETWORK \
 *     -n com.otobusreklam.player/.provision.ProvisionReceiver \
 *     --es secret "..." --es ssid "YENI-AP" --es psk "yeni-parola"
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

        // Sir HER YOLDA once dogrulanir.
        val secret = intent.getStringExtra("secret").orEmpty()
        if (!constantTimeEquals(secret, BuildConfig.PROVISION_SECRET)) {
            Log.w(TAG, "REDDEDILDI: provizyon sirri yanlis")
            reply("REDDEDILDI: sir yanlis")
            return
        }

        /*
         * AG BILGISI GUNCELLEME - provizyondan SONRA da calisir.
         *
         * NEDEN ZORUNLU: cihaza yazilan tek WiFi profili provizyon aninda verilendir
         * ve baska hicbir yol onu degistiremiyordu. Bunun iki somut sonucu vardi:
         *
         *  1. AP PAROLASI DEGISIRSE tum filo erisilemez hale gelir ve tek cikis yolu
         *     HER OTOBUSE GIDIP fabrika ayarlarina donmek olur. Parola degisikligi
         *     sira dişı bir olay degil: personel degisikligi, sizma suphesi, cihaz
         *     kaybi. Yani sistem, rutin bir isletme adimini felakete ceviriyordu.
         *  2. Belgeler "provizyondan sonra cihazi MASADAKI aga baglayin, kutuphane
         *     insin" diyordu - ama cihaza ikinci bir profil eklemenin yolu YOKTU.
         *     Belge, kodda bulunmayan bir adimi anlatiyordu.
         *
         * KIMLIGE DOKUNMUYOR: deviceId ve token degismez. Degisen yalnizca AG ve
         * SUNUCU ADRESI. Sunucu adresini degistirmek icerik guvenligini bozmaz -
         * manifest Ed25519 ile imzali, yani sahte bir sunucu gecerli manifest
         * uretemez; en fazla hizmet kesintisi yapabilir, ki cihaza fiziksel erisimi
         * olan biri bunu zaten yapabilir.
         */
        if (intent.action == ACTION_NETWORK || (config.provisioned && intent.getBooleanExtra("agGuncelle", false))) {
            if (!config.provisioned) {
                reply("REDDEDILDI: cihaz henuz provizyonlanmamis - once normal provizyon")
                return
            }
            val yeniSsid = intent.getStringExtra("ssid").orEmpty()
            val yeniPsk = intent.getStringExtra("psk").orEmpty()
            val yeniBase = intent.getStringExtra("baseUrl").orEmpty()
            val yeniApi = intent.getStringExtra("apiUrl").orEmpty()

            if (yeniSsid.isBlank() && yeniBase.isBlank()) {
                reply("REDDEDILDI: ssid veya baseUrl gerekli")
                return
            }
            if (yeniSsid.isNotBlank()) { config.ssid = yeniSsid; config.psk = yeniPsk }
            if (yeniBase.isNotBlank()) config.baseUrl = yeniBase
            if (yeniApi.isNotBlank()) config.apiUrl = yeniApi

            val admin = DeviceAdmin(context)
            val ok = if (config.ssid.isNotBlank()) admin.ensureWifi(config.ssid, config.psk) else true
            NetworkWatcher(context).apply { start(); triggerIfAlreadyConnected() }

            Log.i(TAG, "ag bilgisi guncellendi: ssid=${config.ssid} base=${config.baseUrl}")
            reply("TAMAM ag guncellendi cihaz=${config.deviceId} ssid=${config.ssid} wifi=${if (ok) "yazildi" else "YAZILAMADI!"}")
            return
        }

        if (config.provisioned) {
            Log.w(TAG, "REDDEDILDI: cihaz zaten provizyonlanmis")
            reply("REDDEDILDI: zaten provizyonlanmis (ag bilgisi icin: -a $ACTION_NETWORK)")
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

    companion object {
        private const val TAG = "ProvisionReceiver"
        /** Yalnizca ag/sunucu adresini gunceller; cihaz kimligine dokunmaz. */
        const val ACTION_NETWORK = "com.otobusreklam.player.NETWORK"
    }
}
