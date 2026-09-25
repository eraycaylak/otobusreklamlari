# Otobüs İçi Reklam Yayın Sistemi

USB bellek taşımayı bitiren, kendi uygulamamızla çalışan merkezi yayın sistemi.

**Ayrıntılar:**
[`docs/plan.md`](docs/plan.md) · [`docs/uygulama-spec.md`](docs/uygulama-spec.md) · [`docs/saha-kurulum.md`](docs/saha-kurulum.md)

---

## Sabit kararlar (mimari bunlara göre kurulu)

| | |
|---|---|
| İnternet | **Tek hat.** Point-to-Multipoint ile 3 toplanma noktasına dağıtılacak |
| Otobüs içi cihaz | TV'nin HDMI'sına takılı **Android stick** |
| Yazılım | **Kendi Android uygulamamız** — oynatma da, veri çekme de, log da uygulamanın içinde |
| Bağlantı | **Sadece WiFi**, sadece toplanma noktalarında, otobüs başı 4G yok |
| Pencere | Otobüs noktada **~3 dakika** duruyor |

---

## Mimari

```
[Ofis / tek internet hattı]
   ├── Yayın sunucusu (mini PC): panel + ffmpeg transcode + imzalı manifest + nginx
   │
   └── PtMP sektör anten (çatı)
         ├──► Nokta 1: station radyo → switch → 2-3 AP  +  ÖNBELLEK KUTUSU
         ├──► Nokta 2: station radyo → switch → 2-3 AP  +  ÖNBELLEK KUTUSU
         └──► Nokta 3: station radyo → switch → 2-3 AP  +  ÖNBELLEK KUTUSU
                                                  │
                                       (WiFi, 3 dakikalık pencere)
                                                  │
                                    [Otobüs: Android stick + kendi app]
                                      - WiFi görünce anında senkron
                                      - parçalı indirir, hash doğrular
                                      - atomik geçiş, kesintisiz oynatır
                                      - oynatma loglarını yükler
                                                  │ HDMI
                                              [ TV ]
```

**Önbellek kutusu mimarinin kalbi.** Dosya tek internet hattından **saatler önce, bir kez**
iner; 3 dakikalık pencerede 20 otobüse **LAN hızında** dağıtılır. Böylece internet hattının
hızı neredeyse önemsizleşir.

---

## Bu 5 şeyi baştan yanlış yaparsan proje yürümez

| # | Konu | Doğrusu |
|---|---|---|
| 1 | **Stick'i TV'nin USB'sinden besleme** | TV USB'si genelde 500 mA verir, stick 900–1200 mA çeker → rastgele resetlenir. Ayrıca **TV kapalıyken stick de ölür, senkron hiç olmaz.** Otobüs hattından 12/24 V → 5 V 2 A ayrı besleme çek. |
| 2 | **Device Owner modunu atlama** | Uygulamayı sessizce kendi kendine güncelleyebilmek, depo WiFi'sine sessizce bağlanabilmek (Android 10+ normal uygulamada `addNetwork` **-1 döner**) ve kiosk kilidi için **zorunlu**. Kurulumu **kutudan çıkmış, hesap eklenmemiş** cihazda `adb shell dpm set-device-owner` ile yapılır. Sonradan yapılamaz — fabrika ayarı gerekir. |
| 3 | **Videoyu olduğu gibi dağıtma** | Sunucuda 2,5 Mbps'e sıkıştır: 30 sn reklam **50 MB değil ~10 MB**. 3 dakikalık pencerenin tek gerçek çözümü bu. |
| 4 | **WorkManager periodic ile senkron denemesi** | Minimum periyot **15 dakika** — 3 dakikalık pencereyi kaçırır. `ConnectivityManager.NetworkCallback` ile WiFi görüldüğü an tetikle. |
| 5 | **Uzaktan anlık komut beklentisi** | 4G yok → **anlık "reklamı kaldır" komutu yok.** Her reklamın bitiş tarihi içeriğe gömülü olmalı; cihaz internetsizken bile kendi kendine yayından düşürmeli. Bu yüzden saat doğruluğu kritik. |

---

## Fazlar

| Faz | Süre | Çıktı |
|---|---|---|
| **0 — Ölçüm** | 1 hafta | Stick modeli/WiFi bandı, TV girişleri, güç noktası, PtMP görüş hattı, otobüslerin gerçek duruş süresi |
| **1 — Pilot** | 3–4 hafta | 1 nokta + 2 otobüs. Uygulamanın v1'i: WiFi'de senkron, parçalı indirme, kesintisiz oynatma, log |
| **2 — Yaygınlaştırma** | 4–6 hafta | 3 nokta, önbellek kutuları, izleme paneli, sessiz uygulama güncellemesi, 10–20 otobüs |
| **3 — Ticarileştirme** | sonrası | Oynatma kanıtı raporu, reklamveren paneli, faturalama |

Pilotta bir noktayı ve iki otobüsü tam çalıştır. 3 noktaya aynı anda girişme.
