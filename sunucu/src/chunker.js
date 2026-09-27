import fs from 'node:fs'
import crypto from 'node:crypto'
import { config } from './config.js'

/**
 * Dosyayi sabit boyutlu parcalara boler ve her parcanin SHA-256'sini cikarir.
 *
 * NEDEN: Otobus noktada sadece ~3 dakika duruyor. Baglanti koptugunda yarim dosya
 * silinmiyor; cihaz sadece EKSIK PARCALARI istiyor (HTTP Range). Her parca kendi
 * hash'iyle dogrulandigi icin yarim kalan indirmenin butunlugunden emin olunuyor.
 *
 * OLAY DONGUSU BLOKLANMAZ - BU BIR PERFORMANS SUSU DEGIL, ISLEVSEL BIR SART.
 *
 * Fonksiyon `async` ilan edilmisti ama govdesi TAMAMEN SENKRONDU: readSync +
 * createHash tek bir tick icinde yuz MB'lari isliyordu. Node tek is parcaciklidir,
 * yani o sure boyunca sunucu HICBIR isteye cevap veremez. Tam o anda noktaya giren
 * bir otobus /api/v1/manifest icin zaman asimina ugrar ve 3 dakikalik penceresini
 * TAMAMEN kaybeder - hem de operator sadece "panelden video yukledim" yapmisken.
 * 500 MB'lik bir ham video veya APK'da bu saniyeler suruyor.
 *
 * Cozum: gercek asenkron okuma (fs.promises) + her parcadan sonra tick birakmak.
 * Hash hesabi hala senkron ama tek parca (4 MB) icin milisaniyeler mertebesinde.
 */
export async function buildChunks (filePath, chunkSize = config.chunkSize) {
  const fh = await fs.promises.open(filePath, 'r')
  const st = await fh.stat()
  const size = st.size
  const chunks = []
  try {
    let offset = 0
    let index = 0
    const buf = Buffer.allocUnsafe(chunkSize)
    while (offset < size) {
      const len = Math.min(chunkSize, size - offset)
      /*
       * KISA OKUMA SESSIZCE GECMEZ.
       *
       * readSync istenen kadar okumak ZORUNDA DEGIL. Kontrol edilmedigi icin eksik
       * okunan bir parca, tamponun ONCEKI parcadan kalan baytlariyla birlikte
       * hash'leniyordu. O hash IMZALI manifeste yaziliyor ve cihaz dogru indirdigi
       * parcayi bir daha ASLA kabul edemiyor: icerik her pencerede bastan indirilip
       * ayni yerde tikaniyor, sebebi de hicbir yerde gorunmuyor. Kalici bir zehir.
       */
      const { bytesRead: okunan } = await fh.read(buf, 0, len, offset)
      if (okunan !== len) {
        throw new Error(`parcalama basarisiz: ${filePath} offset ${offset} icin ${len} bayt istendi, ${okunan} okundu`)
      }
      const slice = buf.subarray(0, len)
      chunks.push({
        i: index,
        offset,
        len,
        sha256: crypto.createHash('sha256').update(slice).digest('hex')
      })
      offset += len
      index += 1
      // Diger isteklere tick birak: parcalama sirasinda gelen manifest istegi
      // beklemesin (bkz. yukaridaki not).
      await new Promise((r) => setImmediate(r))
    }
  } finally {
    await fh.close()
  }
  return { size, chunks }
}
