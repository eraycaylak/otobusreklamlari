#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Merkez sunucudaki icerigi noktaya AYNALAR.
#
# Neden proxy_cache degil de ayna: PtMP linki koptugunda proxy onbellegi
# eksik dosyalar icin caresiz kalir. Ayna ise noktayi TAM BAGIMSIZ yapar -
# link gunlerce kopuk olsa bile otobusler icerik almaya devam eder.
#
# cron: 7 * * * *  /opt/reklam/ayna.sh >> /var/log/reklam-ayna.log 2>&1
# ---------------------------------------------------------------------------
set -uo pipefail

MERKEZ="${MERKEZ:-reklam@10.0.0.5}"
UZAK_DIZIN="${UZAK_DIZIN:-/opt/reklam/sunucu/data}"
YEREL_DIZIN="${YEREL_DIZIN:-/srv/reklam}"
KILIT="${KILIT:-/var/lock/reklam-ayna.lock}"

# ---------------------------------------------------------------------------
# TEK ORNEK.
#
# Aynalama saatte bir tetikleniyor ama yavas/kesintili bir PtMP linkinde bir
# saatten UZUN surebilir. Kilit olmadan iki rsync ayni gecici dosyalara yazar:
# sonuc, tam da onlemeye calistigimiz sey - bozuk dosya. flock yoksa devam
# ediyoruz (kurulum eksik olabilir), ama uyariyoruz.
# ---------------------------------------------------------------------------
if command -v flock >/dev/null 2>&1; then
  exec 9>"$KILIT" || true
  if ! flock -n 9; then
    echo "[$(date -Is)] onceki aynalama hala calisiyor, bu tur atlaniyor"
    exit 0
  fi
else
  echo "[$(date -Is)] UYARI: flock yok - es zamanli aynalama engellenemiyor"
fi

mkdir -p "$YEREL_DIZIN/content" "$YEREL_DIZIN/app"

echo "[$(date -Is)] aynalama basliyor: $MERKEZ:$UZAK_DIZIN -> $YEREL_DIZIN"

HATA=0

# --delete         : merkezde silinen icerik noktadan da silinsin (disk sismesin)
# --partial-dir    : YARIM DOSYA SERVIS EDILEN ADA YAZILMAZ.
#                    Duz `--partial` yarim dosyayi NIHAI ADIYLA birakir; nginx onu
#                    servis eder, otobus tam dosya hash'inde patlar ve tum dosyayi
#                    bastan indirir - yani 3 dakikalik pencere bosa gider. Gizli bir
#                    dizinde tutmak devam etme kazancini korur, riski kaldirir.
# --append-verify  : yavas/kesintili linkte kaldigi yerden devam (dosyalar icerik
#                    adresli ve DEGISMEZ oldugu icin ekleme guvenli)
# --bwlimit        : aynalama gunduz otobuslerin bant genisligini calmasin
AYNALA() {
  rsync -a --delete --partial-dir=.rsync-partial --append-verify \
        --bwlimit="${BWLIMIT:-4000}" \
        "$MERKEZ:$UZAK_DIZIN/$1/" "$YEREL_DIZIN/$1/"
}

# IKISI DE DENENIYOR: `set -e` ile icerik aynalamasi basarisiz olunca APK
# aynalamasi HIC calismiyordu. Oysa bozuk bir surumden cikis yolu (fix-forward)
# tam olarak o APK'ya bagli - en kotu anda kaybedilmemesi gereken sey odur.
AYNALA content || { echo "[$(date -Is)] HATA: icerik aynalanamadi"; HATA=1; }
AYNALA app     || { echo "[$(date -Is)] HATA: APK aynalanamadi";    HATA=1; }

# ---------------------------------------------------------------------------
# BUTUNLUK DENETIMI - bu aynanin en degerli parcasi.
#
# Dosya adlari ICERIK ADRESLI (ad = sha256), yani dogrulama icin merkeze sormaya
# gerek yok: dosyanin kendisi adini dogrulamali.
#
# Neden gerekli: rsync dosyalari boyut+tarihe gore atlar. eMMC bozulmasi, dolu
# disk veya yarida kesilen bir yazma yuzunden DOGRU BOYUTTA ama BOZUK bir dosya
# olusursa rsync onu bir daha hic transfer etmez. O noktadaki HER otobus dosyayi
# indirir, tam dosya hash'i tutmaz, bastan indirir - ve bu sonsuza kadar tekrarlanir.
# Sessiz, kalici ve tum noktayi etkileyen bir ariza.
#
# Bozuk dosyayi siliyoruz: bir sonraki aynalama onu yeniden getirir.
# ---------------------------------------------------------------------------
BOZUK=0
if command -v sha256sum >/dev/null 2>&1; then
  while IFS= read -r dosya; do
    ad=$(basename "$dosya")
    beklenen="${ad%%.*}"
    # 64 onaltilik karakter degilse icerik adresli degil (orn. known-good.apk)
    [[ "$beklenen" =~ ^[0-9a-f]{64}$ ]] || continue
    gercek=$(sha256sum "$dosya" | cut -d' ' -f1)
    if [[ "$gercek" != "$beklenen" ]]; then
      echo "[$(date -Is)] BOZUK DOSYA SILINIYOR: $ad (hash tutmadi)"
      rm -f "$dosya"
      BOZUK=$((BOZUK+1))
    fi
    # .rsync-partial altindaki yarim dosyalar HENUZ tam degil: denetime girmemeli
  done < <(find "$YEREL_DIZIN/content" "$YEREL_DIZIN/app" \
                -type d -name '.rsync-partial' -prune -o \
                -type f ! -name '.*' -print 2>/dev/null)
else
  echo "[$(date -Is)] UYARI: sha256sum yok - butunluk denetimi atlandi"
fi

ICERIK_SAYISI=$(find "$YEREL_DIZIN/content" -type d -name '.rsync-partial' -prune -o \
                     -type f ! -name '.*' -print | wc -l)
TOPLAM=$(du -sh "$YEREL_DIZIN" | cut -f1)
echo "[$(date -Is)] aynalama tamam: $ICERIK_SAYISI dosya, $TOPLAM, bozuk: $BOZUK"

# Bozuk dosya bulunduysa HEMEN yeniden cek: otobusler gelmeden once yerinde olsun.
if [[ $BOZUK -gt 0 ]]; then
  echo "[$(date -Is)] bozuk dosyalar icin yeniden aynalama"
  AYNALA content || HATA=1
  AYNALA app     || HATA=1
fi

exit $HATA
