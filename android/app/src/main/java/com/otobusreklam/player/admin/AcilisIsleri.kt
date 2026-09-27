package com.otobusreklam.player.admin

import android.content.Context
import android.util.Log
import com.otobusreklam.player.Config
import java.util.concurrent.Executors

/**
 * ACILIS ISLERI: AGIR VE TEKRARLANAN KURULUM ISLERININ TEK KAPISI.
 *
 * Iki ayri problem cozuluyor:
 *
 * 1. ANA IS PARCACIGI. `applyPolicies()` 8 senkron DPM binder cagrisi yapiyor
 *    (icinde `addPersistentPreferredActivity` -> sistem tarafinda package-restrictions
 *    XML yazimi), `ensureWifi` ise `setWifiEnabled`/`configuredNetworks`/`addNetwork`/
 *    `saveConfiguration` cagiriyor - `saveConfiguration` eski ROM'larda WiFi config
 *    store'unu flash'a yaziyor. Bunlar App.onCreate'te ANA IS PARCACIGINDA
 *    yapiliyordu ve App.onCreate sureci baslatan HER bilesen icin calisir
 *    (BOOT_COMPLETED, PROVISION yayini, InstallResultReceiver, WorkManager). Acilis
 *    contention'i altinda ayni is parcacigi PlayerActivity.onCreate'i de bekletiyor,
 *    yani TV'de siyah ekran suresi her acilista belirgin sekilde uzuyordu.
 *
 * 2. TEKRAR. BootReceiver.onReceive ayni applyPolicies + ensureWifi cagrilarini
 *    App.onCreate onlari saniyenin kesri kadar once bitirmisken IKINCI kez yapiyordu.
 *    `addPersistentPreferredActivity` bir setter degil EKLEYICI bir API'dir; her
 *    surec baslangicinda yeniden cagirmak bedava degil.
 *
 * Boylece: surec basina BIR kez, ana is parcaciginin DISINDA.
 */
object AcilisIsleri {

    private const val TAG = "AcilisIsleri"

    @Volatile private var basladi = false

    /**
     * Tek is parcacikli havuz: isler SIRAYLA kosar.
     * Provizyon sirasinda gelen ikinci bir istek acilis isinin ortasina girmez.
     */
    private val havuz = Executors.newSingleThreadExecutor { r ->
        Thread(r, "acilis-isleri").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Surec basina bir kez. Ikinci cagri sessizce yok sayilir. */
    fun birKez(context: Context) {
        if (basladi) return
        synchronized(this) {
            if (basladi) return
            basladi = true
        }
        calistir(context.applicationContext, "acilis")
    }

    /**
     * Provizyon/ag guncellemesi gibi durum DEGISTIREN olaylardan sonra: kosulsuz
     * yeniden uygula. (Yeni SSID/parola yazilmasi gerekiyor.)
     */
    fun yenidenUygula(context: Context) = calistir(context.applicationContext, "yeniden")

    private fun calistir(app: Context, sebep: String) {
        havuz.execute {
            val t0 = System.nanoTime()
            val admin = DeviceAdmin(app)
            val config = Config(app)
            val hatalar = admin.applyPolicies()

            if (config.ssid.isNotBlank()) {
                // wifiHazirla bekleme icerir - bu yuzden ana is parcaciginda DEGIL.
                val sonuc = admin.wifiHazirla(config.ssid, config.psk)
                Log.i(TAG, "wifi ($sebep): $sonuc")
            }

            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "acilis isleri bitti ($sebep, ${ms}ms), politika hatasi: ${hatalar.size}")
        }
    }
}
