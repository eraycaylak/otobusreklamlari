package com.otobusreklam.player.sync

import kotlin.math.abs

/**
 * Kademeli yayim grubu.
 *
 * DIKKAT: Bu formul SUNUCUDAKININ BIREBIR AYNISI olmak zorunda
 * (sunucu/src/routes/admin.js icindeki hash fonksiyonu). Aksi halde sunucu
 * cihazi 2. grupta sanirken cihaz kendini 3. grupta sanir ve kademeli yayim
 * ongorulemez sekilde davranir.
 *
 * Iki tarafin ayni sonucu urettigi testle dogrulanmistir.
 */
object RolloutGroup {

    fun of(deviceId: String, groups: Int): Int {
        if (groups <= 1) return 1

        var h = 0
        for (c in deviceId) h = 31 * h + c.code      // Int tasmasi bilincli (JS `|0` ile ayni)

        // abs(Int.MIN_VALUE) yine Int.MIN_VALUE doner (negatif!).
        // Bu durumda modulo negatif cikar ve grup 0 veya eksi olurdu.
        val positive = if (h == Int.MIN_VALUE) 0 else abs(h)
        return 1 + (positive % groups)
    }
}
