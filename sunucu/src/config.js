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

/**
 * SAYISAL ORTAM DEGISKENLERI ACILISTA DOGRULANIR.
 *
 * Eski hali her yerde duz `Number(process.env.X || varsayilan)` idi ve NaN hicbir
 * yerde yakalanmiyordu. Somut ariza: systemd unit dosyasina okunabilirlik adina
 * `CHUNK_SIZE=4MB` yazilir. Sunucu sorunsuz acilir, panel calisir, cihaz eklenir -
 * ilk yuklemede ffmpeg 10 dakika CPU yakar, cikti content/ altina tasinir, girdi
 * dosyasi SILINIR ve hemen ardindan `Buffer.allocUnsafe(NaN)` ERR_OUT_OF_RANGE
 * atar. Operatorun gordugu mesajin CHUNK_SIZE ile gorunur hicbir ilgisi yoktur ve
 * her denemede content/ altinda bir yetim dosya daha birikir.
 * Ayni kalip PORT icin `app.listen(NaN)` -> RASTGELE port, VIDEO_WIDTH icin
 * `scale=NaN:NaN` -> her transcode basarisiz demekti.
 *
 * Artik bozuk deger, ne yazilmasi gerektigini soyleyen bir mesajla ACILISTA durdurur.
 */
function sayi (ad, ham, varsayilan, { min, max, tamsayi = true, ipucu = '' } = {}) {
  if (ham === undefined || ham === null || String(ham).trim() === '') return varsayilan
  const d = Number(ham)
  const hata = (sebep) => {
    throw new Error(
      `${ad}="${ham}" gecerli bir deger degil (${sebep}).` +
      (ipucu ? ` ${ipucu}` : '') +
      ` Varsayilan: ${varsayilan}`
    )
  }
  if (!Number.isFinite(d)) hata('sayi degil')
  if (tamsayi && !Number.isInteger(d)) hata('tam sayi olmali')
  if (min !== undefined && d < min) hata(`en az ${min} olmali`)
  if (max !== undefined && d > max) hata(`en fazla ${max} olmali`)
  return d
}

export { sayi }

