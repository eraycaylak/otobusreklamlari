package com.otobusreklam.player.provision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.otobusreklam.player.BuildConfig
import com.otobusreklam.player.Config
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.sync.NetworkWatcher
import com.otobusreklam.player.sync.SyncWorker
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

    /**
     * AG ISLERI ARKA PLANDA, CEVAP GECIKTIRILEREK.
     *
     * `wifiHazirla` gercek ILISKLENDIRMEYI bekliyor (birkac saniye). onReceive ana is
     * parcaciginda kosar ve orada beklemek ANR'dir; goAsync() ile cevabi geciktirip isi
     * arka plan is parcaciginda yapiyoruz. `am broadcast` sirali yayin sonucunu
     * bekledigi icin teknisyen ciktiyi yine gorur.
     *
     * NEDEN BEKLIYORUZ: `addNetwork` yalnizca bicimsel gecerliligi dogrular, PAROLANIN
     * DOGRU oldugunu dogrulamaz. Eski "wifi=yazildi" raporu bu yuzden YANILTICIYDI:
     * yanlis parolayla provizyonlanmis bir stick checklist'i geciyor, otobuse montaj
     * ediliyor ve aga BIR DAHA HIC baglanmiyordu. Sebep aylarca bulunamazdi.
     */
    private fun agIsleriniBitir(context: Context, ssid: String, psk: String, onlar: (String) -> String) {
        /*
         * `isOrderedBroadcast` BURADA okunur, arka planda DEGIL.
         *
         * PendingResult bu bilgiyi disa acmiyor ve sirali OLMAYAN bir yayinin
         * PendingResult'ina setResultData cagirmak ISTISNA firlatir. Bayragi onReceive
         * icinde yakaliyoruz (orada gecerli), sonra arka planda yalnizca ona bakiyoruz.
         */
        val sirali = isOrderedBroadcast
        val pending = goAsync()
        val app = context.applicationContext
        Thread({
            val ozet = try {
                val admin = DeviceAdmin(app)
                val hatalar = admin.applyPolicies()
                val wifi = if (ssid.isNotBlank()) {
                    // 8 sn: goAsync'in arka plan yayin siniri (60 sn) icinde rahat kalir.
                    admin.wifiHazirla(ssid, psk, beklemeMs = 8_000L).toString()
                } else "SSID_YOK"

                val tetikleyici = NetworkWatcher(app).let { nw ->
                    val ok = nw.start()
                    nw.triggerIfAlreadyConnected()
                    ok
                }
                /*
                 * EMNIYET KEMERI BURADA KURULUR.
                 *
                 * SyncWorker.schedule yalnizca App.onCreate ve BootReceiver'da
                 * cagriliyordu; App.onCreate ise provizyonlanmamis cihazda ERKEN
                 * CIKIYOR. Yani yeni provizyonlanmis bir cihazda periyodik is
                 * KURULMUYORDU: NetworkWatcher kaydi basarisiz olduysa (ROM'un callback
                 * limiti) cihazin hicbir tetikleyicisi kalmiyor ve ilk is gunu tamamen
                 * kaybediliyordu - is ancak gece 03:25 reboot'undan sonra kuruluyordu.
                 */
                SyncWorker.schedule(app)

                onlar(
                    "wifi=$wifi tetikleyici=${if (tetikleyici) "kuruldu" else "KURULAMADI!"}" +
                        if (hatalar.isEmpty()) "" else " politika_hatasi=${hatalar.joinToString("|")}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "provizyon ag isleri basarisiz", e)
                "HATA: ${e.message}"
            }
            if (sirali) runCatching { pending.resultData = ozet }
            pending.finish()
        }, "provizyon-ag").start()
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
         * SUNUCU ADRESI.
         *
         * DURUST SINIR: "en fazla hizmet kesintisi" demek EKSIK olurdu. Sunucu adresi
         * degistiginde cihaz kendi TOKENINI yeni adrese Authorization basligiyla
         * gonderir, yani o adresi kontrol eden taraf O CIHAZIN tokenini ogrenir.
         * Icerik guvenligi bozulmaz (manifest Ed25519 imzali; sahte bir sunucu gecerli
         * manifest uretemez) ama token sizmasi gercektir.
         *
         * Neden yine de kabul edilebilir: buraya gelebilmek icin PROVISION_SECRET ve
         * cihaza ADB erisimi gerekiyor. Ikisine sahip olan biri zaten o stick'i sokup
         * tokenini dogrudan okuyabilir - yani bu yol yeni bir yetki kazandirmiyor,
         * yalnizca ayni yetkiyi daha kolay kullaniyor. Sizan token TEK CIHAZA aittir;
         * iptal etmek panelden bir tiklamadir (revoked).
         */
        if (intent.action == ACTION_NETWORK || (config.provisioned && intent.getBooleanExtra("agGuncelle", false))) {
            if (!config.provisioned) {
                reply("REDDEDILDI: cihaz henuz provizyonlanmamis - once normal provizyon")
                return
            }
            val yeniSsid = intent.getStringExtra("ssid").orEmpty()
            val yeniBase = intent.getStringExtra("baseUrl").orEmpty()
            val yeniApi = intent.getStringExtra("apiUrl").orEmpty()

            if (yeniSsid.isBlank() && yeniBase.isBlank()) {
                reply("REDDEDILDI: ssid veya baseUrl gerekli")
                return
            }

            /*
             * PAROLA SESSIZCE SILINMEZ.
             *
             * Eski hali `config.psk = yeniPsk` idi ve `yeniPsk`, extra yoksa BOS
             * DIZGIYE dusuyordu. Yani `--es ssid "YENI-AP"` yazip `--es psk` yazmayi
             * atlayan (ya da parolayi ayri bir adimda verecegini dusunen) bir teknisyen,
             * cihazin profilini ACIK AG olarak yeniden yaziyordu: WPA2 korumali AP'ye
             * bir daha ASLA baglanilmiyordu. Tam da filoyu kurtarmak icin eklenen
             * ozellik, filoyu kaybetmenin yeni bir yolu oluyordu - ve yayin
             * "TAMAM ... wifi=yazildi" diyordu.
             *
             * Artik: parola YALNIZCA extra gercekten verildiyse degisir. Acik bir ag
             * kastediliyorsa bu ACIKCA soylenmeli: --ez acikAg true
             */
            val pskVerildi = intent.hasExtra("psk")
            val acikAg = intent.getBooleanExtra("acikAg", false)
            if (yeniSsid.isNotBlank()) {
                if (!pskVerildi && !acikAg && config.psk.isBlank()) {
                    reply("REDDEDILDI: psk verilmedi. Parolali ag icin --es psk \"...\", " +
                        "sifresiz ag icin --ez acikAg true")
                    return
                }
                config.ssid = yeniSsid
                when {
                    acikAg -> config.psk = ""
                    pskVerildi -> config.psk = intent.getStringExtra("psk").orEmpty()
                    // psk verilmedi ve acikAg da denmedi: VAR OLAN parolayi KORU.
                    // (Yalnizca SSID adi degistiyse bu dogru davranis.)
                    else -> Log.i(TAG, "psk verilmedi - mevcut parola korunuyor")
                }
            }
            if (yeniBase.isNotBlank()) config.baseUrl = yeniBase
            if (yeniApi.isNotBlank()) config.apiUrl = yeniApi

            Log.i(TAG, "ag bilgisi guncellendi: ssid=${config.ssid} base=${config.baseUrl}")
            agIsleriniBitir(context, config.ssid, config.psk) { durum ->
                "TAMAM ag guncellendi cihaz=${config.deviceId} ssid=${config.ssid} $durum"
            }
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

        // adb ciktisinda gorunsun: sahada teknisyen bunu okuyup dogrulayacak
        val sahip = DeviceAdmin(context).isDeviceOwner
        agIsleriniBitir(context, ssid, psk) { durum ->
            "TAMAM cihaz=$deviceId sahip=${if (sahip) "evet" else "HAYIR!"} $durum"
        }
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
