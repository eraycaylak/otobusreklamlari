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

if [[ $TAM -eq 1 ]]; then
  echo "[$(date -Is)] TAM yedek (videolar dahil) aliniyor..."
  tar -czf "$DOSYA" -C "$VERI" .
else
  echo "[$(date -Is)] yedek aliniyor (videolar HARIC)..."
  # content/ haric: videolar yeniden yuklenebilir, anahtar ve kayitlar yuklenemez
  tar -czf "$DOSYA" -C "$VERI" --exclude='./content' --exclude='./incoming' --exclude='./app' .
fi

chmod 600 "$DOSYA"   # icinde OZEL ANAHTAR var
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
