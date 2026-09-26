package com.otobusreklam.player.player

import java.time.LocalTime

/**
 * Saat araligi eslestirmesi. Android'e bagimli DEGIL -> birim testi yazilabilir.
 *
 * Bicim: "07:00-10:00,17:00-20:00"  (bos = gun boyu)
 * Gece yarisini asan aralik desteklenir: "22:00-02:00"
 */
object Daypart {

    fun matches(spec: String, now: LocalTime): Boolean {
        if (spec.isBlank()) return true

        val ranges = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (ranges.isEmpty()) return true

        return ranges.any { range -> inRange(range, now) }
    }

    private fun inRange(range: String, now: LocalTime): Boolean {
        val parts = range.split("-")
        // Bozuk tanim yuzunden reklami dusurmeyelim: gecerli say.
        if (parts.size != 2) return true

        val start = parseOrNull(parts[0]) ?: return true
        val end = parseOrNull(parts[1]) ?: return true

        // Baslangic = bitis -> anlamsiz tanim, gun boyu kabul et
        if (start == end) return true

        return if (end.isAfter(start)) {
            // Normal aralik: [start, end)
            !now.isBefore(start) && now.isBefore(end)
        } else {
            // Gece yarisini asan aralik: [start, 24:00) veya [00:00, end)
            !now.isBefore(start) || now.isBefore(end)
        }
    }

    private fun parseOrNull(value: String): LocalTime? =
        try { LocalTime.parse(value.trim()) } catch (_: Exception) { null }
}
