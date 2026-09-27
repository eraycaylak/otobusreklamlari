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

async function probeDurationMs (file) {
  try {
    const out = await new Promise((resolve, reject) => {
      const p = spawn(config.video.ffprobe, [
        '-v', 'error', '-show_entries', 'format=duration',
        '-of', 'default=noprint_wrappers=1:nokey=1', file
      ])
      let s = ''
      p.stdout.on('data', (d) => { s += d.toString() })
      p.on('error', reject)
      p.on('close', () => resolve(s.trim()))
    })
    const sec = parseFloat(out)
    return Number.isFinite(sec) ? Math.round(sec * 1000) : 0
  } catch {
    return 0
  }
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

  const sha = await sha256File(produced)
  const finalName = `${sha}.mp4`
  const finalPath = path.join(paths.content, finalName)
  if (!fs.existsSync(finalPath)) fs.renameSync(produced, finalPath)
  else fs.rmSync(produced, { force: true })
  fs.rmSync(incomingPath, { force: true })

  const { size, chunks } = await buildChunks(finalPath)
  const durationMs = await probeDurationMs(finalPath)

  const s = db()
  s.items[sha] = {
    sha256: sha,
    file: `content/${finalName}`,
    size,
    durationMs,
    chunks,
    originalName,
    createdAt: new Date().toISOString()
  }
  save()
  bumpPlaylistVersion()
  return s.items[sha]
}
