package com.otobusreklam.player.sync

import android.content.Context
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
        if (!config.configured) return Result.success()
        SyncService.startNow(applicationContext, null)
        return Result.success()
    }

    companion object {
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