export const config = {
  port: sayi('PORT', process.env.PORT, 8080, { min: 1, max: 65535 }),
  dataDir: DATA_DIR,

  // Yonetim paneli / API anahtari
  adminToken: process.env.ADMIN_TOKEN || 'degistir-beni',

  // Transcode hedefi. Otobus ekrani 18-22 inc; 2.5 Mbps fazlasiyla yeterli.
  video: {
    videoBitrate: process.env.VIDEO_BITRATE || '2500k',
    maxrate: process.env.VIDEO_MAXRATE || '3500k',
    bufsize: process.env.VIDEO_BUFSIZE || '5000k',
    width: sayi('VIDEO_WIDTH', process.env.VIDEO_WIDTH, 1920, { min: 160, max: 3840 }),
    height: sayi('VIDEO_HEIGHT', process.env.VIDEO_HEIGHT, 1080, { min: 120, max: 2160 }),
    fps: sayi('VIDEO_FPS', process.env.VIDEO_FPS, 25, { min: 1, max: 60 }),
    audioBitrate: process.env.AUDIO_BITRATE || '128k',

    /*
     * SES: VARSAYILAN KAPALI - ve bu bir bant genisligi karari.
     *
     * Otobus ici ses politikasi geregi oynatici sessiz cikiyor. Ses akisini yine de
     * kodlamak, ASLA DUYULMAYACAK 128 kbit/s'yi her dosyaya eklemek demekti: 30
     * saniyelik bir reklamda ~480 KB, tipik bir dosyanin ~%5'i.
     *
     * Bu sistemde %5 kucuk bir sayi degil: tum mimari 3 dakikalik pencereye SIGMAK
     * icin kurulu (transcode, parcali indirme, nokta onbellegi). Duyulmayan ses icin
     * o pencerenin yirmide birini harcamak, cozmeye calistigimiz problemi bilerek
     * buyutmek olur.
     *
     * AUDIO=1 yaparsaniz: ses kodlanir VE policy.volume manifestle cihaza gider, yani
     * oynatici da sesi acar. Tek karar, iki yerde tutarli. DIKKAT: daha once yuklenmis
     * dosyalarda ses akisi YOKTUR; onlarin yeniden yuklenmesi gerekir.
     */
    audio: (process.env.AUDIO || '0') === '1',
    /** Oynatici ses seviyesi (0..1). Ses kapaliyken anlamsiz oldugu icin 0'a kilitlenir. */
    volume: Math.min(1, Math.max(0, sayi('VOLUME', process.env.VOLUME, 1, { min: 0, max: 1, tamsayi: false }))),
    ffmpeg: process.env.FFMPEG_BIN || 'ffmpeg',
    ffprobe: process.env.FFPROBE_BIN || 'ffprobe',
    // Test ortaminda ffmpeg yoksa yuklenen dosya oldugu gibi kabul edilir.
    skipTranscode: process.env.SKIP_TRANSCODE === '1'
  },

  // Parca boyutu. 4 MB: kopma aninda kaybedilen is kucuk, manifest sismiyor.
  chunkSize: sayi('CHUNK_SIZE', process.env.CHUNK_SIZE, 4 * 1024 * 1024,
    { min: 64 * 1024, max: 64 * 1024 * 1024, ipucu: 'Bayt cinsinden yazin: 4 MB = 4194304.' }),

  // Manifest imzalama anahtarlari (varsayilan olarak dataDir altinda)
  keys: {
    privatePath: process.env.PRIVATE_KEY_PATH || path.join(DATA_DIR, 'keys', 'ed25519-private.pem'),
    publicPath: process.env.PUBLIC_KEY_PATH || path.join(DATA_DIR, 'keys', 'ed25519-public.pem')
  },

  // Uygulama guncellemesi icin kademeli yayim: cihazlar 1..N grubuna dagitilir.
  rolloutGroups: sayi('ROLLOUT_GROUPS', process.env.ROLLOUT_GROUPS, 4, { min: 1, max: 100 }),

  /*
   * ISLETMENIN SAAT DILIMI.
   *
   * Daypart ("07:00-10:00") bu dilime gore yorumlanir ve deger MANIFESTLE cihaza
   * gider. Cihazin kendi saat dilimine BIRAKILAMAZ: o deger provizyonda kimsenin
   * dokunmadigi bir ROM varsayilanidir ve ucuz stick'lerde sik sik UTC cikar - yani
   * sabah kusagi reklami ogleden sonra doner. Sunucu tek kaynak olmali.
   */
  timezone: process.env.TIMEZONE || 'Europe/Istanbul',

  /*
   * Onumuzde kac GUVENILIR vekil var (nokta onbellek kutusu, ters vekil)?
   *
   * Deneme sinirinin dayandigi req.ip bu degerden uretiliyor. 'true' vermek
   * X-Forwarded-For'u istemciye birakir ve siniri tamamen atlatilabilir kilar;
   * bu yuzden burada bir SAYI istiyoruz. Dogrudan erisimde 0.
   */
  trustProxyHops: sayi('TRUST_PROXY_HOPS', process.env.TRUST_PROXY_HOPS, 0, { min: 0, max: 10 }),

  /*
   * /content icin cihaz tokeni istenecek mi?
   *
   * Varsayilan ACIK. Kapatma kacisi, Authorization basligini gecirmeyen/onbellege
   * karistiran bir ara sunucunun TUM filonun indirmesini durdurmasina karsi. /app
   * (APK) icin boyle bir kacis YOK: o dosya PROVISION_SECRET tasiyor.
   */
  contentAuth: (process.env.CONTENT_AUTH || '1') !== '0',

  /*
   * Bir cihaz kac saat senkron olmazsa panelde "bayat" sayilir.
   *
   * Bu deger onceden manifestte cihaza GONDERILIYOR ama cihazda hic kullanilmiyordu;
   * panel ise ayri bir yerde 72'yi SABIT tutuyordu. Yani "ayar" gorunen sey hicbir
   * seyi degistirmiyordu. Karar sunucuda verildigi icin ayar da burada.
   */
  staleHours: sayi('STALE_HOURS', process.env.STALE_HOURS, 72, { min: 1, max: 8760 })
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
