#!/bin/bash
mkdir -p /opt/mediamtx && cd /opt/mediamtx
LATEST_URL=$(curl -s https://api.github.com/repos/bluenviron/mediamtx/releases/latest | grep "browser_download_url.*linux_arm64.tar.gz" | cut -d : -f 2,3 | tr -d \")
# trim leading space
LATEST_URL=$(echo $LATEST_URL | xargs)
wget -q -O mediamtx.tar.gz "$LATEST_URL"
tar -zxvf mediamtx.tar.gz
rm mediamtx.tar.gz
