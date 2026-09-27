#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Merkez sunucu yedegi.
#
# NEDEN KRITIK: data/keys/ed25519-private.pem kaybolursa yeni manifest
# imzalanamaz. Yeni anahtarla devam etmek, sahadaki TUM cihazlarin yeniden
# provizyonu (yani her otobuse tek tek gitmek) demektir.
#
# Kullanim:
#   ./scripts/yedekle.sh                      -> anahtarlar + veritabani + loglar
#   ./scripts/yedekle.sh --tam                -> videolar da dahil (buyuk)
#   HEDEF=/mnt/yedek ./scripts/yedekle.sh
#
# cron: 0 2 * * *  /opt/reklam/sunucu/scripts/yedekle.sh >> /var/log/reklam-yedek.log 2>&1
# ---------------------------------------------------------------------------
set -euo pipefail

KOK="$(cd "$(dirname "$0")/.." && pwd)"
VERI="${DATA_DIR:-$KOK/data}"
HEDEF="${HEDEF:-$KOK/yedekler}"
TAM=0
[[ "${1:-}" == "--tam" ]] && TAM=1

[[ -d "$VERI" ]] || { echo "veri dizini yok: $VERI"; exit 1; }
mkdir -p "$HEDEF"

DAMGA=$(date +%Y%m%d-%H%M%S)
DOSYA="$HEDEF/reklam-yedek-$DAMGA.tar.gz"

# ---------------------------------------------------------------------------
# IZINLER ICERIKTEN ONCE.
#
# chmod ONCEDEN veriliyor ve dosya bos olarak olusturuluyor: yedek hicbir an
# okunabilir izinlerle OZEL ANAHTAR tasimasin. Onceden chmod tar'DAN SONRAYDI ve
# ikili bir sorun vardi:
#   - tar, okurken degisen bir dosya gorurse ("file changed as we read it") 1 ile
#     cikar. Bu kurulumda bu KACINILMAZ: oynatma loglari append-only NDJSON ve
#     yedek cron'da, cihazlar log yuklerken calisiyor.
#   - `set -e` o cikisi olumcul sayip betigi chmod'a GELMEDEN oldururdu
# Sonuc: 0644 izinli, ozel imzalama anahtari iceren bir tar.gz geride kalirdi ve
# betik "basarisiz" gorundugu icin kimse dosyaya bakmazdi.
# ---------------------------------------------------------------------------
umask 077
: > "$DOSYA"
chmod 600 "$DOSYA"   # icinde OZEL ANAHTAR var

TAR_DURUM=0
if [[ $TAM -eq 1 ]]; then
  echo "[$(date -Is)] TAM yedek (videolar dahil) aliniyor..."
  tar -czf "$DOSYA" -C "$VERI" . || TAR_DURUM=$?
else
  echo "[$(date -Is)] yedek aliniyor (videolar HARIC)..."
  # content/ haric: videolar yeniden yuklenebilir, anahtar ve kayitlar yuklenemez
  tar -czf "$DOSYA" -C "$VERI" --exclude='./content' --exclude='./incoming' --exclude='./app' . || TAR_DURUM=$?
fi

# tar cikis kodlari: 1 = UYARI (dosya okunurken degisti), 2 = OLUMCUL hata.
# 1'i kabul ediyoruz cunku append-only loglarla kacinilmaz; 2'de duruyoruz.
if [[ $TAR_DURUM -eq 1 ]]; then
  echo "[$(date -Is)] UYARI: bazi dosyalar okunurken degisti (append-only loglar) - yedek gecerli"
elif [[ $TAR_DURUM -ne 0 ]]; then
  echo "[$(date -Is)] HATA: tar olumcul hata verdi ($TAR_DURUM) - yedek SILINIYOR"
  rm -f "$DOSYA"
  exit 1
fi

# Anahtar gercekten iceride mi? "Yedek var" sanip anahtarsiz bir arsiv tutmak,
# yedek olmamasindan kotudur: fark ancak anahtar kaybolunca anlasilir.
if ! tar -tzf "$DOSYA" 2>/dev/null | grep -q 'keys/ed25519-private.pem'; then
  echo "[$(date -Is)] HATA: yedekte ozel imzalama anahtari YOK - yedek ise yaramaz"
  echo "             (veri dizini: $VERI - anahtar uretildi mi?)"
  rm -f "$DOSYA"
  exit 1
fi
BOYUT=$(du -h "$DOSYA" | cut -f1)
echo "[$(date -Is)] tamam: $DOSYA ($BOYUT)"

# Son 14 yedegi tut
mapfile -t ESKILER < <(ls -1t "$HEDEF"/reklam-yedek-*.tar.gz 2>/dev/null | tail -n +15)
for f in "${ESKILER[@]:-}"; do
  [[ -n "$f" ]] && { rm -f "$f"; echo "eski yedek silindi: $(basename "$f")"; }
done

cat <<SON

UYARI: Bu dosya OZEL IMZALAMA ANAHTARINI icerir.
  - Sunucunun kendisinde tutmayin; baska bir makineye/diske kopyalayin
  - Paylasilan bir dizine veya genel bir bulut klasorune koymayin
SON
