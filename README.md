# Otobüs İçi Reklam Yayın Sistemi — Plan

**Amaç:** USB bellek takıp-çıkarmayı tamamen bitirmek. Reklam videosu merkezi panele bir kez
yüklenecek; otobüsteki oynatıcı dosyayı kendi başına indirecek, sırayla yayınlayacak ve ne
oynattığını rapora yazacak.

Ayrıntılı plan: [`docs/plan.md`](docs/plan.md)

---

## 1. Önce matematiği düzeltelim — asıl darboğaz sandığın yerde değil

| Soru | Cevap |
|---|---|
| 50 MB, 3 dakikada iner mi? | Gerekli hız sadece **2,3 Mbps**. 4G tipik 10–50 Mbps verir → **8–40 saniye**. Yani iner. |
| Peki risk yok mu? | Var: **20 otobüs aynı tek WiFi'ye** binerse 3 dakikada 50 MB × 20 = 1 GB sığmaz. |
| En büyük kazanç nerede? | **Videoyu sunucuda yeniden sıkıştırmak.** 30 sn'lik reklam 18–22" ekran için 2–3 Mbps yeter → dosya **50 MB değil ~8–11 MB**. Sorun %80 buharlaşır. |
| Aylık veri / otobüs | ~20 reklam değişikliği × 10 MB + telemetri ≈ **250 MB/ay**. Küçük kotalı M2M hat yeter. |

**Sonuç:** Sistemi "3 dakikalık pencere"ye göre tasarlamayalım. Otobüse 4G koyarsak dosya
*gün boyunca yolda* iner; toplanma alanı sadece bonus olur.

---

## 2. Bağlantı seçenekleri — karşılaştırma

| # | Yöntem | Artı | Eksi | Karar |
|---|---|---|---|---|
| **A** | Otobüste 4G/LTE modem (M2M SIM) | Her yerde çalışır, anlık komut kanalı (reklamı acil kaldırma), pencere derdi yok | Otobüs başı aylık hat ücreti | **Ana yol** |
| **B** | Toplanma alanında WiFi + yerel önbellek sunucusu | Tek hat 20 otobüse hizmet eder, veri maliyeti ~0 | Otobüs alana gelmezse içerik eskir | **İkinci katman (A ile birlikte)** |
| **C** | Point-to-Point kablosuz köprü (alanda internet yoksa) | Alana 100+ Mbps taşır, ~$65–100/uç | Görüş hattı ve direk/elektrik gerekir | B'nin ön koşulu, alternatifi 4G CPE |
| **D** | Alanda 4G CPE router (PtP yerine) | Görüş hattı yok, kurulum 1 saat | Alan kapsamasına bağlı | **C yerine ilk denenecek** |
| **E** | "Akıllı USB" (Pi Zero USB gadget) | TV'nin **sadece USB girişi** varsa kurtarır, TV'ye dokunmaz | Yavaş, kırılgan, ölçeklenmez | Sadece HDMI'sı olmayan TV'ler için |
| **F** | Otobüsler arası P2P (biri indirir, diğerlerine dağıtır) | Veri maliyetini böler | Karmaşık, 50+ araçtan önce anlamsız | Faz 3 / opsiyonel |
| **G** | Şoför telefonu hotspot | Bedava | Güvenilmez, şoföre bağlı | Sadece acil kurtarma |
| **H** | Elden USB (mevcut yöntem) | — | Şu anki derdin | **Yedek olarak kalsın, silme** |

**Önerilen kombinasyon: A (ana) + B/D (bedava hızlandırıcı) + H (yedek).**

---

## 3. Parçalı indirme ("3 dakika durdu, 20 MB indi") — çözülmüş bir problem

HTTP **Range / 206 Partial Content** ile dosya kaldığı bayttan devam eder. Kural seti:

1. Dosya **parçalara** bölünür (4 MB), her parçanın SHA-256'sı manifestte yazılıdır.
2. Bağlantı kopunca yarım dosya **silinmez**, ilerleme diske yazılır (`aria2c -c`, `.aria2` kontrol dosyası).
3. Ağ dönünce kaldığı yerden devam; her parça indikçe doğrulanır.
4. Dosya **%100 bitip hash doğrulanmadan** oynatma listesine girmez.
5. Eski video, yenisi doğrulanana kadar **silinmez** → ekran asla boş kalmaz.
6. Geçiş **atomik**: `playlist.json` son anda tek hamlede değişir.

---

## 4. Yol haritası

| Faz | Süre | İş |
|---|---|---|
| **0** | 1 hafta | 1 otobüste tek ekranın modeli, girişi (HDMI var mı?), voltajı, sigortası ölçülür. Alanda 4G hız testi yapılır. |
| **1 — Pilot** | 2–3 hafta | **2 otobüs.** Hazır CMS (Xibo self-hosted / Yodeck) + 4G'li Android oynatıcı. Amaç: elini USB'ye hiç sürmemek. |
| **2 — Yaygınlaştırma** | 4–6 hafta | 10–20 otobüs, alanda WiFi + önbellek sunucusu, transcode hattı, izleme paneli. |
| **3 — Ticarileştirme** | sonrası | Oynatma kanıtı (proof-of-play) raporu, reklamveren paneli, hat/güzergâh bazlı hedefleme, faturaya bağlı rapor. |

Pilotta hazır yazılımla başla, kendi sistemini **Faz 2'de** yaz. İlk gün kendi CMS'ini yazmaya
kalkarsan 3 ay USB taşımaya devam edersin.
