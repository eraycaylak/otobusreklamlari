import crypto from 'node:crypto'
import fs from 'node:fs'
import { config } from './config.js'

/**
 * Ed25519 imzalama.
 *
 * ONEMLI TASARIM KARARI:
 * Manifest JSON'unun "kanonik" halini imzalamaya calismiyoruz (anahtar sirasi,
 * bosluk, unicode kacislari yuzunden iki taraf ayni baytlari uretemez).
 * Bunun yerine sunucu manifest'in TAM BAYTLARINI base64'leyip zarfa koyuyor,
 * imza da tam o baytlarin uzerine atiliyor:
 *
 *   { "alg": "ed25519", "payload": "<base64(manifest json baytlari)>", "sig": "<base64(64 bayt imza)>" }
 *
 * Cihaz: payload'i coz -> imzayi DOGRULA -> sonra JSON parse et.
 * Boylece kanonlastirma problemi tamamen ortadan kalkiyor.
 */

/*
 * ANAHTAR CIFTI TEK SEFERDE, BIRLIKTE VE DOGRULANARAK YUKLENIR.
 *
 * Eski hali: ozel anahtar bir kez onbellege aliniyor, acik anahtar HER cagrida
 * diskten okunuyordu. Aralarinda hicbir tutarlilik kontrolu yoktu.
 *
 * Somut ariza: operator servisi durdurmadan `anahtar-uret --force` calistirir.
 * GET /api/admin/public-key diskteki YENI anahtari doner, operator onu APK'ya gomer
 * ve filoya dagitir - ama sunucu hala ESKI ozel anahtarla imzalar. Her cihaz her
 * manifestte imza dogrulamasindan duser, hicbir icerik/guncelleme inmez ve sunucu
 * tarafinda TEK BIR hata log'u bile olusmaz (200 donuyor, imza "gecerli" gorunuyor).
 * Filo sessizce oluyor, sebep sunucu yeniden baslayinca kayboluyor.
 *
 * Cozum: ikisi ayni anda yuklenir, acilista imzala-dogrula self-test'i yapilir,
 * eslesmiyorsa surec HIC baslamaz. Boylece "sessiz filo olumu" yerine "sunucu
 * acilmiyor ve sebebini yaziyor" elde edilir.
 */
let anahtarlar = null

function anahtarlariYukle () {
  if (anahtarlar) return anahtarlar

  let ozelPem, acikPem
  try {
    ozelPem = fs.readFileSync(config.keys.privatePath, 'utf8')
  } catch (e) {
    throw new Error(`ozel anahtar okunamadi (${e.code || e.message}): ${config.keys.privatePath}`)
  }
  try {
    acikPem = fs.readFileSync(config.keys.publicPath, 'utf8')
  } catch (e) {
    throw new Error(`acik anahtar okunamadi (${e.code || e.message}): ${config.keys.publicPath}`)
  }

  const priv = crypto.createPrivateKey(ozelPem)
  const pub = crypto.createPublicKey(acikPem)
  const der = pub.export({ format: 'der', type: 'spki' })
  // SPKI DER = 12 bayt baslik + 32 bayt ham anahtar
  const raw = der.subarray(der.length - 32)

  // SELF-TEST: cift gercekten eslesiyor mu? Eslesmiyorsa dagitilan acik anahtar
  // imzalari dogrulamaz - yani tum filo senkron olamaz. Simdi patla.
  const deneme = Buffer.from('anahtar-cifti-self-test', 'utf8')
  const imza = crypto.sign(null, deneme, priv)
  if (!crypto.verify(null, deneme, pub, imza)) {
    throw new Error(
      'ANAHTAR CIFTI ESLESMIYOR: ozel anahtarin imzasi acik anahtarla dogrulanmiyor. ' +
      `Sunucu baslatilmiyor (aksi halde tum filo sessizce senkron olamaz). ` +
      `Dosyalar: ${config.keys.privatePath} / ${config.keys.publicPath}`
    )
  }

  anahtarlar = { priv, raw }
  return anahtarlar
}

function privateKey () {
  return anahtarlariYukle().priv
}

/** Ed25519 acik anahtarini ham 32 bayt olarak dondurur (Android tarafi bunu bekler). */
export function publicKeyRaw () {
  return anahtarlariYukle().raw
}

/**
 * Anahtar onbellegini bosalt.
 *
 * Rotasyondan sonra dogru yol servisi yeniden baslatmaktir (systemd yapar); bu
 * fonksiyon testler ve `anahtarlariDogrula()` acilis kontrolu icindir.
 */
export function _anahtarOnbellegiBosalt () {
  anahtarlar = null
}

/** Acilista cagrilir: anahtarlar yuklenemiyorsa/eslesmiyorsa surec hic baslamasin. */
export function anahtarlariDogrula () {
  anahtarlariYukle()
}

export function publicKeyBase64 () {
  return publicKeyRaw().toString('base64')
}

/** Verilen nesneyi imzalanmis zarf olarak paketler. */
export function signEnvelope (obj) {
  const payload = Buffer.from(JSON.stringify(obj), 'utf8')
  const sig = crypto.sign(null, payload, privateKey())
  return {
    alg: 'ed25519',
    payload: payload.toString('base64'),
    sig: sig.toString('base64')
  }
}

/** Test ve dogrulama icin: zarfi acik anahtarla dogrula ve icerigi dondur. */
export function verifyEnvelope (envelope, rawPublicKey = publicKeyRaw()) {
  if (!envelope || envelope.alg !== 'ed25519') return null
  const payload = Buffer.from(envelope.payload, 'base64')
  const sig = Buffer.from(envelope.sig, 'base64')
  const key = crypto.createPublicKey({
    key: Buffer.concat([
      // SPKI DER basligi (Ed25519)
      Buffer.from('302a300506032b6570032100', 'hex'),
      rawPublicKey
    ]),
    format: 'der',
    type: 'spki'
  })
  if (!crypto.verify(null, payload, key, sig)) return null
  return JSON.parse(payload.toString('utf8'))
}

export function sha256File (filePath) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256')
    const s = fs.createReadStream(filePath)
    s.on('data', (d) => h.update(d))
    s.on('error', reject)
    s.on('end', () => resolve(h.digest('hex')))
  })
}

export function sha256Buffer (buf) {
  return crypto.createHash('sha256').update(buf).digest('hex')
}

export function randomToken (bytes = 24) {
  return crypto.randomBytes(bytes).toString('base64url')
}

/** Sabit zamanli karsilastirma - token dogrulamasinda zamanlama sizintisini onler. */
export function safeEqual (a, b) {
  const ba = Buffer.from(String(a))
  const bb = Buffer.from(String(b))
  if (ba.length !== bb.length) return false
  return crypto.timingSafeEqual(ba, bb)
}
