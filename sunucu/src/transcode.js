import { spawn } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { config, paths } from './config.js'
import { sha256File } from './crypto.js'
import { buildChunks } from './chunker.js'
import { db, save, bumpPlaylistVersion } from './store.js'

/**
 * Tek standarda cevirme.
 *
 * Bu adim projenin en buyuk kazanci: 50 MB'lik bir reklam ~10 MB'a duser.
 * 3 dakikalik pencerenin yetmesinin tek sebebi budur.
 *
 * Kurallar:
 *  - H.264 High profile + yuv420p  -> ucuz Android stick'lerin HEPSI donanimda cozer
 *  - H.265/HEVC KULLANILMAZ        -> bazi stick'ler cozemez, CPU'ya duser, kare atlar
 *  - Tum ciktilar ayni cozunurluk/fps -> oynaticida gecislerde siyah kare olmaz
 *  - +faststart                    -> moov atomu basa gelir
 */
function ffmpegArgs (input, output) {
  const v = config.video
  return [
    '-hide_banner', '-loglevel', 'error', '-y',
    '-i', input,
    '-c:v', 'libx264', '-profile:v', 'high', '-level', '4.0', '-preset', 'slow',
    '-b:v', v.videoBitrate, '-maxrate', v.maxrate, '-bufsize', v.bufsize,
    '-vf', `scale=${v.width}:${v.height}:force_original_aspect_ratio=decrease,` +
           `pad=${v.width}:${v.height}:(ow-iw)/2:(oh-ih)/2,fps=${v.fps}`,
    '-pix_fmt', 'yuv420p', '-g', String(v.fps * 2), '-sc_threshold', '0',
    // SES: varsayilan olarak TAMAMEN ATILIYOR (-an). Oynatici sessiz calistigi icin
    // ses akisini kodlamak, asla duyulmayacak ~128 kbit/s'yi her dosyaya eklemekti -
    // 3 dakikalik pencereye sigmak icin kurulmus bir sistemde dosyanin ~%5'i.
    // Gerekcesi ve acma yolu: config.js -> video.audio
    ...(v.audio
      ? ['-c:a', 'aac', '-b:a', v.audioBitrate, '-ar', '48000', '-ac', '2']
      : ['-an']),
    '-movflags', '+faststart',
    output
  ]
}

const TRANSCODE_TIMEOUT_MS = Number(process.env.TRANSCODE_TIMEOUT_MS || 30 * 60 * 1000)

/**
 * Alt surec calistir - ZAMAN ASIMIYLA.
 *
 * Bozuk veya tuhaf kodlanmis bir video ffmpeg'i sonsuza kadar askida birakabilir.
 * Zaman asimi olmadan: istek hic bitmez, gecici dosya kalir ve asili ffmpeg
 * surecleri birikerek sunucunun CPU'sunu yer. Sahada bu "panel yavasladi, sonra
 * durdu" seklinde gorunur ve sebebi bulunamaz.
 */
function run (bin, args, timeoutMs = TRANSCODE_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, args)
    let err = ''
    let zamanAsti = false

    const zamanlayici = setTimeout(() => {
      zamanAsti = true
      p.kill('SIGKILL')
    }, timeoutMs)

    p.stderr.on('data', (d) => { err += d.toString() })
    p.on('error', (e) => { clearTimeout(zamanlayici); reject(e) })
    p.on('close', (code) => {
      clearTimeout(zamanlayici)
      if (zamanAsti) {
        reject(new Error(`${bin} ${Math.round(timeoutMs / 1000)} saniyede bitmedi, durduruldu (video bozuk olabilir)`))
      } else if (code === 0) {
        resolve()
      } else {
        reject(new Error(`${bin} cikis kodu ${code}: ${err.slice(0, 2000)}`))
      }
    })
  })
}

/**
 * Ayni anda kac transcode calisabilir.
 *
 * ffmpeg -preset slow tum cekirdekleri doyurur. Kucuk bir mini PC'de iki buyuk
 * video ayni anda gelince sunucu kilitlenir ve o sirada noktadan manifest isteyen
 * otobusler zaman asimina ugrar - yani PENCERE BOSA GIDER. Siraya aliyoruz.
 */
const MAX_ESZAMANLI = Math.max(1, Number(process.env.MAX_CONCURRENT_TRANSCODE || 1))
let calisan = 0
const kuyruk = []

async function sirayaAl (is) {
  if (calisan >= MAX_ESZAMANLI) {
    await new Promise((resolve) => kuyruk.push(resolve))
  }
  calisan += 1
  try {
    return await is()
  } finally {
    calisan -= 1
    const sonraki = kuyruk.shift()
    if (sonraki) sonraki()
  }
}

const PROBE_TIMEOUT_MS = Number(process.env.PROBE_TIMEOUT_MS || 60 * 1000)

