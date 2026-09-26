#!/usr/bin/env node
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { config, ensureDirs } from '../src/config.js'

/**
 * Manifest imzalama anahtar cifti.
 *
 * OZEL ANAHTAR SADECE MERKEZDE KALIR. Onbellek kutularina, cihazlara, git'e gitmez.
 * ACIK ANAHTAR Android uygulamasina gomulur; cihaz manifest imzasini bununla dogrular.
 * Boylece onbellek kutusu ele gecse bile cihaza sahte reklam yollanamaz.
 */
ensureDirs()

if (fs.existsSync(config.keys.privatePath) && !process.argv.includes('--force')) {
  console.error(`Anahtar zaten var: ${config.keys.privatePath}`)
  console.error('Uzerine yazmak icin --force (DIKKAT: tum cihazlarin yeniden provizyonu gerekir).')
  process.exit(1)
}

const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519')

fs.mkdirSync(path.dirname(config.keys.privatePath), { recursive: true })
fs.writeFileSync(config.keys.privatePath, privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 })
fs.writeFileSync(config.keys.publicPath, publicKey.export({ format: 'pem', type: 'spki' }))

const der = publicKey.export({ format: 'der', type: 'spki' })
const raw = der.subarray(der.length - 32).toString('base64')

console.log('Anahtar cifti uretildi.')
console.log(`  ozel : ${config.keys.privatePath}   (GIZLI - yedekleyin, paylasmayin)`)
console.log(`  acik : ${config.keys.publicPath}`)
console.log('')
console.log('Bu degeri Android tarafinda gradle.properties icine yazin:')
console.log(`  MANIFEST_PUBLIC_KEY=${raw}`)
