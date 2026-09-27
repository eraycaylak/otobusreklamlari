package com.otobusreklam.player.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROTOKOL SOZLESMESI - SUNUCUNUN URETTIGI GERCEK MANIFEST UZERINDE.
 *
 * Bu testin girdisi elle yazilmis bir ornek DEGIL: `sunucu/` calistirilip
 * /api/v1/manifest'ten alinan ve `app/src/test/resources/` altina konan GERCEK
 * bir zarf. Neden onemli: iki uc arasindaki uyusmazlik (bir alanin adi degisti,
 * bir alan artik null gonderiliyor, bir tarih bicimi farklilasti) DERLEME zamaninda
 * yakalanmaz. Sahada gorunumu ise sudur: cihaz manifesti ayristiramaz, hicbir sey
 * indirmez, sunucu 200 doner ve panelde cihazlar yalnizca yavas yavas "bayat" olur -
 * sebep hicbir yerde gorunmez ve uzaktan komut kanali olmadigi icin duzeltme sahaya
 * gitmeyi gerektirir.
 *
 * Fixture'i yenilemek icin: sunucu tarafinda bir manifest uretip
 * resources/manifest-zarf.json + acik-anahtar.txt dosyalarini degistirin.
 */
class ManifestParserTest {

    private fun kaynak(ad: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(ad)) { "test kaynagi yok: $ad" }
            .bufferedReader().use { it.readText() }

    private val manifest: PlayManifest by lazy {
        val zarf = kaynak("manifest-zarf.json")
        val anahtar = kaynak("acik-anahtar.txt").trim()
        // IMZADAN GECEREK ayristiriyoruz: iki sinifin birlikte calistigini da dogrular.
        ManifestParser.parse(SignatureVerifier.open(zarf, anahtar))
    }

    @Test fun `zarf gercek anahtarla acilir ve manifest ayristirilir`() {
        assertEquals(1, manifest.schema)
        assertEquals("OTOBUS-014", manifest.deviceId)
        assertEquals("hat-14", manifest.deviceGroup)
        assertTrue("playlistVersion pozitif olmali", manifest.playlistVersion > 0)
    }

    @Test fun `serverTime ayristirilir - saat duzeltmesinin tek kaynagi`() {
        // Bu deger null gelirse cihaz saatini ASLA duzeltemez ve yalnizca evergreen oynar.
        assertNotNull("serverTime ayristirilamadi", manifest.serverTimeMs)
        assertTrue("serverTime makul bir epoch olmali", manifest.serverTimeMs!! > 1_600_000_000_000L)
    }

    @Test fun `timezone SUNUCUDAN gelir - daypart bu dilimde yorumlanir`() {
        // Cihazin kendi dilimi ucuz stick'lerde UTC cikar: Turkiye icin 3 saat kayma,
        // yani sabah kusagi reklami ogleden sonra doner.
        assertEquals("Europe/Istanbul", manifest.timezone)
    }

    @Test fun `evergreen oge BASTA gelir`() {
        // Cihaz once guvenlik agini indirsin: ekran asla bos kalmasin.
        assertTrue("manifest bos", manifest.items.isNotEmpty())
        assertTrue("ilk oge evergreen olmali", manifest.items.first().evergreen)
    }

    @Test fun `icerik ogesinin TUM zorunlu alanlari okunur`() {
        val kampanya = manifest.items.first { it.id == "kahve-30" }
        assertTrue("remotePath content/ ile baslamali: ${kampanya.remotePath}",
            kampanya.remotePath.startsWith("content/"))
        assertTrue("remotePath .mp4 ile bitmeli", kampanya.remotePath.endsWith(".mp4"))
        assertEquals(204_800L, kampanya.size)
        assertEquals(64, kampanya.sha256.length)
        assertTrue("dosya adi sha256 icermeli", kampanya.remotePath.contains(kampanya.sha256))
        assertEquals(30_040L, kampanya.durationMs)
        assertEquals(3, kampanya.weight)
        assertEquals(false, kampanya.evergreen)
    }

    @Test fun `tarihler ISO-8601 instant olarak ayristirilir`() {
        val kampanya = manifest.items.first { it.id == "kahve-30" }
        // 2026-10-01T00:00:00Z ve 2030-12-31T23:59:00Z
        assertEquals(1790_812_800_000L, kampanya.validFrom)
        assertEquals(1924_991_940_000L, kampanya.validUntil)

        val evergreen = manifest.items.first { it.evergreen }
        assertNull("evergreen'in tarihi olmamali", evergreen.validFrom)
        assertNull("evergreen'in tarihi olmamali", evergreen.validUntil)
    }

    @Test fun `daypart listesi gece yarisini asan araligi da tasir`() {
        val kampanya = manifest.items.first { it.id == "kahve-30" }
        assertEquals(listOf("07:00-10:00", "22:00-02:00"), kampanya.dayparts)
    }

