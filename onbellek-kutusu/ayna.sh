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
# --max-delete     : TOPLU SILME KAZASINA KARSI SIGORTA. Merkezde DATA_DIR degisirse,
#                    disk takilmazsa veya sunucu yeniden kurulursa uzak content/
#                    BOS gorunur ve duz `--delete` noktanin TUM aynasini siler.
#                    O noktadaki her otobus ertesi gun her seyi bastan indirmeye
#                    calisir - tek internet hattiyla gunler suren bir kurtarma.
#                    Sinir asilirsa rsync silmeyi REDDEDIP hata veriyor.
# --partial-dir    : YARIM DOSYA SERVIS EDILEN ADA YAZILMAZ. Duz `--partial` yarim
#                    dosyayi NIHAI ADIYLA birakir; nginx onu servis eder, otobus tam
#                    dosya hash'inde patlar ve tum dosyayi bastan indirir - yani
#                    3 dakikalik pencere bosa gider.
# --bwlimit        : aynalama gunduz otobuslerin bant genisligini calmasin
#
# --append-verify KULLANILMIYOR (bilincli):
#   `--append`/`--append-verify` --inplace'i IMA EDER, yani veri dogrudan NIHAI
#   dosyaya yazilir ve dosya buyuyerek ilerler. Aktarim yarida kesildiginde nginx
#   KISA bir dosyayi TAM dosya gibi servis eder; otobus hash'te patlar ve pencereyi
#   bosa harcar. Ustelik --inplace, --partial-dir'i etkisiz kilar - yani yukarida
#   aldigimiz onlemi sessizce iptal ederdi.
#   Kaybettigimiz sey ne: yarim kalan aktarimin devami. Ama --partial-dir bunu zaten
#   sagliyor (rsync bir sonraki turda o dosyayi temel alir), ustelik yarim dosyayi
#   servis edilen yolun DISINDA tutarak.
AYNALA() {
  rsync -a --delete --max-delete="${MAX_SILME:-50}" \
        --partial-dir=.rsync-partial \
        --bwlimit="${BWLIMIT:-4000}" \
        "$MERKEZ:$UZAK_DIZIN/$1/" "$YEREL_DIZIN/$1/"
}

# IKISI DE DENENIYOR: `set -e` ile icerik aynalamasi basarisiz olunca APK
# aynalamasi HIC calismiyordu. Oysa bozuk bir surumden cikis yolu (fix-forward)
# tam olarak o APK'ya bagli - en kotu anda kaybedilmemesi gereken sey odur.
# rsync cikis 25 = --max-delete siniri asildi. Bu bir AG hatasi degil, bir
# SIGORTA: merkezde beklenmeyen bir bosalma var. Ayrica belirtiyoruz ki operator
# onu "gecici ag sorunu" sanip gormezden gelmesin.
AYNALA content || {
  d=$?
  if [[ $d -eq 25 ]]; then
    echo "[$(date -Is)] SILME SIGORTASI DEVREDE: merkezde ${MAX_SILME:-50}+ dosya eksik gorunuyor."
    echo "             Ayna KORUNDU. Merkezi kontrol edin (DATA_DIR, disk, yeniden kurulum)."
    echo "             Gercekten bu kadar silme gerekiyorsa: MAX_SILME=100000 $0"
  else
    echo "[$(date -Is)] HATA: icerik aynalanamadi (rsync $d)"
  fi
  HATA=1
}
AYNALA app || { echo "[$(date -Is)] HATA: APK aynalanamadi (rsync $?)"; HATA=1; }

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
# ---------------------------------------------------------------------------
# DENETIM ARTIK HER SAAT TUM AYNAYI YENIDEN HASH'LEMIYOR.
#
# Eski hali her turda content/ + app/ altindaki HER dosyayi bastan sha256'liyordu.
# Uc sorun:
#   1. Otobuslerin penceresiyle CAKISIYOR. 20 GB'lik bir aynada bu, diski ve CPU'yu
#      dakikalarca doyurur; tam o anda noktaya giren otobus dosyalari YAVAS ceker ve
#      3 dakikasinin bir kismini kaybeder. Kutunun tek isi o pencerede hizli olmak.
#   2. KILIDI TUTUYOR. Denetim flock altinda kosuyor, yani bir sonraki aynalama turu
#      da bekliyor.
#   3. nice/ionice YOK: islem, nginx ile ayni onceliktre yarisiyor.
#
# Yeni davranis: DEGISMEYEN dosya yeniden hash'lenmez. Dogrulanmis her dosyanin
# ad+boyut+mtime'i bir durum dosyasinda tutuluyor; tur yalnizca YENI veya DEGISMIS
# dosyalari hash'liyor. Ayrica gunde bir kez (TAM_DENETIM_SAATI, varsayilan 03:00 -
# otobusler yokken) tam denetim yapiliyor: eMMC bozulmasi dosyayi degistirmeden
# icerigini bozabilir, yani artimli denetim tek basina yetmez.
#
# nice + ionice: denetim her zaman nginx'e yol verir.
# ---------------------------------------------------------------------------
DURUM="${DURUM:-$YEREL_DIZIN/.dogrulandi}"
TAM_DENETIM_SAATI="${TAM_DENETIM_SAATI:-3}"
SAAT=$(date +%-H)
TAM_DENETIM=0
[[ "$SAAT" == "$TAM_DENETIM_SAATI" ]] && TAM_DENETIM=1
[[ "${TAM_DENETIM_ZORLA:-0}" == "1" ]] && TAM_DENETIM=1

