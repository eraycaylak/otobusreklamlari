# Senden Gerekenler — tek sayfalık liste

Kod tarafında yapılacak bir şey kalmadı. Aşağıdakiler **yalnızca senin
sağlayabileceğin** şeyler: donanım, ağ değerleri, sırlar ve bir video.

Sırayla git. Her adımın "nasıl"ı [`kurulum-calistirma.md`](kurulum-calistirma.md)
ve [`saha-kurulum.md`](saha-kurulum.md) içinde.

---

## 1. Donanım (satın alınacak)

Tam liste: [`saha-kurulum.md` §1](saha-kurulum.md). Kritik üç nokta:

- **Otobüs başına Android stick** — çift bant WiFi + **device owner kurulabilir**
  olmalı. **Fire TV / Google TV ALMA**: device owner atanamıyor ve bu olmadan proje
  yürümez (sessiz güncelleme, kiosk, otomatik WiFi hepsi buna bağlı).
- **Otobüs başına ayrı 5 V / 2 A besleme** (araç DC-DC, 9–36 V giriş).
  **TV'nin USB'sinden besleme YOK** — TV kapanınca stick de kapanır.
- **Nokta başına önbellek kutusu** (mini PC / Pi 4 + SSD) + **2–3 AP**.
  Tek AP 20 otobüsü aynı anda doyurmaz.

## 2. Ağ değerleri (koda yazılacak)

Bunlar yer tutucu ve **senin ağına göre değişmesi zorunlu**:

| Nerede | Şu an | Ne yazılacak |
|---|---|---|
| `onbellek-kutusu/nginx.conf:12` | `10.0.0.5:8080` | Merkez sunucunun IP'si |
| `onbellek-kutusu/nginx.conf` (3 yerde) | `allow 10.20.0.0/24` | Noktadaki AP alt ağı |
| `onbellek-kutusu/ayna.sh:13` | `reklam@10.0.0.5` | Merkeze SSH (`MERKEZ=`) |

> Alt ağı yanlış yazarsan otobüsler `403` alır ve hiçbir şey indirmez.
> `allow` satırları bilinçli: o blok olmadan noktadaki paylaşılan kablosuz ağdan
> yönetim API'sine ulaşılabiliyordu.

## 3. Sırlar (sen üreteceksin, kimseye vermeyeceksin)

```bash
# a) Admin token — panel ve yönetim API'si
openssl rand -base64 32          # -> sunucuda ADMIN_TOKEN

# b) Provizyon sırrı — APK'ya gömülür, cihaz provizyonunu korur
openssl rand -base64 32          # -> android/gradle.properties: PROVISION_SECRET

# c) İmzalama anahtarı — sunucuda üretilir, açık kısmı APK'ya gömülür
cd sunucu && npm run anahtar-uret && npm run anahtar-goster
#   çıkan base64 -> android/gradle.properties: MANIFEST_PUBLIC_KEY

# d) APK imzalama deposu (release derlemesi için)
keytool -genkey -v -keystore reklam.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias reklam
#   -> RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
```

**`~/.gradle/gradle.properties` içine yaz** (depodaki `android/gradle.properties`
değil — o dosya git'e giriyor). Release derlemesi bu değerler eksik veya kısaysa
**durur**: boş bir açık anahtarla derlenmiş APK, sahadaki tüm filonun her manifesti
reddetmesi demek olurdu.

**Özel imzalama anahtarını (`data/keys/ed25519-private.pem`) kaybetme.** Kaybedersen
yeni anahtarla devam etmek, her otobüse tek tek gidip yeniden provizyon demek.
`npm run yedekle` ile yedekle, yedeği **sunucunun kendisinde bırakma**.

## 4. İçerik

Montajdan **önce** panele en az **bir evergreen video** yükle. Evergreen olmadan
kampanyalar bittiği anda ekran kararır — sistemin en kötü sonucu bu.

## 5. Yalnızca senin yapabileceğin tek teknik adım

Her stick **kutudan yeni çıkmış ya da fabrika ayarlarına dönmüş** ve **hiçbir hesap
ekli olmamış** olmalı. Tek bir Google hesabı ekliyse device owner atanamaz ve geri
dönüşü yine fabrika ayarlarıdır. Sonra:

```bash
./tools/provizyon.sh OTOBUS-014 --sunucu http://10.20.0.10 \
  --ssid "REKLAM-AP" --psk "ap-parolasi" --admin-token "$TOKEN" \
  --apk android/app/build/outputs/apk/release/app-release.apk \
  --secret "$PROVISION_SECRET" --grup hat-14
```

Betik şunları görmezse **durur** (görmezse sahaya gönderme):
`sahip=evet`, `wifi=BAGLANDI`, `tetikleyici=kuruldu`.

## 6. Benim önerim: 50 otobüsle başlama

**2–3 otobüs + 1 nokta** ile başla, bir hafta çalıştır ve panelden
**Pencere raporu (CSV)** çek. O rapor tüm mimarinin dayandığı soruyu ölçüyor:
*"3 dakika gerçekten yetiyor mu?"* Sayı elinde olmadan büyütmek tahmine dayanmak olur.

İlk hafta panelde bakacağın üç şey:

| Sinyal | Ne demek |
|---|---|
| **EKRAN BOŞ** (kırmızı) | Reklamveren para ödedi, ekran siyah. Her şeyin önünde gelir. Yanındaki satır sebebi yazar. |
| **SAHİP DEĞİL** (kırmızı) | Device owner atanamamış: kiosk ve sessiz güncelleme çalışmıyor. Fabrika ayarları + yeniden provizyon. |
| **pencere** sütunu (MB) | O otobüsün son durakta indirdiği bayt. Sürekli düşükse AP sayısı veya konumu sorunlu. |
