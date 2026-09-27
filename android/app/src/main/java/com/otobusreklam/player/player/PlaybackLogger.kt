package com.otobusreklam.player.player

import com.otobusreklam.player.Config
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.PlayLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Oynatma kaniti (proof-of-play) kaydi.
 * Reklamverene kesilen faturanin dayanagi bu satirlar.
 *
 * clockTrusted alani bilerek tasiniyor: saati supheli cihazdan gelen kayit
 * raporda ayri sutunda gosterilir, boylece denetimde tartisma cikmaz.
 */
class PlaybackLogger(
    private val db: AppDatabase,
    private val clock: ClockManager,
    private val config: Config
) {

    /**
     * Kendi kapsamindan yaziyoruz, cagiranin kapsamindan DEGIL.
     *
     * Onceden kayitlar aktivitenin lifecycleScope'undan yaziliyordu. Aktivite
     * kapanirken (onDestroy) son oynatmayi kaydetmeye calisiyorduk ama o anda
     * lifecycleScope ZATEN IPTAL EDILMIS oluyordu: coroutine hic calismiyor ve
     * son oynatma kaydi sessizce kayboluyordu. Faturanin dayanagi olan bir veri
     * icin kabul edilemez.
     */
    private val kapsam = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Atesle-ve-unut: cagiranin yasam dongusune bagli degildir. */
    fun kaydet(
        itemId: String,
        sha256: String,
        startedAt: Long,
        durationMs: Long,
        completed: Boolean
    ) {
        kapsam.launch { record(itemId, sha256, startedAt, durationMs, completed) }
    }

    suspend fun record(
        itemId: String,
        sha256: String,
        startedAt: Long,
        durationMs: Long,
        completed: Boolean
    ) {
        db.playLog().insert(
            PlayLogEntity(
                itemId = itemId,
                sha256 = sha256,
                startedAt = startedAt,
                durationMs = durationMs,
                completed = completed,
                playlistVersion = config.playlistVersion,
                clockTrusted = clock.trusted()
            )
        )
    }
}
