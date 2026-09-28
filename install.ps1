# Minecraft MCP - one command install on Windows.
#
#   irm https://raw.githubusercontent.com/Mishaadevv/minecraft-mcp/main/install.ps1 | iex
#
# Puts the mod where the game looks for it and tells you where to point your agent.
# Optional environment variables: MC_DIR, MCP_PORT, WITH_FABRIC_API=1, DRY_RUN=1

$ErrorActionPreference = "Stop"

$repo = "Mishaadevv/minecraft-mcp"
$asset = "https://github.com/$repo/releases/latest/download/minecraft-mcp.jar"
$port = if ($env:MCP_PORT) { $env:MCP_PORT } else { "25585" }
$dry = $env:DRY_RUN -eq "1"
$mcDir = if ($env:MC_DIR) { $env:MC_DIR } else { Join-Path $env:APPDATA ".minecraft" }
$mods = Join-Path $mcDir "mods"

function Say($text) { Write-Host "  $text" }
function Blank { Write-Host "" }

Blank
Write-Host "Minecraft MCP"
Say "mods folder: $mods"
Say "MCP endpoint: http://127.0.0.1:$port/mcp"

if ($dry) {
    Say "would download $asset"
    Say "would write  $mods\minecraft-mcp.jar"
} else {
    if (-not (Test-Path $mods)) { New-Item -ItemType Directory -Path $mods -Force | Out-Null }
    $target = Join-Path $mods "minecraft-mcp.jar"
    try {
        Invoke-WebRequest -Uri $asset -OutFile $target -UseBasicParsing
    } catch {
        Write-Host "  could not download the jar: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
    if ((Get-Item $target).Length -lt 1024) {
        Remove-Item $target -Force
        Write-Host "  what came back was not a jar - check the release exists at https://github.com/$repo/releases" -ForegroundColor Red
        exit 1
    }
    Say "installed $target"
}

$api = Get-ChildItem -Path $mods -Filter "fabric-api-*.jar" -ErrorAction SilentlyContinue
if ($api) {
    Say "Fabric API: found $($api[0].Name)"
} elseif ($env:WITH_FABRIC_API -eq "1" -and -not $dry) {
    Say "Fabric API: missing, fetching the 1.21.11 build from Modrinth"
    $versions = Invoke-RestMethod "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D"
    if (-not $versions) {
        Write-Host "  no Fabric API build for 1.21.11 was found - install it by hand" -ForegroundColor Yellow
    } else {
        $url = $versions[0].files[0].url
        $name = Split-Path $url -Leaf
        Invoke-WebRequest -Uri $url -OutFile (Join-Path $mods $name) -UseBasicParsing
        Say "installed $name"
    }
} else {
    Say "Fabric API: NOT installed - the mod will not load without it."
    Say "             https://modrinth.com/mod/fabric-api  (or rerun with `$env:WITH_FABRIC_API='1')"
}

if (-not $dry) {
    $helper = Join-Path $env:USERPROFILE "minecraft-mcp"
    New-Item -ItemType Directory -Path $helper -Force | Out-Null
    try {
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/$repo/main/tools/configure.py" `
            -OutFile (Join-Path $helper "configure.py") -UseBasicParsing
        Say "helper:     $helper\configure.py"
    } catch {
        Say "helper:     could not be downloaded (no matter, the commands below are enough)"
    }
}

Blank
Write-Host "Next"
Say "1. Start Minecraft with Fabric Loader 1.21.11, open a world, and leave it running."
Say "2. Point your agent at http://127.0.0.1:$port/mcp - one command per agent:"
Blank
Say "     claude mcp add --transport http minecraft http://127.0.0.1:$port/mcp"
Say "     codex mcp add minecraft --url http://127.0.0.1:$port/mcp"
Blank
Say "   or let the helper do it:  python $env:USERPROFILE\minecraft-mcp\configure.py opencode"
Say "   (agents it knows: claude, codex, opencode, cursor, vscode, freebuff, generic)"
Blank
Say "3. Check it is alive:  curl http://127.0.0.1:$port/health"
Blank
