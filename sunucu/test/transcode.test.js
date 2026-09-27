import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/**
 * Transcode dayanikliligi.
 *
 * NEDEN HTTP UZERINDEN DEGIL: ingest() dogrudan cagriliyor.
 * Bu testler kacinilmaz olarak alt surec (ffmpeg) dogurur. Bazi kum havuzu
 * ortamlarinda, SUREC ICI bir HTTP istegi beklerken alt surec dogurmak Node'un
 * fetch istemcisini askida birakiyor - urun hatasi degil, kosum ortami etkilesimi
 * (gercek dagitimda istemci ile sunucu ayri sureclerdedir).
 *
 * Dogrudan cagri hem bu etkilesimden kaciniyor hem de test ettigimiz sey zaten
 * HTTP katmani degil, transcode dayanikliligi.
 */

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'reklam-tc-'))
let ingest, paths

before(async () => {
  const asili = path.join(DATA, 'asili-ffmpeg.sh')
  fs.writeFileSync(asili, '#!/bin/sh\nexec sleep 300\n')
  fs.chmodSync(asili, 0o755)

  process.env.NODE_ENV = 'test'
  process.env.DATA_DIR = DATA
  process.env.SKIP_TRANSCODE = '0'          // gercek transcode yolu
  process.env.FFMPEG_BIN = asili
  process.env.TRANSCODE_TIMEOUT_MS = '800'

  const cfg = await import('../src/config.js')
  cfg.ensureDirs()
  paths = cfg.paths
  ingest = (await import('../src/transcode.js')).ingest
})

after(() => {
  fs.rmSync(DATA, { recursive: true, force: true })
})

function girdiYaz (ad) {
  const p = path.join(paths.incoming, ad)
  fs.writeFileSync(p, Buffer.alloc(4096, 7))
  return p
}

test('asili kalan ffmpeg zaman asiminda oldurulur', async () => {
  // Zaman asimi olmasaydi bu cagri HIC donmezdi: istek asili kalir, gecici dosya
  // birikir ve asili ffmpeg surecleri sunucunun CPU'sunu yerdi. Sahada bu
  // "panel yavasladi, sonra durdu" seklinde gorunur ve sebebi bulunamaz.
  const girdi = girdiYaz('bozuk.mp4')
  const basla = Date.now()

  await assert.rejects(
    () => ingest(girdi, 'bozuk.mp4'),
    (e) => {
      assert.match(e.message, /bitmedi|durduruldu/, `beklenmeyen hata: ${e.message}`)
      return true
    }
  )

  const gecen = Date.now() - basla
  assert.ok(gecen < 10000, `zaman asimi calismadi, ${gecen}ms surdu`)
  assert.ok(gecen >= 700, `cok erken dondu (${gecen}ms) - zaman asimi gercekten beklemis mi?`)
})

test('basarisiz transcode gecici dosya birakmaz', () => {
  // incoming/ dizini zamanla dolup diski tuketmemeli
  const kalanlar = fs.readdirSync(paths.incoming).filter((f) => f.startsWith('t-'))
  assert.deepEqual(kalanlar, [], `gecici cikti kalmis: ${kalanlar.join(', ')}`)
})

test('ayni anda gelen yuklemeler ayni gecici dosyayi kullanmaz', async () => {
  // Gecici ad Date.now() + RASTGELE son ek. Sadece Date.now() olsaydi ayni
  // milisaniyede gelen iki yukleme birbirinin ciktisini bozardi.
  const a = girdiYaz('a.mp4')
  const b = girdiYaz('b.mp4')

  const sonuclar = await Promise.allSettled([ingest(a, 'a.mp4'), ingest(b, 'b.mp4')])
  assert.equal(sonuclar.length, 2)
  for (const s of sonuclar) {
    assert.equal(s.status, 'rejected', 'sahte ffmpeg her ikisini de basarisiz kilmali')
  }
  // Ikisi de temizlenmis olmali
  const kalanlar = fs.readdirSync(paths.incoming).filter((f) => f.startsWith('t-'))
  assert.deepEqual(kalanlar, [], `gecici cikti kalmis: ${kalanlar.join(', ')}`)
})

test('transcode kuyrugu ikinci isi bloklamaz (sira ilerliyor)', async () => {
  // MAX_CONCURRENT_TRANSCODE=1 ile ikinci is birincinin bitmesini bekler.
  // Kuyruk mantigi hatali olsaydi ikinci is SONSUZA KADAR beklerdi.
  const girdi = girdiYaz('c.mp4')
  const basla = Date.now()
  await assert.rejects(() => ingest(girdi, 'c.mp4'))
  const gecen = Date.now() - basla
  assert.ok(gecen < 10000, `kuyruk kilitlendi (${gecen}ms)`)
})
