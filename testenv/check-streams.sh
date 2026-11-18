#!/usr/bin/env bash
# Controleert alle streams van de testomgeving via RTSP over TCP (vereist ffprobe).
#   ./check-streams.sh [host]
set -uo pipefail
HOST="${1:-127.0.0.1}"
command -v ffprobe >/dev/null || { echo "ffprobe niet gevonden (installeer ffmpeg)"; exit 1; }
streams=$(curl -fsS "http://$HOST:5000/api/go2rtc/streams" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)))') \
  || { echo "Frigate-mock op http://$HOST:5000 niet bereikbaar"; exit 1; }
rc=0
for s in $streams; do
  info=$(timeout 15 ffprobe -v error -rtsp_transport tcp -show_entries stream=codec_type,codec_name,width,height \
         -of csv=p=0 "rtsp://$HOST:8554/$s" 2>/dev/null | tr '\n' ' ')
  if [[ -z "$info" ]]; then echo "FOUT  $s: geen stream-informatie"; rc=1; else printf "OK    %-14s %s\n" "$s" "$info"; fi
done
exit $rc
