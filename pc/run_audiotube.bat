@echo off
title SkullBeat - High-Fidelity Music Streaming
echo Starting SkullBeat Server...
start http://localhost:8000
py app.py 2>nul || python app.py
pause
