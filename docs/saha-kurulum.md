# Saha Kurulum — Malzeme Listesi ve Adım Adım

> Tek internet hattı → PtMP → 3 toplanma noktası → otobüsler.
> Mimarinin gerekçeleri [`plan.md`](plan.md)'de; burası uygulama tarafı.

---

## 1. Malzeme listesi

### Merkez (internet hattının olduğu yer)

| Adet | Malzeme | Not |
|---|---|---|
| 1 | Yayın sunucusu (mini PC) | Panel + PostgreSQL + ffmpeg + nginx. VPS de olur |
| 1 | **PtMP sektör AP** (airMAX ac, 120°) | Üç noktayı tek radyodan besler |
| 1 | Direk / montaj + topraklama + yıldırım koruması | Çatıda |
| 1 | PoE enjektör + dış mekân UTP | |
| 1 | Yönetilebilir switch (VLAN) | Noktaları birbirinden izole etmek için |

### Her toplanma noktası (× 3)

| Adet | Malzeme | Not |
|---|---|---|
| 1 | **Station radyo** (airMAX ac CPE) | Merkez sektöre bakar |
| 1 | **Önbellek kutusu**: mini PC veya Pi 4 + SSD | nginx + rsync aynası + log kuyruğu |
| 2–3 | **Erişim noktası (AP)** | **Tek AP yetmez.** Çift bant tercih |
| 1 | PoE switch | AP'leri besler |
| — | Elektrik hattı + kesintisiz güç (küçük UPS) | Kutu ve switch için |
| — | Dış mekân muhafaza / pano | |

### Her otobüs

| Adet | Malzeme | Not |
|---|---|---|
| 1 | **Android stick veya küçük box** | Çift bant WiFi + Device Owner kurulabilir olmalı. **Fire TV alma** |
| 1 | **Araç DC-DC: 9–36 V giriş → 5 V / 2 A çıkış** | **TV USB'sinden besleme YOK** |
| 1 | Sigorta + sigorta yuvası | Hat başına |
| 1 | HDMI kablo (kısa, kaliteli) | |
| 1 | Kilitli/havalanan montaj kutusu | Dışarıda erişilebilir port olmasın |
| — | Kelepçe, gerilim azaltıcı, kablo kanalı | |
| +%15 | **Yedek stick** | Ucuz cihazlar ölür |

---

## 2. Kurulum sırası

### 2.1 Merkez

1. Sunucuyu kur: nginx (Range aktif — varsayılan), PostgreSQL, ffmpeg, panel/API.
2. Ed25519 anahtar çiftini üret. **Özel anahtar yalnızca merkezde**, açık anahtar uygulamaya gömülür.
3. `content/`, `manifest/`, `app/` klasör yapısını kur.
4. Sektör anteni monte et, yönünü üç noktayı kapsayacak şekilde ayarla (120° içinde kalmalı).
5. Topraklama ve yıldırım korumasını atlama.

### 2.2 Her nokta

1. Station radyoyu monte et, merkez sektöre hizala. **Sinyal hedefi: −65 dBm veya daha iyi.**
2. Link hızını ölç (iperf3). Aynalama yapacağın için 20–30 Mbps bile yeterli.
3. Önbellek kutusunu kur, rsync'i cron'a bağla, nginx'i ayağa kaldır.
4. AP'leri yerleştir:
   - **2,4 GHz kanalları 1 / 6 / 11** — örtüşmeyen üçlü
   - 5 GHz varsa aç ve tercih ettir
   - AP'ler **yüksekte**, otobüs pencerelerini görecek şekilde (araç gövdesi sinyali keser)
   - Tüm noktalarda **aynı SSID**, WPA2-PSK (enterprise değil)
   - Ayrı VLAN + MAC izin listesi
   - **Captive portal / ek doğrulama YOK** — 3 dakikada yeri yok
   - Kısa DHCP lease (ör. 10 dk), hızlı dağıtım
5. Ağın PtMP üzerinden **gerçek internete çıktığını** doğrula — aksi halde Android ağı
   "internet yok" diye geri plana atabilir.

### 2.3 Her otobüs

