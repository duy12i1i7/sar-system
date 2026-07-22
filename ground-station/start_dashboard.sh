#!/bin/bash
# Chi mo trinh duyet kiosk. Vite KHONG con khoi dong o day nua - no da thanh
# systemd service `drone-dashboard` (co Restart=always + health-check trong chung).
export PATH=$PATH:/snap/bin:/usr/local/bin:/usr/bin

# Doi vite san sang roi moi mo kiosk (toi da 60s).
for _ in $(seq 1 60); do
    curl -sf -o /dev/null http://localhost:5173/ && break
    sleep 1
done

if command -v firefox &> /dev/null; then
    firefox --kiosk "http://localhost:5173"
elif command -v google-chrome &> /dev/null; then
    google-chrome --kiosk --app="http://localhost:5173"
elif command -v chromium-browser &> /dev/null; then
    chromium-browser --kiosk --app="http://localhost:5173"
else
    xdg-open "http://localhost:5173"
fi
