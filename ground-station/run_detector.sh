#!/bin/bash
cd /home/ubuntu
export YOLO_MODEL=yolo26s.pt
export PI_HOST=10.10.10.2
export DETECT_FPS=3
exec /home/ubuntu/detector-env/bin/python /home/ubuntu/detector.py
