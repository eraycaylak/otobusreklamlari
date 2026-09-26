import express from 'express'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { config, paths, ensureDirs } from './config.js'
import { load } from './store.js'
import { deviceRouter } from './routes/device.js'
import { adminRouter } from './routes/admin.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

ensureDirs()
load()

if (!fs.existsSync(config.keys.privatePath)) {
  console.error('\nHATA: imzalama anahtari yok.\n  npm run anahtar-uret\nkomutunu calistirin.\n')
  process.exit(1)
}
if (config.adminToken === 'degistir-beni') {
  console.warn('UYARI: ADMIN_TOKEN varsayilan degerde. Uretimde mutlaka degistirin.')
}

export const app = express()
app.disable('x-powered-by')
app.set('trust proxy', true)

app.use((req, res, next) => {
  const t = Date.now()
  res.on('finish', () => {
    // Onbellek kutusu uzerinden gelen istekleri de gorebilmek icin sade erisim logu
    console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl} ${res.statusCode} ${Date.now() - t}ms`)
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
app.use('/content', express.static(paths.content, staticOpts))
app.use('/app', express.static(paths.app, staticOpts))

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
