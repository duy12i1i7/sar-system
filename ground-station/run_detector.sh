#!/bin/bash
cd /home/ubuntu
export YOLO_MODEL=yolo26s.pt                       # NGUOI (COCO)
export FIRE_MODEL=/home/ubuntu/firedetect-11s.pt   # LUA/KHOI (Fire/Smoke)
export PI_HOST=10.10.10.2
export DETECT_FPS=8
export DETECT_CONF=0.35
export FIRE_CONF=0.4
export DISPLAY_W=854
exec /home/ubuntu/detector-env/bin/python /home/ubuntu/detector.py
