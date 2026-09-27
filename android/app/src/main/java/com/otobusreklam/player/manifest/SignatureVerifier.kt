package com.otobusreklam.player.manifest

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Manifest imza dogrulamasi.
 *
 * NEDEN ZARF: JSON'u "kanonik" hale getirip imzalamak klasik bir tuzaktir -
 * anahtar sirasi, bosluk ve unicode kacislari yuzunden iki taraf asla ayni
 * baytlari uretemez. Bu yuzden sunucu manifest'in TAM BAYTLARINI base64'leyip
 * zarfa koyuyor, imza da tam o baytlarin uzerine atiliyor:
 *
 *   { "alg":"ed25519", "payload":"<base64>", "sig":"<base64>" }
 *
 * Cihaz once imzayi dogrular, SONRA JSON'u ayristirir.
 *
 * Neden java.security "Ed25519" degil: o saglayici sadece API 33+ te var.
 * Bu kutuphane API 24'ten itibaren ayni sekilde calisir.
 */
object SignatureVerifier {

    private val curveSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    class InvalidSignature(message: String) : Exception(message)

    /**
     * Zarfi dogrular ve icindeki manifest JSON'unu dondurur.
     * Imza tutmazsa istisna firlatir - CAGIRAN TARAF BUNU YUTMAMALI:
     * imzasiz/bozuk manifest geldiginde hicbir sey indirilmez, mevcut liste calmaya devam eder.
     */
    fun open(envelopeJson: String, publicKeyBase64: String): String {
        val envelope = JSONObject(envelopeJson)

        val alg = envelope.optString("alg")
        if (alg != "ed25519") throw InvalidSignature("beklenmeyen algoritma: $alg")

        val payload = decode(envelope.optString("payload"), "payload")
        val signature = decode(envelope.optString("sig"), "sig")
        val publicKey = decode(publicKeyBase64, "acik anahtar")

        if (publicKey.size != 32) {
            throw InvalidSignature("acik anahtar 32 bayt olmali, ${publicKey.size} geldi")
        }
        if (signature.size != 64) {
            throw InvalidSignature("imza 64 bayt olmali, ${signature.size} geldi")
        }
        if (!verify(payload, signature, publicKey)) {
            throw InvalidSignature("imza dogrulanamadi")
        }
        return String(payload, Charsets.UTF_8)
    }

    private fun verify(payload: ByteArray, signature: ByteArray, rawPublicKey: ByteArray): Boolean =
        try {
            val key = EdDSAPublicKey(EdDSAPublicKeySpec(rawPublicKey, curveSpec))
            val engine = EdDSAEngine(MessageDigest.getInstance(curveSpec.hashAlgorithm))
            engine.initVerify(key)
            engine.update(payload)
            engine.verify(signature)
        } catch (_: Exception) {
            false
        }

    /*
     * java.util.Base64 - android.util.Base64 DEGIL.
     *
     * android.util.Base64 platform sinifidir: JVM birim testinde govdesi yoktur.
     * Eski halde bu, iki kotu secenekten birine zorluyordu - ya test hic yazilamaz
     * ya da `isReturnDefaultValues = true` ile cagri SESSIZCE null/0 doner ve test
     * hicbir sey dogrulamadan GECER. Bu sinifin testi tam olarak "sahte manifest
     * kabul edilmiyor, GERCEK manifest reddedilmiyor" sorusunu kapatiyor; sessizce
     * gecen bir test burada en kotu sonucu verir.
     *
     * java.util.Base64 API 26+ ve minSdk 24 - ama coreLibraryDesugaring acik, yani
     * API 24/25'te de calisir. Ayrica bu sinif JVM'de gercek govdeye sahip oldugu icin
     * birim testi ANLAMLI olur.
     */
    private fun decode(value: String?, label: String): ByteArray {
        if (value.isNullOrBlank()) throw InvalidSignature("$label bos")
        return try {
            java.util.Base64.getDecoder().decode(value.trim())
        } catch (_: Exception) {
            throw InvalidSignature("$label base64 degil")
        }
    }
}
