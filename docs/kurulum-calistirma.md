# Kurulum ve Çalıştırma

> Sıfırdan çalışan bir sisteme: merkez sunucu → nokta önbellek kutusu → otobüs cihazı.
> Sırayı bozmayın; her adım bir öncekini doğrular.

---

## 0. Neye ihtiyacınız var

| | |
|---|---|
| Merkez | Linux mini PC veya VPS. Node.js 20+, ffmpeg |
| Nokta | Mini PC / Raspberry Pi 4 + SSD. nginx, rsync |
| Otobüs | Android stick (Android 7+, **device owner kurulabilen**), ayrı 5 V / 2 A besleme |
| Geliştirme | Android Studio (veya JDK 17 + Android SDK), `adb` |

---

## 1. Merkez sunucu

```bash
cd sunucu
npm install
npm run anahtar-uret          # Ed25519 imza anahtarı üretir
```

Çıktıdaki satırı **saklayın**, Android tarafına gerekecek:

```
MANIFEST_PUBLIC_KEY=dDDQzO3DpPzQnzlaUdw1cgJI8w3MOake66FGwloRkPM=
```

> **Özel anahtar (`data/keys/ed25519-private.pem`) sadece merkezde kalır.**
> Git'e girmez (`.gitignore`'da), önbellek kutusuna gitmez, cihaza gitmez.
> Yedekleyin: kaybederseniz tüm cihazların yeniden provizyonu gerekir.

Çalıştırın:

```bash
ADMIN_TOKEN="uzun-rastgele-bir-dize" PORT=8080 npm start
```

Testleri çalıştırın (24 test — Range ile devam eden indirme senaryosu ve güvenlik testleri dahil):

```bash
npm test
```

Panel: `http://sunucu:8080` — admin token'ı girip **Bağlan**.

### Sistem servisi olarak

```ini
# /etc/systemd/system/reklam-sunucu.service
[Unit]
Description=Otobus reklam sunucusu
After=network.target

[Service]
Type=simple
User=reklam
WorkingDirectory=/opt/reklam/sunucu
Environment=ADMIN_TOKEN=uzun-rastgele-bir-dize
Environment=PORT=8080
ExecStart=/usr/bin/node src/index.js
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

---

## 2. İlk içeriği yükleyin (**evergreen önce**)

İlk yüklediğiniz şey bir **evergreen** içerik olmalı — kendi tanıtımınız, bir kamu
spotu, herhangi bir dolgu. Sebebi: evergreen, ekranın son güvencesidir. Kampanya
yoksa, süresi dolduysa veya saat şüpheliyse ekranı bu doldurur.

```bash
TOKEN="uzun-rastgele-bir-dize"

# 1) Video yükle (sunucu 2,5 Mbps H.264'e çevirir)
curl -H "Authorization: Bearer $TOKEN" --data-binary @tanitim.mp4 \
     "http://localhost:8080/api/admin/upload?name=tanitim.mp4"
# -> {"ok":true,"item":{"sha256":"a1b2...","size":9861234,"chunkCount":3}}

# 2) Evergreen kampanya yap
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"itemSha":"a1b2...","title":"Kurumsal tanıtım","evergreen":true}' \
     http://localhost:8080/api/admin/campaign
```

Normal bir kampanyada **`validUntil` zorunludur** — sunucu tarihsiz kampanyayı
reddeder. 4G olmadığı için uzaktan "kaldır" komutu yok; reklam kendi kendine
yayından düşmek zorunda.

```bash
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"itemSha":"c3d4...","title":"Kahve kampanyası","advertiser":"Kahve A.Ş.",
          "validFrom":"2026-10-01T06:00:00Z","validUntil":"2026-10-15T23:59:59Z",
          "weight":2,"dayparts":["07:00-10:00","17:00-20:00"],"groups":["hat-14"]}' \
     http://localhost:8080/api/admin/campaign
```

> `validFrom`'u kampanya başlangıcından **24–48 saat önceye** verin. Otobüsler
> dosyayı bolca zamanda indirir, hepsi aynı anda yayına geçer.

---

## 3. Nokta önbellek kutusu

```bash
sudo ./onbellek-kutusu/kurulum.sh
sudo nano /etc/nginx/sites-available/reklam   # upstream merkez adresini düzeltin
ssh-keygen && ssh-copy-id reklam@MERKEZ       # rsync için parolasız erişim
sudo /opt/reklam/ayna.sh                      # ilk aynalama
sudo systemctl reload nginx
```

**Kabul testi — bu geçmeden devam etmeyin:**

```bash
curl -sI -r 0-1023 http://localhost/content/a1b2....mp4 | head -3
```

`HTTP/1.1 206 Partial Content` görmeniz **şart**. Göremiyorsanız parçalı indirme
çalışmaz, yani sistemin tamamı çalışmaz.

Link koptuğunda bayat manifest servis edildiğini de doğrulayın:

```bash
curl -s -o /dev/null -D- -H "Authorization: Bearer <cihaz-token>" \
     http://localhost/api/v1/manifest | grep X-Manifest-Cache
```

---

## 4. Android uygulaması

### 4.1 Anahtarları yerleştirin

`android/gradle.properties`:

```properties
MANIFEST_PUBLIC_KEY=dDDQzO3DpPzQnzlaUdw1cgJI8w3MOake66FGwloRkPM=
PROVISION_SECRET=uzun-rastgele-bir-dize
```

> `MANIFEST_PUBLIC_KEY` yanlışsa cihaz **hiçbir** manifesti kabul etmez.
> Bu kasıtlı bir davranış: önbellek kutusu ele geçse bile cihaza sahte reklam
> yüklenemez.

### 4.2 İmza anahtarı (release)

**Tüm sürümler aynı anahtarla imzalanmalı**, yoksa sessiz güncelleme reddedilir.
Anahtarı bir kez üretip güvenli yerde saklayın:

```bash
keytool -genkey -v -keystore reklam.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias reklam
```

`android/app/build.gradle.kts` içindeki `release` bloğuna `signingConfig` ekleyin.

### 4.3 Test edin ve derleyin

```bash
cd android

