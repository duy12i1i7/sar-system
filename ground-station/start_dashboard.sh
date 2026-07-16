#!/bin/bash
export PATH=$PATH:/snap/bin:/usr/local/bin:/usr/bin

cd ~/drone-dashboard
pkill -f "vite"
nohup npm run dev -- --host > ~/frontend.log 2>&1 &
sleep 5

# Try to open in Kiosk mode (full screen without toolbars)
if command -v google-chrome &> /dev/null; then
    google-chrome --kiosk --app="http://localhost:5173"
elif command -v chromium-browser &> /dev/null; then
    chromium-browser --kiosk --app="http://localhost:5173"
elif command -v firefox &> /dev/null; then
    firefox --kiosk "http://localhost:5173"
else
    xdg-open "http://localhost:5173"
fi
