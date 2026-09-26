package com.otobusreklam.player.player

/**
 * Agirlikli siralama. Android'e bagimli DEGIL -> birim testi yazilabilir.
 *
 * weight=2 olan icerik donguye iki kez girer ama ARKA ARKAYA DEGIL:
 * her turda liste bir kez gecilir, boylece agirlikli icerikler donguye yayilir.
 *
 * Ornek: A(3) B(1) C(1)  ->  A B C  A  A
 * Ayni icerigi ust uste koymak izleyici acisindan "reklam tekrarliyor" hissi
 * yaratir; yaymak ayni oynatma sayisini daha az rahatsiz edici hale getirir.
 */
object Weighting {

    fun <T> interleave(items: List<T>, weightOf: (T) -> Int): List<T> {
        if (items.isEmpty()) return emptyList()

        val maxWeight = items.maxOf { weightOf(it).coerceAtLeast(1) }
        val out = ArrayList<T>(items.size * maxWeight)

        for (pass in 1..maxWeight) {
            for (item in items) {
                if (weightOf(item).coerceAtLeast(1) >= pass) out += item
            }
        }
        return out
    }
}
