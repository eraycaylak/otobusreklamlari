import { spawn } from 'node:child_process'
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
    '-c:a', 'aac', '-b:a', v.audioBitrate, '-ar', '48000', '-ac', '2',
    '-movflags', '+faststart',
    output
  ]
}

function run (bin, args) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, args)
    let err = ''
    p.stderr.on('data', (d) => { err += d.toString() })
    p.on('error', reject)
    p.on('close', (code) => code === 0 ? resolve() : reject(new Error(`${bin} cikis kodu ${code}: ${err.slice(0, 2000)}`)))
  })
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
  const tmpOut = path.join(paths.incoming, `t-${Date.now()}.mp4`)
  let produced

  if (config.video.skipTranscode) {
    fs.copyFileSync(incomingPath, tmpOut)
    produced = tmpOut
  } else {
    await run(config.video.ffmpeg, ffmpegArgs(incomingPath, tmpOut))
    produced = tmpOut
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
