/**
 * Kimlik dogrulama korumalari ve girdi temizligi.
 */

const attempts = new Map()   // ip -> { count, until }

const WINDOW_MS = 15 * 60 * 1000
const MAX_FAILURES = 10
const LOCK_MS = 15 * 60 * 1000

/**
 * Basarisiz kimlik denemelerini sinirlar.
 *
 * Admin tokeni tek bir dizedir; sinirsiz deneme hakki verilirse zamanla
 * denenebilir. 15 dakikada 10 basarisiz denemeden sonra o IP 15 dakika kilitlenir.
 */
export function tooManyFailures (ip) {
  const rec = attempts.get(ip)
  if (!rec) return false
  if (Date.now() > rec.until) { attempts.delete(ip); return false }
  return rec.count >= MAX_FAILURES
}

export function noteFailure (ip) {
  const now = Date.now()
  const rec = attempts.get(ip)
  if (!rec || now > rec.until) {
    attempts.set(ip, { count: 1, until: now + WINDOW_MS })
    return
  }
  rec.count += 1
  if (rec.count >= MAX_FAILURES) rec.until = now + LOCK_MS
}

export function noteSuccess (ip) {
  attempts.delete(ip)
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
