/**
 * Kimlik dogrulama korumalari ve girdi temizligi.
 */

import crypto from 'node:crypto'

const attempts = new Map()   // "kapsam|anahtar" -> { count, until }

const WINDOW_MS = 15 * 60 * 1000
const LOCK_MS = 15 * 60 * 1000

/**
 * DENEME SINIRLARI KAPSAMA GORE AYRI.
 *
 * Onceden tek bir IP-bazli sayac vardi ve cihaz ile admin kimlik dogrulamasi onu
 * PAYLASIYORDU. Bu mimaride TUM otobusler noktadaki tek PtMP linkinin arkasinda,
 * yani sunucuya AYNI IP'den goruluyorlar. Sonuc: tokeni iptal edilmis ya da yanlis
 * provizyonlanmis TEK BIR otobus, 15 dakikada 10 deneme yapip o noktadaki BUTUN
 * otobusleri 15 dakika kilitliyordu. Her otobus gunde birkac dakikalik pencereye
 * sahip oldugu icin bu, tek bozuk cihazin tum hattin yayinini durdurmasi demekti.
 * Ustelik sebep sunucu loglarinda "429" olarak gorunur ve asil cihaz belli olmaz.
 *
 * Yeni tasarim iki katmanli:
 *   device : SUNULAN TOKENIN parmak izine gore - bozuk cihaz yalnizca kendini kilitler
 *   ip     : paylasilan cikis IP'sine gore, COK YUKSEK esikle - kaba kuvvet yine
 *            sinirli (cihaz tokenlari uzun ve rastgele) ama komsu otobusler etkilenmez
 *   admin  : IP'ye gore, DAR esikle - admin tokeni tek bir dizedir ve panel tek yerden
 *            kullanilir; burada siki olmanin bir maliyeti yok
 */
const LIMITS = {
  admin: 10,
  device: 20,
  ip: 200
}

function anahtar (scope, key) {
  return `${scope}|${key}`
}

function limit (scope) {
  return LIMITS[scope] ?? 10
}

/** Token'i LOGA VE BELLEGE yazmadan kimliklendir. */
export function tokenFingerprint (token) {
  if (!token) return 'yok'
  return crypto.createHash('sha256').update(String(token)).digest('hex').slice(0, 16)
}

export function tooManyFailures (scope, key) {
  const rec = attempts.get(anahtar(scope, key))
  if (!rec) return false
  if (Date.now() > rec.until) { attempts.delete(anahtar(scope, key)); return false }
  return rec.count >= limit(scope)
}

export function noteFailure (scope, key) {
  const now = Date.now()
  if (attempts.size > 5000) budaEskileri()
  const k = anahtar(scope, key)
  const rec = attempts.get(k)
  if (!rec || now > rec.until) {
    attempts.set(k, { count: 1, until: now + WINDOW_MS })
    return
  }
  rec.count += 1
  if (rec.count >= limit(scope)) rec.until = now + LOCK_MS
}

export function noteSuccess (scope, key) {
  attempts.delete(anahtar(scope, key))
}

/** Testler icin: sayaclari sifirla. */
export function resetFailures () {
  attempts.clear()
}

/**
 * Suresi dolmus kayitlari temizle.
 *
 * Map aksi halde her goren IP icin bir giris tutar ve hic kuculmezdi; internete
 * acik bir sunucuda bu yavas ama kesin bir bellek sizintisidir.
 */
function budaEskileri () {
  const now = Date.now()
  for (const [k, rec] of attempts) {
    if (now > rec.until) attempts.delete(k)
  }
}

/**
 * Express 4 ASYNC HANDLER TUZAGI.
 *
 * Express 4, async bir handler REDDEDERSE bunu yakalamaz: hata Express'in hata
 * zincirine hic ulasmaz, Node'un unhandledRejection'ina duser. Node 15'ten beri
 * varsayilan davranis SURECI SONLANDIRMAKTIR - yani tek bir beklenmedik hata
 * (disk dolu, izin sorunu, bozuk dosya) tum yayin sunucusunu dusururdu.
 *
 * Bu sarmalayici async handler'lari Express'in senkron sozlesmesine baglar.
 * (Express 5 bunu kendisi yapar; 4'te elle gerekir.)
 */
export function tut (handler) {
  return (req, res, next) => {
    Promise.resolve(handler(req, res, next)).catch(next)
  }
}

/**
 * Kimlik dizgilerinin (cihaz id, kampanya id) guvenli oldugundan emin ol.
 *
 * Bu degerler hem NESNE ANAHTARI hem de DOSYA ADI olarak kullaniliyor.
 * Serbest birakmak iki ayri riske yol acar: prototip kirlenmesi ("__proto__")
 * ve dosya yolu kacisi.
 */
const ID_PATTERN = /^[A-Za-z0-9._:-]{1,64}$/
const FORBIDDEN = new Set(['__proto__', 'constructor', 'prototype'])

export function validId (value) {
  if (typeof value !== 'string') return false
  if (FORBIDDEN.has(value)) return false
  if (value === '.' || value === '..') return false
  return ID_PATTERN.test(value)
}

/**
 * CSV enjeksiyonunu onle.
 *
 * Reklamveren adi kullanici girdisidir. "=cmd|..." gibi bir deger Excel'de
 * FORMUL olarak calisir. Basindaki tehlikeli karakterleri notrlestiriyoruz.
 */
export function csvSafe (value) {
  const s = String(value ?? '')
  const cleaned = s.replace(/[;\r\n]/g, ' ')
  return /^[=+\-@\t]/.test(cleaned) ? `'${cleaned}` : cleaned
}

/** Log satirinda token gorunmesin. */
export function redactUrl (url) {
  return String(url).replace(/([?&](?:token|admin_token)=)[^&]*/gi, '$1***')
}
