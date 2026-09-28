#!/usr/bin/env bash
# Minecraft MCP - one command install.
#
#   curl -fsSL https://raw.githubusercontent.com/Mishaadevv/minecraft-mcp/main/install.sh | bash
#
# It puts the mod where the game looks for it, checks that Fabric is ready for 1.21.11, tells you which MCP
# URL your agent should be pointed at, and leaves a configure script next to it for the popular agents.
#
# Options, all optional:
#   MC_DIR=...            the .minecraft folder to install into
#   MCP_PORT=25585        the port the mod will listen on (written into its config)
#   WITH_FABRIC_API=1     also download Fabric API from Modrinth if it is missing
#   DRY_RUN=1             say what would happen and change nothing
set -euo pipefail

REPO="Mishaadevv/minecraft-mcp"
JAR="minecraft-mcp.jar"
ASSET="https://github.com/${REPO}/releases/latest/download/${JAR}"
PORT="${MCP_PORT:-25585}"
DRY_RUN="${DRY_RUN:-0}"
WITH_FABRIC_API="${WITH_FABRIC_API:-0}"

say()  { printf '  %s\n' "$*"; }
head_() { printf '\n%s\n' "$*"; }
die()  { printf '\nMinecraft MCP: %s\n' "$*" >&2; exit 1; }

# --- where is the game? ------------------------------------------------------
if [ -n "${MC_DIR:-}" ]; then
    MODS="${MC_DIR}/mods"
else
    case "$(uname -s)" in
        Darwin) MODS="${HOME}/Library/Application Support/minecraft/mods" ;;
        MINGW*|MSYS*|CYGWIN*) MODS="${APPDATA:-${HOME}/AppData/Roaming}/.minecraft/mods" ;;
        *) MODS="${HOME}/.minecraft/mods" ;;
    esac
fi

head_ "Minecraft MCP"
say "mods folder: ${MODS}"
say "MCP endpoint: http://127.0.0.1:${PORT}/mcp"

# --- a downloader ------------------------------------------------------------
if command -v curl >/dev/null 2>&1; then
    fetch() { curl -fsSL "$1" -o "$2"; }
elif command -v wget >/dev/null 2>&1; then
    fetch() { wget -qO "$2" "$1"; }
else
    die "neither curl nor wget is available - install one of them and try again."
fi

# --- the mod -----------------------------------------------------------------
if [ "${DRY_RUN}" = "1" ]; then
    say "would download ${ASSET}"
    say "would write  ${MODS}/${JAR}"
else
    [ -d "${MODS}" ] || mkdir -p "${MODS}"
    TMP="$(mktemp)"
    fetch "${ASSET}" "${TMP}" || die "could not download the jar from ${ASSET}"
    # A jar is a zip: refusing a stray error page here is worth the two bytes.
    if [ "$(head -c 2 "${TMP}")" != "PK" ]; then
        rm -f "${TMP}"
        die "what came back was not a jar - check the release exists at https://github.com/${REPO}/releases"
    fi
    mv "${TMP}" "${MODS}/${JAR}"
    say "installed ${MODS}/${JAR}"
fi

# --- Fabric API --------------------------------------------------------------
if ls "${MODS}"/fabric-api-*.jar >/dev/null 2>&1; then
    say "Fabric API: found"
elif [ "${WITH_FABRIC_API}" = "1" ] && [ "${DRY_RUN}" != "1" ]; then
    say "Fabric API: missing, fetching the 1.21.11 build from Modrinth"
    API_JSON="$(mktemp)"
    fetch 'https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D' "${API_JSON}" \
        || die "could not reach Modrinth"
    API_URL="$(python - "${API_JSON}" <<'PY' 2>/dev/null || true
import json, sys
versions = json.load(open(sys.argv[1]))
print(versions[0]["files"][0]["url"] if versions else "")
PY
)"
    rm -f "${API_JSON}"
    [ -n "${API_URL}" ] || die "no Fabric API build for 1.21.11 was found - install it by hand from https://modrinth.com/mod/fabric-api"
    fetch "${API_URL}" "${MODS}/$(basename "${API_URL}")"
    say "installed $(basename "${API_URL}")"
else
    say "Fabric API: NOT installed - the mod will not load without it."
    say "             https://modrinth.com/mod/fabric-api  (or rerun with WITH_FABRIC_API=1)"
fi

# --- the agent side ----------------------------------------------------------
if [ "${DRY_RUN}" != "1" ]; then
    HELPER="${HOME}/minecraft-mcp"
    mkdir -p "${HELPER}"
    if fetch "https://raw.githubusercontent.com/${REPO}/main/tools/configure.py" "${HELPER}/configure.py"; then
        say "helper:     ${HELPER}/configure.py"
    fi
fi

head_ "Next"
say "1. Start Minecraft with Fabric Loader 1.21.11, open a world, and leave it running."
say "2. Point your agent at http://127.0.0.1:${PORT}/mcp - one command per agent:"
say ""
say "     claude mcp add --transport http minecraft http://127.0.0.1:${PORT}/mcp"
say "     codex mcp add minecraft --url http://127.0.0.1:${PORT}/mcp"
say ""
say "   or let the helper do it:  python ${HOME}/minecraft-mcp/configure.py opencode"
say "   (agents it knows: claude, codex, opencode, cursor, vscode, freebuff, generic)"
say ""
say "3. Check it is alive:  curl http://127.0.0.1:${PORT}/health"
printf '\n'
