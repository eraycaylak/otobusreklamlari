import fs from 'node:fs'
import crypto from 'node:crypto'
import { config } from './config.js'

/**
 * Dosyayi sabit boyutlu parcalara boler ve her parcanin SHA-256'sini cikarir.
 *
 * NEDEN: Otobus noktada sadece ~3 dakika duruyor. Baglanti koptugunda yarim dosya
 * silinmiyor; cihaz sadece EKSIK PARCALARI istiyor (HTTP Range). Her parca kendi
 * hash'iyle dogrulandigi icin yarim kalan indirmenin butunlugunden emin olunuyor.
 */
export async function buildChunks (filePath, chunkSize = config.chunkSize) {
  const size = fs.statSync(filePath).size
  const chunks = []
  const fd = fs.openSync(filePath, 'r')
  try {
    let offset = 0
    let index = 0
    const buf = Buffer.allocUnsafe(chunkSize)
    while (offset < size) {
      const len = Math.min(chunkSize, size - offset)
      fs.readSync(fd, buf, 0, len, offset)
      const slice = buf.subarray(0, len)
      chunks.push({
        i: index,
        offset,
        len,
        sha256: crypto.createHash('sha256').update(slice).digest('hex')
      })
      offset += len
      index += 1
    }
  } finally {
    fs.closeSync(fd)
  }
  return { size, chunks }
}
