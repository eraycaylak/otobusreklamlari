#!/usr/bin/env node
import fs from 'node:fs'
import { config } from '../src/config.js'
import { publicKeyBase64 } from '../src/crypto.js'

/**
 * Manifest imzalama ACIK anahtarini yeniden yazdirir.
 *
 * NEDEN AYRI BIR BETIK: anahtar-uret.js degeri YALNIZCA uretim aninda basiyor ve
 * mevcut anahtarin uzerine yazmayi (dogru olarak) reddediyor. Ama acik anahtara
 * sonradan tekrar ihtiyac duyulur - ve her seferinde:
 *   - yeni bir APK derlenirken (MANIFEST_PUBLIC_KEY gomulmeli)
 *   - ikinci bir gelistirici/operator devreye girdiginde
 *   - ilk ciktinin yazildigi not kaybolduginda
 * Bu betik olmadan tek gorunur yol `anahtar-uret --force` idi: o komut anahtari
 * DEGISTIRIR ve sahadaki TUM cihazlarin yeniden provizyonunu (yani her otobuse tek
 * tek gitmeyi) gerektirir. Yani eksik bir betik, en pahali hatayi tek dogru yol
 * gibi gosteriyordu.
 *
 * ACIK anahtar sir DEGILDIR: cihaza gomuluyor ve yalnizca dogrulama icin kullanilir.
 * OZEL anahtar bu betikte HIC okunmaz.
 */
if (!fs.existsSync(config.keys.publicPath)) {
  console.error(`Acik anahtar bulunamadi: ${config.keys.publicPath}`)
  console.error('Once uretin:  npm run anahtar-uret')
  console.error('(DATA_DIR dogru mu? Sistem kurulumunda /var/lib/reklam kullanilir:')
  console.error('   DATA_DIR=/var/lib/reklam npm run anahtar-goster )')
  process.exit(1)
}

const raw = publicKeyBase64()

console.log('Manifest imzalama ACIK anahtari (sir degil, APK ya gomulur):')
console.log('')
console.log(`  MANIFEST_PUBLIC_KEY=${raw}`)
console.log('')
console.log('Bu satiri ~/.gradle/gradle.properties icine yazin.')
console.log('(Depodaki android/gradle.properties TAKIP EDILIYOR - sir icermemeli;')
console.log(' bu deger sir degil ama aliskanligi ayni yerde tutmak daha guvenli.)')
console.log('')
console.log(`Ozel anahtar: ${config.keys.privatePath}  (okunmadi - yedegini ayri tutun)`)
