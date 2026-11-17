#!/usr/bin/env bash
# ==============================================================================
# SkullBeat - Linux / Ubuntu Launcher
# High-Fidelity Zero-Ad Music Streaming
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "==============================================="
echo "💀 Starting SkullBeat on Ubuntu Linux"
echo "==============================================="

# 1. Verify python3 availability
if ! command -v python3 &>/dev/null; then
    echo "❌ Error: python3 is not installed."
    echo "Install with: sudo apt update && sudo apt install -y python3 python3-pip python3-venv ffmpeg"
    exit 1
fi

# 2. Virtual environment setup / detection
VENV_DIR="$DIR/.venv"
if [ -d "$VENV_DIR" ] && [ -f "$VENV_DIR/bin/activate" ]; then
    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
    PYTHON="$VENV_DIR/bin/python3"
    PIP="$VENV_DIR/bin/pip"
else
    PYTHON="python3"
    PIP="pip3"
fi

# 3. Check for ffmpeg (optional but strongly recommended for audio streaming)
if ! command -v ffmpeg &>/dev/null; then
    echo "💡 Note: ffmpeg is recommended for high-quality audio streaming & downloading."
    echo "   Run: sudo apt install -y ffmpeg"
fi

# 4. Check dependencies
if ! $PYTHON -c "import fastapi, uvicorn, ytmusicapi, yt_dlp, pydantic" 2>/dev/null; then
    echo "📦 Required packages missing. Installing via $PIP..."
    if command -v "$PIP" &>/dev/null; then
        $PIP install -r requirements.txt || true
    else
        echo "⚠️  pip is not installed. Run: ./install_ubuntu.sh"
    fi
fi

# 5. Open browser in background after short delay
(
    sleep 1.2
    TARGET_URL="http://localhost:8000"
    if command -v xdg-open &>/dev/null; then
        xdg-open "$TARGET_URL" 2>/dev/null || true
    elif command -v sensible-browser &>/dev/null; then
        sensible-browser "$TARGET_URL" 2>/dev/null || true
    elif command -v google-chrome &>/dev/null; then
        google-chrome "$TARGET_URL" 2>/dev/null || true
    elif command -v firefox &>/dev/null; then
        firefox "$TARGET_URL" 2>/dev/null || true
    fi
) &

# 6. Start FastAPI server
echo "🚀 SkullBeat server running at http://localhost:8000"
echo "Press Ctrl+C to stop the server."
exec $PYTHON app.py
