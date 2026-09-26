#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Merkez sunucudaki icerigi noktaya AYNALAR.
#
# Neden proxy_cache degil de ayna: PtMP linki koptugunda proxy onbellegi
# eksik dosyalar icin caresiz kalir. Ayna ise noktayi TAM BAGIMSIZ yapar -
# link gunlerce kopuk olsa bile otobusler icerik almaya devam eder.
#
# cron: 0 * * * *  /opt/reklam/ayna.sh >> /var/log/reklam-ayna.log 2>&1
# ---------------------------------------------------------------------------
set -euo pipefail

MERKEZ="${MERKEZ:-reklam@10.0.0.5}"
UZAK_DIZIN="${UZAK_DIZIN:-/opt/reklam/sunucu/data}"
YEREL_DIZIN="${YEREL_DIZIN:-/srv/reklam}"

mkdir -p "$YEREL_DIZIN/content" "$YEREL_DIZIN/app"

echo "[$(date -Is)] aynalama basliyor: $MERKEZ:$UZAK_DIZIN -> $YEREL_DIZIN"

# --delete: merkezde silinen icerik noktadan da silinsin (disk sismesin)
# --partial --append-verify: yavas/kesintili PtMP linkinde kaldigi yerden devam
# --bwlimit: aynalama gunduz otobuslerin bant genisligini calmasin
rsync -a --delete --partial --append-verify \
      --bwlimit="${BWLIMIT:-4000}" \
      "$MERKEZ:$UZAK_DIZIN/content/" "$YEREL_DIZIN/content/"

rsync -a --delete --partial --append-verify \
      --bwlimit="${BWLIMIT:-4000}" \
      "$MERKEZ:$UZAK_DIZIN/app/" "$YEREL_DIZIN/app/"

ICERIK_SAYISI=$(find "$YEREL_DIZIN/content" -type f | wc -l)
TOPLAM=$(du -sh "$YEREL_DIZIN" | cut -f1)
echo "[$(date -Is)] aynalama tamam: $ICERIK_SAYISI dosya, $TOPLAM"
