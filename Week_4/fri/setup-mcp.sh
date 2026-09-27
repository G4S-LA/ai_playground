#!/bin/sh
set -eu

VERSION="1.1.2"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
TARGET_DIR="$SCRIPT_DIR/.mcp"

OS=$(uname -s)
ARCH=$(uname -m)
case "$OS/$ARCH" in
  Darwin/arm64) PLATFORM="darwin_arm64"; CHECKSUM="1cf37267f37830f969bb8a6d0160b04e2aaf46e23bb6868dbfe15b46efe20734"; EXTENSION="tar.gz"; BINARY="excel-mcp" ;;
  Darwin/x86_64) PLATFORM="darwin_amd64"; CHECKSUM="57fa03c68f6616b9662c40b5e5d8c6d59e2bb9056d68a068df532b7dc1ded695"; EXTENSION="tar.gz"; BINARY="excel-mcp" ;;
  Linux/x86_64) PLATFORM="linux_amd64"; CHECKSUM="830140992cc5764aed440e161c65e9e2048df5eb5c97a9b63f58d5a9efbba2a0"; EXTENSION="tar.gz"; BINARY="excel-mcp" ;;
  Linux/aarch64|Linux/arm64) PLATFORM="linux_arm64"; CHECKSUM="7b02bf29148fe2bb1573f7db24ba71e264c2ac18cb998de8840c57cc8550902f"; EXTENSION="tar.gz"; BINARY="excel-mcp" ;;
  MINGW*/x86_64|MSYS*/x86_64|CYGWIN*/x86_64) PLATFORM="windows_amd64"; CHECKSUM="134198fbce7885a23a2aa87a44016f1eba71ae522a74dc168696d09b5b31c73f"; EXTENSION="zip"; BINARY="excel-mcp.exe" ;;
  MINGW*/aarch64|MINGW*/arm64|MSYS*/aarch64|MSYS*/arm64|CYGWIN*/aarch64|CYGWIN*/arm64) PLATFORM="windows_arm64"; CHECKSUM="a2f4b17176e5a17df3cc69ed07a3208cf0e5a228e0962bea9495c8b5a94d3081"; EXTENSION="zip"; BINARY="excel-mcp.exe" ;;
  *) echo "Unsupported platform: $OS/$ARCH" >&2; exit 1 ;;
esac

TARGET="$TARGET_DIR/$BINARY"
mkdir -p "$TARGET_DIR"
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM
ARCHIVE="$TEMP_DIR/excel-mcp.$EXTENSION"
URL="https://github.com/ralscha/excel-mcp/releases/download/v$VERSION/excel-mcp_${VERSION}_${PLATFORM}.$EXTENSION"

echo "Downloading Excel MCP $VERSION for $PLATFORM..."
curl -fsSL "$URL" -o "$ARCHIVE"
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
else
  ACTUAL=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')
fi
if [ "$ACTUAL" != "$CHECKSUM" ]; then
  echo "Checksum mismatch for Excel MCP: expected $CHECKSUM, got $ACTUAL" >&2
  exit 1
fi
if [ "$EXTENSION" = "zip" ]; then
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ARCHIVE" -d "$TEMP_DIR"
  elif command -v powershell.exe >/dev/null 2>&1 && command -v cygpath >/dev/null 2>&1; then
    ARCHIVE_WINDOWS=$(cygpath -w "$ARCHIVE")
    TEMP_WINDOWS=$(cygpath -w "$TEMP_DIR")
    powershell.exe -NoProfile -NonInteractive -Command \
      "Expand-Archive -LiteralPath '$ARCHIVE_WINDOWS' -DestinationPath '$TEMP_WINDOWS' -Force"
  else
    echo "Cannot unpack $ARCHIVE: install unzip or run the script from Git Bash with PowerShell available." >&2
    exit 1
  fi
else
  tar -xzf "$ARCHIVE" -C "$TEMP_DIR" "$BINARY"
fi
mv "$TEMP_DIR/$BINARY" "$TARGET"
chmod +x "$TARGET"
echo "Installed: $TARGET"