/**
 * Video suresini ol.
 *
 * ZAMAN ASIMI VE CIKIS KODU KONTROLU SART - iki ayri sessiz ariza vardi:
 *
 *  1. Zaman asimi yoktu. run()'a bilincli olarak zaman asimi eklenmisti ("bozuk video
 *     ffmpeg'i sonsuza kadar askida birakabilir") ama ffprobe AYNI bozuk dosya
 *     uzerinde korumasiz calisiyordu. Tuhaf bir moov atomu ffprobe'u kilitleyince
 *     POST /api/admin/upload istegi HIC yanit vermiyor, panelde spinner sonsuza kadar
 *     donuyordu - yani run()'daki onlemin butun amaci bu adimda geri geliyordu.
 *
 *  2. `code` hic kontrol edilmiyordu. ffprobe kurulu degilse (ffmpeg var, ffprobe yok -
 *     paketleme farkliliklarinda olur) veya 1 ile cikarsa parseFloat('') = NaN olup
 *     sessizce 0 donuyordu. durationMs=0 manifeste gidiyor, cihaz fatura suresini
 *     olculen zamana dusuruyor ve report.csv toplam_saniye kolonu hatali cikiyordu -
 *     tek bir uyari bile olmadan.
 *
 * Artik: zaman asiminda ve sifirdan farkli cikis kodunda HATA firlatir; cagiran taraf
 * bunu operatore gosterilecek bir isarete cevirir.
 */
async function probeDurationMs (file) {
  const out = await new Promise((resolve, reject) => {
    const p = spawn(config.video.ffprobe, [
      '-v', 'error', '-show_entries', 'format=duration',
      '-of', 'default=noprint_wrappers=1:nokey=1', file
    ])
    let s = ''
    let err = ''
    let zamanAsti = false
    const zamanlayici = setTimeout(() => { zamanAsti = true; p.kill('SIGKILL') }, PROBE_TIMEOUT_MS)
    p.stdout.on('data', (d) => { s += d.toString() })
    p.stderr.on('data', (d) => { err += d.toString() })
    p.on('error', (e) => { clearTimeout(zamanlayici); reject(e) })
    p.on('close', (code) => {
      clearTimeout(zamanlayici)
      if (zamanAsti) {
        reject(new Error(`ffprobe ${Math.round(PROBE_TIMEOUT_MS / 1000)} saniyede bitmedi, durduruldu (video bozuk olabilir)`))
      } else if (code !== 0) {
        reject(new Error(`ffprobe cikis kodu ${code}: ${err.slice(0, 500)}`))
      } else {
        resolve(s.trim())
      }
    })
  })
  const sec = parseFloat(out)
  if (!Number.isFinite(sec) || sec <= 0) {
    throw new Error(`ffprobe sure dondurmedi (cikti: "${out.slice(0, 120)}")`)
  }
  return Math.round(sec * 1000)
}

/**
 * Yuklenen ham dosyayi isler ve kutuphaneye kaydeder.
 * Dosya adi ICERIK ADRESLI'dir (sha256): ayni dosya iki kez inmez, onbellek bayatlamaz.
 */
export async function ingest (incomingPath, originalName) {
  // Rastgele son ek: ayni milisaniyede gelen iki yukleme ayni gecici dosyayi
  // kullanip birbirinin ciktisini bozmasin.
  const tmpOut = path.join(paths.incoming, `t-${Date.now()}-${crypto.randomBytes(4).toString('hex')}.mp4`)
  let produced

  try {
    if (config.video.skipTranscode) {
      fs.copyFileSync(incomingPath, tmpOut)
    } else {
      await sirayaAl(() => run(config.video.ffmpeg, ffmpegArgs(incomingPath, tmpOut)))
    }
    produced = tmpOut
  } catch (e) {
    // Yarim kalan ciktiyi birakma: aksi halde incoming/ dizini zamanla dolar
    fs.rmSync(tmpOut, { force: true })
    throw e
  }

  /*
   * TUM HESAP GECICI DOSYA UZERINDE YAPILIR, TASIMA EN SONDA.
   *
   * Eski sira: rename -> buildChunks -> ffprobe -> save. Aradaki bir hata (bozuk
   * CHUNK_SIZE, kilitlenen ffprobe, dolu disk) content/ altinda KAYDI OLMAYAN bir
   * dosya birakiyordu: /temizlik onu goremiyor (s.items'ta yok), kimse silmiyor ve her
   * yeniden denemede bir kopya daha birikiyordu. Once hesapla, sonra tasi: hata
   * durumunda content/ hic dokunulmamis kalir.
   */
  const sha = await sha256File(produced)
  const finalName = `${sha}.mp4`
  const finalPath = path.join(paths.content, finalName)

  let size, chunks, durationMs
  let sureBilinmiyor = false
  try {
    ;({ size, chunks } = await buildChunks(produced))
    try {
      durationMs = await probeDurationMs(produced)
    } catch (e) {
      /*
       * Sure olculemedi: yuklemeyi REDDETMIYORUZ (dosya isleniyor, oynatilabilir) ama
       * SESSIZ de gecmiyoruz. durationMs=0 ile devam etmek fatura suresini olculen
       * zamana dusurur; operator bunu bilmek zorunda - item'a isaret koyup panelde
       * kirmizi gosteriyoruz.
       */
      console.warn(`[transcode] sure olculemedi (${originalName}): ${e.message}`)
      durationMs = 0
      sureBilinmiyor = true
    }
  } catch (e) {
    fs.rmSync(produced, { force: true })
    throw e
  }

  if (!fs.existsSync(finalPath)) fs.renameSync(produced, finalPath)
  else fs.rmSync(produced, { force: true })
  fs.rmSync(incomingPath, { force: true })

  const s = db()
  s.items[sha] = {
    sha256: sha,
    file: `content/${finalName}`,
    size,
    durationMs,
    ...(sureBilinmiyor ? { durationBilinmiyor: true } : {}),
    chunks,
    originalName,
    createdAt: new Date().toISOString()
  }
  save()
  bumpPlaylistVersion()
  return s.items[sha]
}
