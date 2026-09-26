#!/usr/bin/env node
import { config } from '../src/config.js'

/**
 * Cihaz kaydi ve provizyon komutu uretici.
 *
 *   node scripts/cihaz-ekle.js OTOBUS-014 --grup hat-14 --etiket "34 ABC 123" \
 *        --sunucu http://10.20.0.10:8080
 */
const args = process.argv.slice(2)
const id = args[0]
if (!id || id.startsWith('--')) {
  console.error('Kullanim: node scripts/cihaz-ekle.js <CIHAZ-ID> [--grup g] [--etiket e] [--sunucu url] [--api url]')
  process.exit(1)
}
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] ? args[i + 1] : def
}

const api = opt('api', `http://127.0.0.1:${config.port}`)
const contentBase = opt('sunucu', api)

const res = await fetch(`${api}/api/admin/device`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', authorization: `Bearer ${config.adminToken}` },
  body: JSON.stringify({ id, group: opt('grup', 'default'), label: opt('etiket', id) })
})
if (!res.ok) {
  console.error('Kayit basarisiz:', res.status, await res.text())
  process.exit(1)
}
const { device } = await res.json()

console.log(`Cihaz kaydedildi: ${device.id}  (grup: ${device.group}, rolloutGroup: ${device.rolloutGroup})`)
console.log('')
console.log('Provizyon komutu (cihaz KUTUDAN YENI / fabrika ayarinda olmali):')
console.log('')
console.log('  adb install -r reklam-app.apk')
console.log('  adb shell dpm set-device-owner com.otobusreklam.player/.admin.AdminReceiver')
console.log('  adb shell am broadcast -a com.otobusreklam.player.PROVISION \\')
console.log('    -n com.otobusreklam.player/.provision.ProvisionReceiver \\')
console.log(`    --es secret "<PROVISION_SECRET>" \\`)
console.log(`    --es deviceId "${device.id}" \\`)
console.log(`    --es token "${device.token}" \\`)
console.log(`    --es baseUrl "${contentBase}" \\`)
console.log(`    --es apiUrl "${contentBase}" \\`)
console.log(`    --es ssid "<AP-SSID>" --es psk "<AP-PAROLA>"`)
console.log('')
console.log('NOT: token bir daha gosterilmez. Kaybolursa --regenerateToken ile yenileyin.')