    @Test fun `parcalar bitisik, toplami size a esit ve her birinin hash i var`() {
        /*
         * DEVAM ETTIRILEBILIR INDIRMENIN TEMELI. Offset/len tutarsizligi ya sonsuz
         * yeniden indirme ya da sessizce bozuk dosya uretir.
         */
        val oge = manifest.items.first()
        assertTrue("parca yok", oge.chunks.isNotEmpty())
        var beklenenOffset = 0L
        var toplam = 0L
        for ((i, c) in oge.chunks.withIndex()) {
            assertEquals("parca indeksi sirali olmali", i, c.index)
            assertEquals("parcalar bitisik olmali", beklenenOffset, c.offset)
            assertTrue("parca uzunlugu pozitif olmali", c.len > 0)
            assertEquals("parca hash i 64 onaltilik karakter olmali", 64, c.sha256.length)
            beklenenOffset += c.len
            toplam += c.len
        }
        assertEquals("parca toplami dosya boyutuna esit olmali", oge.size, toplam)
        // 200 KB / 64 KB -> 4 parca (son parca kisa)
        assertEquals(4, oge.chunks.size)
        assertEquals(8_192, oge.chunks.last().len)
    }

    @Test fun `app guncellemesi ve kademeli yayim alanlari okunur`() {
        val app = assertNotNull(manifest.app).let { manifest.app!! }
        assertEquals(42, app.versionCode)
        assertEquals("1.4.0", app.versionName)
        assertEquals("app/reklam-42.apk", app.remotePath)
        assertEquals(153_600L, app.size)
        assertEquals(64, app.sha256.length)
        assertEquals(3, app.rolloutGroup)
        assertEquals(true, app.critical)
        assertEquals(3, app.chunks.size)
    }

    @Test fun `elle atanan kademeli yayim grubu cihaza ULASIR`() {
        /*
         * Bu alan bir zamanlar HIC gonderilmiyordu: operatorun "su otobus kanarya
         * olsun" secimi sessizce yok sayiliyor ve kademeli yayimin butun amaci
         * (riski ALACAK cihazi secmek) ortadan kalkiyordu.
         */
        assertEquals(4, manifest.rolloutGroups)
        assertEquals(2, manifest.deviceRolloutGroup)
    }

    @Test fun `policy degerleri okunur ve ses varsayilan olarak KAPALI`() {
        assertEquals(15_000L, manifest.policy.staggerMaxMs)
        assertEquals(2, manifest.policy.parallelChunks)
        assertEquals(8_000L, manifest.policy.connectTimeoutMs)
        assertEquals(15_000L, manifest.policy.readTimeoutMs)
        assertEquals(0f, manifest.policy.volume, 0.0001f)
    }

    // ------------------------------------------------------- sinir kirpmalari

    @Test fun `policy alt sinirlari zorlanir - sifir zaman asimi SONSUZ beklemedir`() {
        /*
         * OkHttp'de zaman asimi 0 = ZAMAN ASIMI YOK. Bozuk bir yapilandirma ya da
         * yanlis bir onbellek yaniti on plan servisini sonsuza kadar askida birakir,
         * cihaz bir daha senkron olmaz ve bunu kimse gormez.
         */
        val m = ManifestParser.parse(
            """{"policy":{"connectTimeoutMs":0,"readTimeoutMs":0,"parallelChunks":0,"staggerMaxMs":-5,"volume":9}}"""
        )
        assertEquals(1_000L, m.policy.connectTimeoutMs)
        assertEquals(1_000L, m.policy.readTimeoutMs)
        assertEquals(1, m.policy.parallelChunks)
        assertEquals(0L, m.policy.staggerMaxMs)
        assertEquals(1f, m.policy.volume, 0.0001f)
    }

    @Test fun `policy ust sinirlari zorlanir`() {
        val m = ManifestParser.parse(
            """{"policy":{"connectTimeoutMs":999999,"readTimeoutMs":999999,"parallelChunks":99,"staggerMaxMs":999999}}"""
        )
        assertEquals(60_000L, m.policy.connectTimeoutMs)
        assertEquals(120_000L, m.policy.readTimeoutMs)
        assertEquals(4, m.policy.parallelChunks)
        assertEquals(60_000L, m.policy.staggerMaxMs)
    }

    @Test fun `policy hic gelmezse varsayilanlar kullanilir`() {
        val m = ManifestParser.parse("""{"schema":1}""")
        assertEquals(Policy(), m.policy)
        assertEquals(4, m.rolloutGroups)
        assertNull(m.deviceRolloutGroup)
        assertNull(m.app)
        assertTrue(m.items.isEmpty())
    }

    @Test fun `ayristirilamayan tarih null olur - oge uygun olmaz ama cokme yok`() {
        // Sunucu artik tarihleri normalize ediyor; yine de bozuk bir deger gelirse
        // cokmek yerine null'a dusuyoruz (Eligibility bunu reddeder).
        val m = ManifestParser.parse(
            """{"items":[{"id":"x","file":"content/a.mp4","size":1,"sha256":"a","validUntil":"31.12.2026"}]}"""
        )
        assertNull(m.items.first().validUntil)
    }

    @Test fun `url u olmayan app guncellemesi YOK sayilir`() {
        // Aksi halde cihaz bos bir yola istek atip pencere harcardi.
        val m = ManifestParser.parse("""{"app":{"versionCode":9,"size":10}}""")
        assertNull(m.app)
    }
}