NICE=()
command -v nice >/dev/null 2>&1 && NICE=(nice -n 19)
command -v ionice >/dev/null 2>&1 && NICE+=(ionice -c3)

BOZUK=0
DENETLENEN=0
ATLANAN=0
if command -v sha256sum >/dev/null 2>&1; then
  touch "$DURUM" 2>/dev/null || true
  YENI_DURUM=$(mktemp) || YENI_DURUM="$DURUM.yeni"
  if [[ $TAM_DENETIM -eq 1 ]]; then
    echo "[$(date -Is)] TAM butunluk denetimi (saat $SAAT) - tum dosyalar yeniden hash'lenecek"
  fi
  while IFS= read -r dosya; do
    ad=$(basename "$dosya")
    beklenen="${ad%%.*}"
    # 64 onaltilik karakter degilse icerik adresli degil (orn. known-good.apk)
    [[ "$beklenen" =~ ^[0-9a-f]{64}$ ]] || continue

    # Parmak izi: ad + boyut + mtime. Degismediyse ve tam denetim turu degilse atla.
    if imza=$(stat -c '%n|%s|%Y' "$dosya" 2>/dev/null); then :; else continue; fi
    if [[ $TAM_DENETIM -eq 0 ]] && grep -qxF "$imza" "$DURUM" 2>/dev/null; then
      printf '%s\n' "$imza" >> "$YENI_DURUM"
      ATLANAN=$((ATLANAN+1))
      continue
    fi

    gercek=$("${NICE[@]}" sha256sum "$dosya" | cut -d' ' -f1)
    DENETLENEN=$((DENETLENEN+1))
    if [[ "$gercek" != "$beklenen" ]]; then
      echo "[$(date -Is)] BOZUK DOSYA SILINIYOR: $ad (hash tutmadi)"
      rm -f "$dosya"
      BOZUK=$((BOZUK+1))
    else
      printf '%s\n' "$imza" >> "$YENI_DURUM"
    fi
    # .rsync-partial altindaki yarim dosyalar HENUZ tam degil: denetime girmemeli
  done < <(find "$YEREL_DIZIN/content" "$YEREL_DIZIN/app" \
                -type d -name '.rsync-partial' -prune -o \
                -type f ! -name '.*' -print 2>/dev/null)
  # Durum dosyasi ATOMIK guncellenir: yarim bir liste, saglam dosyalari "hic
  # dogrulanmamis" gosterip bir sonraki turda gereksiz tam hash'e yol acardi.
  mv -f "$YENI_DURUM" "$DURUM" 2>/dev/null || true
  chmod 600 "$DURUM" 2>/dev/null || true
  echo "[$(date -Is)] butunluk: $DENETLENEN hash'lendi, $ATLANAN degismedigi icin atlandi"
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
