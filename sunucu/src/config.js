import path from 'node:path'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')

/**
 * Tum ayarlar ortam degiskeninden okunur, hepsinin makul varsayilani vardir.
 * Uretimde en az ADMIN_TOKEN degistirilmelidir.
 */
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data')

export const config = {
  port: Number(process.env.PORT || 8080),
  dataDir: DATA_DIR,

  // Yonetim paneli / API anahtari
  adminToken: process.env.ADMIN_TOKEN || 'degistir-beni',

  // Transcode hedefi. Otobus ekrani 18-22 inc; 2.5 Mbps fazlasiyla yeterli.
  video: {
    videoBitrate: process.env.VIDEO_BITRATE || '2500k',
    maxrate: process.env.VIDEO_MAXRATE || '3500k',
    bufsize: process.env.VIDEO_BUFSIZE || '5000k',
    width: Number(process.env.VIDEO_WIDTH || 1920),
    height: Number(process.env.VIDEO_HEIGHT || 1080),
    fps: Number(process.env.VIDEO_FPS || 25),
    audioBitrate: process.env.AUDIO_BITRATE || '128k',
    ffmpeg: process.env.FFMPEG_BIN || 'ffmpeg',
    ffprobe: process.env.FFPROBE_BIN || 'ffprobe',
    // Test ortaminda ffmpeg yoksa yuklenen dosya oldugu gibi kabul edilir.
    skipTranscode: process.env.SKIP_TRANSCODE === '1'
  },

  // Parca boyutu. 4 MB: kopma aninda kaybedilen is kucuk, manifest sismiyor.
  chunkSize: Number(process.env.CHUNK_SIZE || 4 * 1024 * 1024),

  // Manifest imzalama anahtarlari (varsayilan olarak dataDir altinda)
  keys: {
    privatePath: process.env.PRIVATE_KEY_PATH || path.join(DATA_DIR, 'keys', 'ed25519-private.pem'),
    publicPath: process.env.PUBLIC_KEY_PATH || path.join(DATA_DIR, 'keys', 'ed25519-public.pem')
  },

  // Uygulama guncellemesi icin kademeli yayim: cihazlar 1..N grubuna dagitilir.
  rolloutGroups: Number(process.env.ROLLOUT_GROUPS || 4)
}

export const paths = {
  root: ROOT,
  data: config.dataDir,
  db: path.join(config.dataDir, 'db.json'),
  keys: path.join(config.dataDir, 'keys'),
  incoming: path.join(config.dataDir, 'incoming'),
  content: path.join(config.dataDir, 'content'),
  app: path.join(config.dataDir, 'app'),
  logs: path.join(config.dataDir, 'logs'),
  heartbeat: path.join(config.dataDir, 'heartbeat'),
  proof: path.join(config.dataDir, 'proof')
}

export function ensureDirs () {
  for (const p of [paths.data, paths.keys, paths.incoming, paths.content, paths.app, paths.logs, paths.heartbeat, paths.proof]) {
    fs.mkdirSync(p, { recursive: true })
  }
}