1. Sigortayı ve besleme noktasını **işletmeci teknikeriyle** belirle (yazılı onay şart).
2. DC-DC dönüştürücüyü bağla, çıkışı 5 V / 2 A ölç. Marş anında düşüşü kontrol et.
3. Stick'i kutuya al, HDMI'yı tak, güç kablosunu **DC-DC'ye** bağla (TV USB'ye değil).
4. TV'de HDMI-CEC'i aç, girişi kilitle, sesi ayarla, TV'yi otel/kiosk moduna al.
5. Stick provizyonunu yap — [`uygulama-spec.md` §10](uygulama-spec.md)
6. Kabloları kelepçele, kutuyu kilitle.

---

## 3. Kabul testleri

### Nokta kabulü

- [ ] Station sinyali −65 dBm veya daha iyi, link kapasitesi ölçüldü
- [ ] Önbellek kutusu içeriği merkezle senkron (`rsync` çalışıyor)
- [ ] Bir test cihazı 3 AP'nin hepsine bağlanabiliyor
- [ ] `curl -r 0-1048575` ile **206 Partial Content** dönüyor (Range çalışıyor)
- [ ] PtMP linkini kes → kutu yereldeki içeriği servis etmeye devam ediyor
- [ ] DHCP + ilişkilendirme süresi **8 saniyenin altında**

### Otobüs kabulü

- [ ] Stick ayrı beslemeden çalışıyor, **TV kapalıyken de ayakta**
- [ ] Kontak açıldığında otomatik açılıyor, kiosk ekranı geliyor
- [ ] HDMI-CEC ile TV açılıyor ve doğru girişe geçiyor
- [ ] Noktaya girince **10 saniye içinde** senkron başlıyor
- [ ] Marş sırasında resetlenmiyor (3 kez dene)
- [ ] Cihaz panelde görünüyor, heartbeat geliyor
- [ ] Oynatma logları merkeze ulaşıyor

### Sistem kabulü (pilot sonu)

- [ ] 2 hafta boyunca **hiçbir otobüse elle dokunmadan** 3 reklam değişikliği yapıldı
- [ ] Ortalama pencere başına inen bayt ölçüldü, 3 dakikaya sığıyor
- [ ] Yarım kalan indirme sonraki ziyarette tamamlandı (kayıtla kanıtlandı)
- [ ] 72 saat senkron olmayan cihaz uyarısı çalıştı
- [ ] Sessiz uygulama güncellemesi 2 cihazda çalıştı, geri dönüş test edildi
- [ ] [`uygulama-spec.md` §12](uygulama-spec.md) kırma testlerinin tamamı geçti

---

## 4. Ölçülecek metrikler (panelde dursun)

| Metrik | Neden |
|---|---|
| Cihaz başına **son senkron zamanı** | 72 saati geçen → tekniker |
| Pencere başına **inen bayt** ve **süre** | AP kapasitesi yetiyor mu? |
| **Tamamlanma oranı** (cihaz kaç içeriğe sahip / toplam) | Dağıtım sağlığı |
| Kesinti/yeniden deneme sayısı | AP yerleşimi iyi mi? |
| Cihaz **yeniden başlatma sayısı** | Güç problemi göstergesi (§2.3) |
| Disk doluluk, sıcaklık, WiFi sinyal seviyesi | Arıza öncesi uyarı |
| **Oynatma sayısı / içerik / gün** | Reklamveren raporunun temeli |
| Uygulama sürüm dağılımı | Kademeli yayımın durumu |

---

## 5. Sık çıkacak arızalar ve ilk müdahale

| Belirti | Muhtemel sebep | İlk bakılacak |
|---|---|---|
| Cihaz rastgele resetleniyor | TV USB'sinden besleme veya zayıf DC-DC | Besleme voltajını yük altında ölç |
| Noktada senkron olmuyor | WiFi profili yok (Device Owner kurulmamış) | `dpm` durumu, panelde son senkron |
| Senkron başlıyor ama hiç bitmiyor | Tek AP'de tıkanma | AP sayısı, kanal planı, kademeli başlama |
| Ekran siyah | TV girişi / CEC / stick ölü | CEC, HDMI, besleme |
| Aynı reklam sürekli dönüyor | Liste güncellenmedi veya imza reddedildi | Cihaz logu, manifest sürümü |
| Süresi geçmiş reklam oynuyor | Saat güvenilmez | `clock_trusted` alanı, son saat senkronu |
| Kare atlıyor / takılıyor | HEVC veya farklı çözünürlük | Transcode standardına uyuyor mu |
| Yaz aylarında sık arıza | Termal | Montaj yeri, havalandırma, box'a geçiş |
