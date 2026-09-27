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

    /**
     * Agirlik ust siniri.
     *
     * Panelde yanlislikla girilen bir deger (ornegin 10000) listeyi o kadar sisirir ki
     * bellek tukenir ve kapasite hesabi Int tasmasina girip cokebilir. Otobus ekraninda
     * 20'den fazla tekrarin pratik bir anlami da yok.
     */
    const val MAX_WEIGHT = 20

    fun <T> interleave(items: List<T>, weightOf: (T) -> Int): List<T> {
        if (items.isEmpty()) return emptyList()

        fun agirlik(item: T) = weightOf(item).coerceIn(1, MAX_WEIGHT)

        val maxWeight = items.maxOf(::agirlik)
        val out = ArrayList<T>(items.size * maxWeight)

        for (pass in 1..maxWeight) {
            for (item in items) {
                if (agirlik(item) >= pass) out += item
            }
        }
        return out
    }
}
