package com.otobusreklam.player.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
        val net = dogrulanmisAg()
        if (net == null) {
            // Kisit "UNMETERED" oldugu halde is tetiklendi ama dogrulanmis ag yok:
            // ag yeni kopmus olabilir. Yeniden denenmek uzere geri ver - basarili
            // donmek, emniyet kemerini o tur icin sessizce iptal etmek olurdu.
            Log.i(TAG, "dogrulanmis ag bulunamadi, emniyet turu yeniden denenecek")
            return Result.retry()
        }

        SyncService.startNow(applicationContext, net)
        return Result.success()
    }

    /** Internet yetenegi DOGRULANMIS WiFi agi. */
    private fun dogrulanmisAg(): Network? = runCatching {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "SyncWorker"
        private const val NAME = "senkron-emniyet"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
