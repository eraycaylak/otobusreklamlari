package com.otobusreklam.player

import android.app.Application
import android.util.Log
import com.otobusreklam.player.admin.AcilisIsleri
import com.otobusreklam.player.sync.NetworkWatcher
import com.otobusreklam.player.sync.SyncWorker

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val config = Config(this)

        /*
         * AGIR ISLER ARKA PLANDA VE SUREC BASINA BIR KEZ.
         *
         * applyPolicies() + ensureWifi() burada ANA IS PARCACIGINDA yapiliyordu ve
         * App.onCreate sureci baslatan HER bilesen icin calisir; BootReceiver da ayni
         * cagrilari milisaniyeler sonra TEKRAR ediyordu. Sonuc, en kotu anda (acilis
         * contention'i) TV'de uzayan siyah ekrandi. Ayrintisi AcilisIsleri icinde.
         *
         * Provizyonlanmamis cihazda da cagiriyoruz: politikalar (kiosk, HOME, izinler)
         * provizyondan BAGIMSIZ ve ilk provizyon yayini icin izinlerin hazir olmasi
         * gerekiyor.
         */
        AcilisIsleri.birKez(this)

        if (!config.configured) {
            Log.w(TAG, "cihaz provizyonlanmamis - sadece provizyon yayini bekleniyor")
            return
        }

        // ASIL TETIKLEYICI: WiFi gorulur gorulmez senkron.
        val tetikleyici = NetworkWatcher(this).let { nw ->
            val ok = nw.start()
            nw.triggerIfAlreadyConnected()
            ok
        }
        if (!tetikleyici) {
            // Tetikleyici kurulamadiysa tek kalan yol 15 dakikalik emniyet kemeri.
            // Bunu SESSIZ gecmek, cihazin pencereleri tamamen kacirmasi demekti.
            Log.e(TAG, "ag tetikleyicisi KURULAMADI - yalnizca periyodik is kaldi")
            config.lastError = "ag tetikleyicisi kurulamadi (senkron gecikebilir)"
        }

        // Emniyet kemeri (15 dk) - ana yol degil
        SyncWorker.schedule(this)
    }

    private companion object { const val TAG = "App" }
}
