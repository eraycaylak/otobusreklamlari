package com.otobusreklam.player.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.otobusreklam.player.Config
import java.util.concurrent.TimeUnit

/**
 * EMNIYET KEMERI - ana tetikleyici degil.
 *
 * NetworkCallback bir sekilde kacarsa (surec oldurulmus, alici kaydedilememis)
 * bu periyodik is 15 dakikada bir kontrol eder. 15 dakika, 3 dakikalik pencere
 * icin yetersizdir; bu yuzden ASIL TETIKLEYICI NetworkWatcher'dir.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = Config(applicationContext)
        if (!config.configured) {
            // Provizyon yok: tekrar denemek bir sey degistirmez.
            Log.i(TAG, "cihaz provizyonlanmamis, emniyet turu atlandi")
            return Result.success()
        }

        /*
         * AGI COZUMLE VE GEC.
         *
         * Onceden `startNow(ctx, null)` cagriliyordu. null gecmek Http.client'in
         * soketi belirli bir aga SABITLEMESINI engeller (network.socketFactory
         * uygulanmaz). Bu cihazlarda birden fazla WiFi profili olabilir ve eski/olu
         * bir profil hala "baglanti" sayilabilir; sabitleme olmadan istekler yanlis
         * arabirimden cikip pencereyi bosa harcar. Tam da bu yuzden Http bu parametreyi
         * aliyor - emniyet yolunda onu bos gecmek, korumayi kendi elimizle kapatmakti.
         */
        val net = noktaAgi()
        if (net == null) {
            // Ag yok: yeniden denenmek uzere geri ver. Basarili donmek, emniyet
            // kemerini o tur icin sessizce iptal etmek olurdu.
            Log.i(TAG, "WiFi agi bulunamadi, emniyet turu yeniden denenecek")
            return Result.retry()
        }

        SyncService.startNow(applicationContext, net)
        return Result.success()
    }

    /**
     * Noktanin WiFi agi.
     *
     * INTERNET VE VALIDATED ARANMIYOR - bu bilincli ve NetworkWatcher ile ayni kural.
     * Noktadaki ag, PtMP linki koptugunda Android'e "internetsiz" gorunur; oysa
     * onbellek kutusu yerel aynadan icerigi hala servis ediyordur. Dogrulanmis ag
     * sartI koymak, emniyet kemerinin TAM DA EN COK GEREKTIGI anda - uplink kopukken -
     * hic devreye girmemesi demekti. Ustelik ana tetikleyici bu sarti koymuyordu,
     * yani iki yol ayni ag icin farkli karar veriyordu.
     *
     * Internet'i olan ag varsa o tercih edilir (merkeze de ulasilabilsin), yoksa
     * herhangi bir WiFi agi kabul edilir.
     */
    private fun noktaAgi(): Network? = runCatching {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val wifi = cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        wifi.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } ?: wifi.firstOrNull()
    }.getOrNull()

    companion object {
        private const val TAG = "SyncWorker"
        private const val NAME = "senkron-emniyet"

        fun schedule(context: Context) {
            /*
             * AG KISITI YOK - kontrol doWork icinde.
             *
             * Onceden NetworkType.UNMETERED kisiti vardi. Bu kisit isletim sistemine
             * "dogrulanmis, olculmeyen bir ag bekle" der; PtMP linki koptugunda
             * noktadaki ag bu sarti gecmez ve is HIC tetiklenmez - yani emniyet kemeri
             * tam da gerektigi anda yok olur. Olculen ag riski de yok: bu cihazlarda
             * SIM/hucresel baglanti bulunmuyor.
             *
             * Kisiti kaldirip agi kendimiz kontrol ediyoruz: en kotu durumda 15
             * dakikada bir iki veritabani okumasi maliyetinde bir bos tur.
             */
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}
