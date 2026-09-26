package com.otobusreklam.player.player

import com.otobusreklam.player.Config
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.PlayLogEntity

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
