package com.otobusreklam.player.manifest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * IMZA DOGRULAMA - GERCEK SUNUCU ANAHTARI VE GERCEK ZARFLA.
 *
 * Iki risk var ve IKINCISI daha olasidir:
 *  1. Sahte bir manifest KABUL EDILMESI (saldiri).
 *  2. GERCEK bir manifestin REDDEDILMESI (kutuphane/base64/kodlama farki).
 * Ikincisi tum filoyu sessizce senkrondan dusurur ve sunucu tarafinda hicbir iz
 * birakmaz. Bu yuzden fixture elle yazilmadi: `sunucu/scripts/anahtar-uret.js` ile
 * uretilmis anahtar cifti ve o anahtarla imzalanmis GERCEK bir zarf kullaniliyor.
 */
class SignatureVerifierTest {

    private fun kaynak(ad: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(ad)) { "test kaynagi yok: $ad" }
            .bufferedReader().use { it.readText() }

    private val zarf get() = kaynak("manifest-zarf.json")
    private val anahtar get() = kaynak("acik-anahtar.txt").trim()

    @Test fun `gercek zarf gercek anahtarla ACILIR`() {
        val json = SignatureVerifier.open(zarf, anahtar)
        val o = JSONObject(json)
        assertEquals("OTOBUS-014", o.getString("deviceId"))
        // Icerik, zarfin payload'inin TAM baytlari olmali (kanonlastirma yok).
        val payload = JSONObject(zarf).getString("payload")
        assertEquals(String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8), json)
    }

    @Test fun `payload in TEK BAYTI degisirse imza gecmez`() {
        val o = JSONObject(zarf)
        val bytes = java.util.Base64.getDecoder().decode(o.getString("payload"))
        // "OTOBUS-014" -> "OTOBUS-015": tek karakter.
        val metin = String(bytes, Charsets.UTF_8).replace("OTOBUS-014", "OTOBUS-015")
        o.put("payload", java.util.Base64.getEncoder().encodeToString(metin.toByteArray()))
        beklenenRet(o.toString(), anahtar, "degistirilmis payload")
    }

    @Test fun `imzanin TEK BITI degisirse imza gecmez`() {
        val o = JSONObject(zarf)
        val sig = java.util.Base64.getDecoder().decode(o.getString("sig"))
        sig[0] = (sig[0].toInt() xor 1).toByte()
        o.put("sig", java.util.Base64.getEncoder().encodeToString(sig))
        beklenenRet(o.toString(), anahtar, "degistirilmis imza")
    }

    @Test fun `63 baytlik imza reddedilir`() {
        val o = JSONObject(zarf)
        val sig = java.util.Base64.getDecoder().decode(o.getString("sig"))
        o.put("sig", java.util.Base64.getEncoder().encodeToString(sig.copyOf(63)))
        val e = beklenenRet(o.toString(), anahtar, "kisa imza")
        assertTrue("mesaj bayt sayisini soylemeli: ${e.message}", e.message!!.contains("64 bayt"))
    }

    @Test fun `31 baytlik acik anahtar reddedilir`() {
        val ham = java.util.Base64.getDecoder().decode(anahtar)
        val kisa = java.util.Base64.getEncoder().encodeToString(ham.copyOf(31))
        val e = beklenenRet(zarf, kisa, "kisa anahtar")
        assertTrue("mesaj bayt sayisini soylemeli: ${e.message}", e.message!!.contains("32 bayt"))
    }

    @Test fun `BASKA bir anahtar cifti reddedilir - sahte sunucu manifest uretemez`() {
        // Gecerli bicimde ama YANLIS bir 32 baytlik anahtar.
        val yanlis = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
        beklenenRet(zarf, yanlis, "yanlis anahtar")
    }

    @Test fun `beklenmeyen algoritma reddedilir`() {
        val o = JSONObject(zarf).put("alg", "hmac-sha256")
        val e = beklenenRet(o.toString(), anahtar, "yanlis algoritma")
        assertTrue(e.message!!.contains("algoritma"))
    }

    @Test fun `bos payload ve bos imza reddedilir`() {
        beklenenRet(JSONObject(zarf).put("payload", "").toString(), anahtar, "bos payload")
        beklenenRet(JSONObject(zarf).put("sig", "").toString(), anahtar, "bos imza")
    }

    @Test fun `base64 olmayan deger reddedilir`() {
        val e = beklenenRet(JSONObject(zarf).put("sig", "bu base64 degil!!!").toString(), anahtar, "bozuk base64")
        assertTrue(e.message!!.contains("base64"))
    }

    private fun beklenenRet(zarfJson: String, anahtarB64: String, ne: String): SignatureVerifier.InvalidSignature {
        try {
            SignatureVerifier.open(zarfJson, anahtarB64)
        } catch (e: SignatureVerifier.InvalidSignature) {
            return e
        }
        fail("$ne KABUL EDILDI - imza dogrulamasi gecirilebilir demektir")
        error("ulasilamaz")
    }
}
