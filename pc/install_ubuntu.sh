#!/usr/bin/env bash
# ==============================================================================
# SkullBeat - Ubuntu / Debian Automated Installer & Desktop Integration
# Installs system dependencies, Python venv, dependencies & desktop icon
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "==============================================="
echo "💀 SkullBeat - Ubuntu Linux Installer"
echo "==============================================="

# Detect sudo availability
SUDO=""
if [ "$EUID" -ne 0 ]; then
    if command -v sudo &>/dev/null; then
        SUDO="sudo"
    fi
fi

# 1. System packages
echo "[1/4] Installing system packages (python3, pip, venv, ffmpeg, xdg-utils)..."
if [ -n "$SUDO" ] || [ "$EUID" -eq 0 ]; then
    $SUDO apt update
    $SUDO apt install -y python3 python3-pip python3-venv ffmpeg curl xdg-utils
else
    echo "⚠️ Running without sudo. If packages are missing, install with:"
    echo "   sudo apt install -y python3 python3-pip python3-venv ffmpeg curl xdg-utils"
fi

# 2. Python Virtual Environment
echo "[2/4] Setting up dedicated Python virtual environment..."
VENV_DIR="$DIR/.venv"
if [ ! -d "$VENV_DIR" ]; then
    python3 -m venv "$VENV_DIR" || python3.14 -m venv "$VENV_DIR" || python3.12 -m venv "$VENV_DIR" || true
fi

if [ -f "$VENV_DIR/bin/activate" ]; then
    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
    pip install --upgrade pip 2>/dev/null || true
    pip install -r requirements.txt
else
    echo "⚠️ Could not create virtual environment. Installing dependencies directly..."
    pip3 install --user -r requirements.txt 2>/dev/null || pip install -r requirements.txt 2>/dev/null || true
fi

# 3. Permissions
echo "[3/4] Setting execution permissions..."
chmod +x "$DIR/run_skullbeat.sh"

# 4. Ubuntu Desktop launcher entry
echo "[4/4] Creating Ubuntu Application launcher entry..."
DESKTOP_DIR="$HOME/.local/share/applications"
mkdir -p "$DESKTOP_DIR"

ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
mkdir -p "$ICON_DIR"
if [ -f "$DIR/logo.png" ]; then
    cp "$DIR/logo.png" "$ICON_DIR/skullbeat.png" 2>/dev/null || true
fi

DESKTOP_FILE="$DESKTOP_DIR/skullbeat.desktop"
cat <<EOF > "$DESKTOP_FILE"
[Desktop Entry]
Version=1.0
Type=Application
Name=SkullBeat
GenericName=Music Player
Comment=High-Fidelity Zero-Ad Music Streaming Platform
Exec=$DIR/run_skullbeat.sh
Icon=$ICON_DIR/skullbeat.png
Terminal=false
Categories=AudioVideo;Audio;Player;
Keywords=music;streaming;audio;player;skullbeat;
StartupNotify=true
EOF

chmod +x "$DESKTOP_FILE"

if command -v update-desktop-database &>/dev/null; then
    update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
fi

echo "==============================================="
echo "✅ SkullBeat installation completed successfully!"
echo ""
echo "How to start:"
echo "  1. Launch from Ubuntu Dash / App Menu: Search 'SkullBeat'"
echo "  2. Run via terminal: ./run_skullbeat.sh"
echo "  3. Open browser: http://localhost:8000"
echo "==============================================="
