package com.otobusreklam.player.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.otobusreklam.player.Config
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.sync.SyncWorker

/**
 * Acilista calisir.
 *
 * OynatIcIyI burada ACMIYORUZ: uygulama HOME oldugu icin Android onu zaten
 * kendisi baslatir. Burada yapilan is durum tazeleme.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "acilis: ${intent.action}")

        val config = Config(context)

        // Yeniden baslatma sayaci: guc problemini panelde gormenin en hizli yolu.
        // Sayaci SADECE burada artiriyoruz - watchdog'un kendi reboot'u da buraya
        // dusecegi icin iki yerde artirmak mukerrer sayima yol acardi.
        config.rebootCount = config.rebootCount + 1

        // Monotonik saat capasi acilista gecersizdir.
        // Saat guvenilmez -> bitis tarihli icerik oynatilmaz, evergreen'e dusulur.
        ClockManager(context).onBoot()

        DeviceAdmin(context).apply {
            applyPolicies()
            ensureWifi(config.ssid, config.psk)
        }

        SyncWorker.schedule(context)
    }

    private companion object { const val TAG = "BootReceiver" }
}