# 36 birim testi: saat aralıkları, ağırlıklı sıralama, saat mantığı,
# indirme önceliği ve sunucuyla kademeli yayım hash uyumu
./gradlew testDebugUnitTest

./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

> İlk çalıştırmada Gradle kendini indirir (`gradle/wrapper/gradle-wrapper.properties`).
> Ayrı bir Gradle kurulumuna gerek yok.

### 4.4 CI

`.github/workflows/ci.yml` her push'ta sunucu testlerini, Android birim testlerini,
lint'i, debug APK derlemesini ve shellcheck'i çalıştırır; APK'yı artifact olarak
yükler. Yerelde Android SDK kurmak istemiyorsanız APK'yı CI çıktısından indirebilirsiniz.

---

## 5. Cihaz provizyonu

### 5.1 Önce uygunluk kontrolü (50 cihaz almadan önce **bir** tanede)

```bash
./tools/cihaz-kontrol.sh
```

En kritik iki satır:
- **hesap ekli** → device owner atanamaz, fabrika ayarlarına dönmek gerekir
- **zaten bir device owner var** → aynı şekilde

### 5.2 Provizyon

Cihaz **kutudan yeni çıkmış / fabrika ayarında** ve **hiçbir hesap eklenmemiş** olmalı.

```bash
./tools/provizyon.sh OTOBUS-014 \
  --sunucu http://10.20.0.10 \
  --ssid "REKLAM-AP" --psk "ap-parolasi" \
  --api http://127.0.0.1:8080 --admin-token "$TOKEN" \
  --apk android/app/build/outputs/apk/release/app-release.apk \
  --secret "gradle.properties içindeki PROVISION_SECRET" \
  --grup hat-14
```

Çıktıda **`sahip=evet`** ve **`wifi=yazildi`** görmelisiniz. Görmüyorsanız durun —
device owner olmadan sessiz güncelleme ve otomatik WiFi çalışmaz.

### 5.3 Kütüphaneyi ön-yükleyin

Yeni cihazın sahaya boş gitmesine gerek yok. Provizyondan sonra cihazı **masadaki
ağa** bağlayın (sunucuya erişen herhangi bir WiFi) ve kütüphane insin. Ayrı bir
ön-yükleme aracı yok: normal senkron yolunu kullanmak, hem daha az kod hem de
sahada çalıştığı zaten test edilmiş olan yol.

---

## 6. Uygulama güncellemesi (kademeli)

```bash
# Önce SADECE 1. gruba (cihazların ~dörtte biri)
curl -H "Authorization: Bearer $TOKEN" --data-binary @app-release.apk \
  "http://sunucu:8080/api/admin/app?versionCode=42&versionName=1.1.0&rolloutGroup=1"

# 48 saat sorunsuzsa hepsine aç
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  http://sunucu:8080/api/admin/app/rollout
```

Acil bir düzeltmeyse `&critical=1` ekleyin — o zaman güncelleme içerikten **önce**
indirilir.

> **50 cihaza aynı gün güncelleme göndermeyin.** Uygulamada bir açılış-sağlık
> sayacı ve geri dönüş mekanizması var, ama uygulama süreç başlamadan çöküyorsa
> o kod hiç çalışmaz. Gerçek koruma kademeli yayımdır.

---

## 7. Rapor

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://sunucu:8080/api/admin/report.csv?from=2026-10-01&to=2026-10-15" \
  -o rapor.csv
```

Sütunlar: kampanya, reklamveren, otobüs, gün, oynatma sayısı, toplam saniye,
**saati şüpheli kayıt sayısı**. Son sütun sıfır değilse o cihazın saatinde sorun
var demektir; denetimde tartışma çıkmaması için raporda açıkça gösteriliyor.

Sadece **tamamlanmış** oynatmalar sayılır — yarım kalan oynatma faturalanamaz.

---

## 8. Sorun giderme

| Belirti | Bakılacak |
|---|---|
| Cihaz panelde hiç görünmedi | Provizyon çıktısında `sahip=evet` var mıydı? WiFi profili yazıldı mı? |
| "IMZA GECERSIZ" hatası | `gradle.properties`'teki `MANIFEST_PUBLIC_KEY`, sunucunun açık anahtarıyla aynı mı? |
| İndirme hiç ilerlemiyor | Önbellek kutusu 206 dönüyor mu? (`curl -r 0-1023 -I`) |
| Cihaz rastgele resetleniyor | Besleme TV'nin USB'sinde mi? Yük altında voltajı ölçün |
| Hep aynı reklam dönüyor | Teşhis ekranını açın (kumandada INFO/MENU): liste sürümü ve son senkron |
| Süresi geçmiş reklam oynuyor | Teşhis ekranında "saat: ŞÜPHELİ" mi yazıyor? |
| Kare atlıyor | Dosya transcode standardından mı geçti? HEVC olmamalı |
| Güncelleme gitmiyor | APK'lar **aynı anahtarla** mı imzalı? `rolloutGroup` cihazın grubunu kapsıyor mu? |

Cihaz logları:

```bash
adb logcat -s SyncService:* Downloader:* PlayerActivity:* Telemetry:* \
              PlaylistBuilder:* DeviceAdmin:* Updater:*
```
