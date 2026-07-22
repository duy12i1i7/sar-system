#!/bin/bash
# Tao nhanh "<path>-web" cho trinh duyet xem qua WebRTC.
#
# VI SAO PHAI CO NHANH RIENG:
#   Firefox chi CHAO duy nhat profile-level-id=42e01f (Constrained Baseline 3.1) trong
#   SDP offer, va tu choi answer khac ("Answer had no codecs in common with offer" - da
#   thu va SDP, Firefox chan thang). Nen bat cu luong nao vuot profile/level do la trinh
#   duyet dung SPS -> khong giai ma duoc -> 0 fps + bao PLI (do duoc voi DJI Fly:
#   High profile level 4.0 1920x1088 -> 0 fps, PLI 5 lan/giay, mat goi 0).
#
# NGUYEN TAC O DAY: **nhan sao phat vay khi co the**.
#   - Luong DA tuong thich (Baseline/Constrained Baseline, <=720p) -> chi REMUX `-c copy`,
#     KHONG dung toi bit video: khong mat chat luong, khong them tre encode.
#   - Chi khi luong vuot chuan (vd DJI Fly) moi transcode, va scale kieu "khong bao gio
#     phong to" de giu nguyen anh neu no von da nho hon 720p.
#
# Doi profile giua chung: MediaMTX co runOnReadyRestart nen script nay duoc chay lai;
# ngoai ra dau ra luon co dinh 720p Baseline nen TRINH DUYET KHONG BAO GIO phai thoa
# thuan lai codec - do la ly do transcode lai AN TOAN hon passthrough khi phi co doi che do.
set -u
PATH_NAME="${1:?thieu ten path}"
SRC="rtsp://127.0.0.1:8554/${PATH_NAME}"
DST="rtmp://127.0.0.1:1935/${PATH_NAME}-web"

# runOnReady chay NGAY khi path vua ready, thuong la truoc khi co keyframe dau tien nen
# ffprobe tra ve rong. Thu lai vai lan thay vi mac dinh "lech chuan" - neu khong, luong
# nao cung bi encode lai, dung y do "nhan sao phat vay khi co the".
# Dung CSV dau phay (mac dinh). ĐUNG dat separator la dau cach kieu `csv=p=0:s=' '`:
# ffprobe khong parse duoc ("Failed to parse option string") va vi bi nuot stderr nen
# nhin nhu la "khong doc duoc luong" - da mat mot vong debug vi cho nay.
PROFILE=""; WIDTH=0; HEIGHT=0
for i in 1 2 3 4 5 6; do
  INFO="$(ffprobe -v error -select_streams v:0 \
            -show_entries stream=profile,width,height -of csv=p=0 \
            "$SRC" 2>/dev/null | head -1)"
  IFS=, read -r P W H <<<"${INFO:-}"
  if [ -n "${P:-}" ] && [ "${H:-0}" -gt 0 ] 2>/dev/null; then
    PROFILE="$P"; WIDTH="$W"; HEIGHT="$H"; break
  fi
  echo "[restream] $PATH_NAME: chua doc duoc thong so (lan $i), thu lai…"
  sleep 2
done
if [ -z "$PROFILE" ]; then
  echo "[restream] $PATH_NAME: KHONG doc duoc profile -> transcode cho chac"
  PROFILE="?"
fi
echo "[restream] $PATH_NAME: nguon profile='$PROFILE' ${WIDTH}x${HEIGHT}"

COMPATIBLE=0
case "$PROFILE" in
  "Constrained Baseline"|"Baseline")
    [ "$HEIGHT" -gt 0 ] && [ "$HEIGHT" -le 720 ] && [ "$WIDTH" -le 1280 ] && COMPATIBLE=1 ;;
esac

if [ "$COMPATIBLE" = "1" ]; then
  echo "[restream] $PATH_NAME: da hop chuan -> REMUX -c copy (khong encode lai)"
  exec ffmpeg -nostdin -hide_banner -loglevel warning \
    -i "$SRC" -an -c:v copy -f flv "$DST"
fi

echo "[restream] $PATH_NAME: lech chuan -> transcode NVENC Baseline 3.1 (<=720p)"
exec ffmpeg -nostdin -hide_banner -loglevel warning \
  -i "$SRC" -an \
  -c:v h264_nvenc -preset p4 -tune ll -profile:v baseline -level 3.1 \
  -vf "scale='min(1280,iw)':'min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2" \
  -g 50 -b:v 3M -maxrate 3M -bufsize 6M \
  -f flv "$DST"
