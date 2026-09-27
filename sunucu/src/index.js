import express from 'express'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { config, paths, ensureDirs } from './config.js'
import { load } from './store.js'
import { deviceRouter, auth as deviceAuth } from './routes/device.js'
import { adminRouter } from './routes/admin.js'
import { redactUrl } from './guard.js'
import { anahtarlariDogrula } from './crypto.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

ensureDirs()
load()

if (!fs.existsSync(config.keys.privatePath)) {
  console.error('\nHATA: imzalama anahtari yok.\n  npm run anahtar-uret\nkomutunu calistirin.\n')
  process.exit(1)
}

/*
 * ANAHTAR CIFTI SELF-TEST'I ACILISTA.
 *
 * Servis durdurulmadan anahtar rotasyonu yapildiginda (dokumante edilmis
 * `anahtar-uret --force` yolu) sunucu YENI acik anahtari dagitip ESKI ozel
 * anahtarla imzaliyordu. Hicbir hata gorunmuyor, 200 donuyor - ama filodaki her
 * cihaz her manifestte imza dogrulamasindan dusuyor ve hicbir icerik inmiyor.
 * Cift acilista dogrulanmazsa bu ariza yalnizca aylar sonra fark edilir.
 */
try {
  anahtarlariDogrula()
} catch (e) {
  console.error(`\nHATA: ${e.message}\n`)
  process.exit(1)
}
if (config.adminToken === 'degistir-beni') {
  console.warn('UYARI: ADMIN_TOKEN varsayilan degerde. Uretimde mutlaka degistirin.')
}

export const app = express()
app.disable('x-powered-by')

/*
 * TRUST PROXY: 'true' DEGIL.
 *
 * 'true' ile Express, X-Forwarded-For basliginin EN SOLDAKI degerini req.ip yapar ve
 * o baslik ISTEMCI TARAFINDAN yazilir. Yani deneme siniri tamamen atlatilabilir hale
 * gelir: saldirgan her istekte baska bir XFF degeri yazar, her seferinde temiz bir
 * sayac alir ve admin tokenini SINIRSIZ dener. Bu, tek dizelik bir admin tokeni icin
 * korumanin tamamen kalkmasi demektir.
 *
 * Dogru deger onumuzde kac guvenilir vekil oldugudur (nokta onbellek kutusu + varsa
 * ters vekil). Express bu durumda XFF'nin SAGDAN o kadar atlanmis degerini alir, yani
 * istemcinin yazdigi kisim yok sayilir. Vekil yoksa 0/'loopback' dogru cevaptir.
 */
app.set('trust proxy', config.trustProxyHops)

app.use((req, res, next) => {
  const t = Date.now()
  res.on('finish', () => {
    // Onbellek kutusu uzerinden gelen istekleri de gorebilmek icin sade erisim logu.
    // URL REDAKTE EDILIYOR: rapor indirme baglantisi admin tokenini sorgu dizesinde
    // tasiyor; ham haliyle loglamak tokeni log dosyalarina sizdirirdi.
    console.log(`${new Date().toISOString()} ${req.method} ${redactUrl(req.originalUrl)} ${res.statusCode} ${Date.now() - t}ms`)
  })
  next()
})

/**
 * Icerik ve APK servisi.
 *
 * acceptRanges ZORUNLU: cihaz pencereyi kacirdiginda kaldigi bayttan devam ediyor.
 * express.static (send modulu) Range isteklerine 206 Partial Content doner.
 *
 * Icerik adresli (sha256) isimler kullanildigi icin dosyalar degismez -> uzun onbellek.
 */
const staticOpts = { acceptRanges: true, maxAge: '365d', immutable: true, index: false, dotfiles: 'deny' }

/*
 * APK SERVISI KIMLIK DOGRULAMASI ISTER - PAZARLIK KONUSU DEGIL.
 *
 * /app altindaki APK, PROVISION_SECRET'i GOMULU tasiyor. Kimlik dogrulamasiz
 * servis edildiginde sunucuya erisebilen herkes APK'yi indirip o sirri cikarabilir
 * ve kendi cihazini filoya provizyonlayabilir. Uzerine cihaz tokeni alan bir
 * saldirgan da manifest cekip icerigi gorebilir.
 *
 * Icerik (reklam videolari) icin de varsayilan AUTH ACIK, ama kapatilabilir: bu
 * dosyalar zaten halka acik otobus ekranlarinda yayinlaniyor, dolayisiyla gizlilik
 * degeri dusuk - ve yanlis yapilandirilmis bir onbellek kutusu TUM filonun icerik
 * indirmesini durdurabilir. Operatore bu kacisi biraktik; APK icin birakmadik.
 *
 * ONBELLEK KUTUSU NOTU: nginx Authorization basligini yukari gecirmeli ve onbellek
 * anahtarina KATMAMALI; boylece bir cihazin cektigi dosya digerlerine de servis
 * edilir. Ayrintisi onbellek-kutusu/nginx.conf icinde.
 */
app.use('/app', deviceAuth, express.static(paths.app, staticOpts))
app.use('/content', config.contentAuth ? deviceAuth : (req, res, next) => next(),
  express.static(paths.content, staticOpts))

app.use('/api/v1', deviceRouter)
app.use('/api/admin', adminRouter)

// Yonetim paneli
app.use('/', express.static(path.join(__dirname, 'panel'), { index: 'index.html' }))

app.get('/saglik', (req, res) => res.json({ ok: true, time: new Date().toISOString() }))

app.use((err, req, res, next) => {
  console.error('ISTEK HATASI', err)
  if (res.headersSent) return next(err)
  res.status(500).json({ error: 'sunucu hatasi' })
})

if (process.env.NODE_ENV !== 'test') {
  app.listen(config.port, () => {
    console.log(`Otobus reklam sunucusu calisiyor: http://0.0.0.0:${config.port}`)
    console.log(`Veri dizini: ${paths.data}`)
  })
}
