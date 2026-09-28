<div align="center">

# Minecraft MCP

**Let an AI agent actually play your Minecraft.** Not a script that replays keys - a Model Context Protocol
server running *inside* the live Fabric client: walking, mining, building, fighting, crafting and reading chat,
all through the game's own code, on your save, on your machine.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62b47a.svg)](https://fabricmc.net)

</div>

---

## What it does

Point any MCP client at the endpoint and the agent can:

- **See the world**: `mc_state` returns position, look, health, food and game mode; the held item, the hotbar
  and the whole inventory; what the crosshair is on; every living thing nearby with its distance and health;
  the last chat lines; the buttons of any open window; the slots of any open container; and how many
  achievements are unlocked, **per mod**, so it can tell vanilla progress from yours.
- **Find something to hit**: `mc_scan` sweeps the crosshair around the horizon and reports which blocks are
  actually in view, nearest first, each with the angle to look at it - so the agent mines what is there
  instead of what it imagines is there.
- **Move like a player**: walking is real input, not teleportation. The mod presses the keys the game reads, so
  movement goes through physics, collision, hunger, sprinting, sneaking and jumping, and the server sees
  ordinary movement.
- **Work**: hold left click until a block breaks, place blocks, right click chests, furnaces, doors and
  villagers, eat, drink, throw, shoot, drop the held stack.
- **Craft and sort**: `mc_slot` clicks the slots of whatever container is open, which is how items get moved,
  the 2x2 and 3x3 grids get filled, and a chest gets emptied.
- **Fight**: hold left click on whatever is in the crosshair, or let the agent square up to the nearest hostile
  by itself.
- **Talk and listen**: `mc_chat` says things and runs commands, `mc_read_chat` reads chat, command results,
  death messages and unlock announcements back.
- **Die and come back**: `respawn` gets up again, because dying is part of the game.
- **Drive any window**: `mc_state` describes the buttons and fields of whatever screen is open, and `click`
  presses them - including the achievements window, whoever draws it.
- **Hand back pictures**: `mc_screenshot` saves what the game is showing.

**10 tools.** See [Tools](#tools).

## Install

Requires **Minecraft 1.21.11** with **Fabric Loader** and **Fabric API**. The installer tells you if either is
missing, and `WITH_FABRIC_API=1` fetches the API for you.

**Linux, macOS, Git Bash on Windows:**

```bash
curl -fsSL https://raw.githubusercontent.com/Mishaadevv/minecraft-mcp/main/install.sh | bash
```

**Windows, PowerShell:**

```powershell
irm https://raw.githubusercontent.com/Mishaadevv/minecraft-mcp/main/install.ps1 | iex
```

The mod is one jar with no dependency but Fabric API. It can also be built from source (`./gradlew build`) or
installed by hand: drop it in `mods/`. On Windows, `build-mod.bat` builds it and offers to install it.

## Point your client at it

The whole configuration is one URL:

```
http://127.0.0.1:25585/mcp
```

`python tools/configure.py <client>` writes it into the right file for you, merging instead of overwriting and
backing up what was there as `<file>.bak`. Or do it by hand:

<details open>
<summary><b>Claude Code</b></summary>

```bash
claude mcp add --transport http minecraft http://127.0.0.1:25585/mcp
```
</details>

<details>
<summary><b>Codex CLI</b> — <code>~/.codex/config.toml</code></summary>

```bash
codex mcp add minecraft --url http://127.0.0.1:25585/mcp
```

or:

```toml
[mcp_servers.minecraft]
url = "http://127.0.0.1:25585/mcp"
# A batch of steps is answered when the last one finishes, so give it room.
tool_timeout_sec = 120
```
</details>

<details>
<summary><b>opencode</b> — <code>~/.config/opencode/opencode.json</code> (or <code>./opencode.json</code>)</summary>

```bash
python tools/configure.py opencode          # global
python tools/configure.py opencode --project
```

```json
{
  "mcp": {
    "minecraft": { "type": "remote", "url": "http://127.0.0.1:25585/mcp", "enabled": true }
  }
}
```
</details>

<details>
<summary><b>Cursor</b> — <code>~/.cursor/mcp.json</code></summary>

```json
{
  "mcpServers": {
    "minecraft": { "url": "http://127.0.0.1:25585/mcp" }
  }
}
```
</details>

<details>
<summary><b>VS Code / Copilot</b> — <code>.vscode/mcp.json</code></summary>

```json
{
  "servers": {
    "minecraft": { "type": "http", "url": "http://127.0.0.1:25585/mcp" }
  }
}
```
</details>

<details>
<summary><b>Freebuff</b></summary>

Add an MCP server of type **HTTP** in its settings and give it
`http://127.0.0.1:25585/mcp`. If it wants JSON, the block under *Cursor* works unchanged.
</details>

<details>
<summary><b>Anything else</b></summary>

It is plain MCP over HTTP: `POST /mcp` with JSON-RPC 2.0, `initialize`, `tools/list`, `tools/call`. There is no
session to keep and no handshake to complete twice - the game is the state.

```bash
python tools/configure.py generic     # every shape in the wild, printed at once
python tools/mcp_client.py probe      # handshake, tool list, then mc_state, for testing
```
</details>

Two things worth knowing: the server only answers while the game is **in a world**, and it binds to
`127.0.0.1`, so nothing outside your machine can reach it.

> Keep one `mc_act` batch to a couple of hundred ticks (about ten seconds). Some clients give up after a minute.

## Tools

| Tool | What it does |
| --- | --- |
| `mc_state` | everything the player can see: position, health, inventory, crosshair, entities, chat, open window, open container, achievements per mod |
| `mc_scan` | which blocks are in view, nearest first, with the angle and distance to each |
| `mc_act` | a batch of steps, in order, one game tick at a time, answered with what each one did plus a fresh state |
| `mc_chat` | say something, or run a command when the text starts with a slash |
| `mc_read_chat` | the last lines the game printed |
| `mc_slot` | click a slot of the open container: moving items, crafting, chests, furnaces, trades |
| `mc_menu` | open the player's inventory, or close whatever is open |
| `mc_achievements` | open or close the achievements window and report the save's progress |
| `mc_screenshot` | save a screenshot of the game window |
| `mc_mods` | which mods this client is running, with their names and versions |

The steps inside `mc_act`:

| Step | |
| --- | --- |
| `{"walk":{"ticks":20,"forward":true,"sprint":true,"jump":false,"sneak":false,"left":false,"right":false}}` | hold keys |
| `{"look":{"yaw":90,"pitch":0}}` / `{"look_at":{"x":1,"y":64,"z":1}}` | turn the head |
| `{"mine":{"ticks":80}}` | hold left click on the block in the crosshair until it breaks |
| `{"attack":{"ticks":20}}` | hold left click, on the crosshair or the nearest hostile |
| `{"place":{}}` / `{"use":{}}` | right click the target, or with no target |
| `{"select":{"slot":0}}` | pick a hotbar slot |
| `{"slot":{"index":36,"button":0,"mode":"pickup"}}` | click a container slot (`pickup`, `quick_move`, `swap`, `clone`, `throw`, `quick_craft`, `pickup_all`) |
| `{"menu":{"open":"inventory"}}` | open the inventory, or `"close"` |
| `{"drop":{"all":true}}` | throw the held stack |
| `{"chat":{"text":"/time set day"}}` | speak, or run a command |
| `{"respawn":{}}` | get up again |
| `{"click":{"label":"Respawn"}}` | press a button in the open window, by label, id or coordinates |
| `{"window":{"open":true}}` | open or close the achievements window |
| `{"scan":{"step":30}}` | look around and report the blocks in view |
| `{"screenshot":{}}` | save a screenshot |
| `{"wait":{"ticks":20}}` | let the world catch up |

## How it works

| Path | What lives there |
| --- | --- |
| `MinecraftMcpClient.java` | the entrypoint: reads the config, starts the server, listens to the tick and to chat |
| `agent/AgentBridge.java` | the queue and the doors: `/mcp`, `/state`, `/act`, `/health` |
| `agent/AgentActions.java` | the verbs, as steps that run one tick at a time |
| `agent/AgentState.java` | the snapshot: everything the agent can perceive |
| `agent/AgentInput.java` | the keys the agent holds |
| `mcp/McpServer.java` | JSON-RPC 2.0 over HTTP: `initialize`, `tools/list`, `tools/call`, `ping` |
| `mcp/McpTools.java` | the tool catalogue and its dispatch |
| `mixin/` | three small patches: read the agent's keys, hand the player its movement, expose the progress map |
| `tools/configure.py` | writes the MCP config for whichever agent you use |
| `tools/mcp_client.py` | a command line MCP client, for testing the server on its own |

Every request is queued and drained **one step per tick on the game thread**, so nothing the agent asks for can
stall a frame, and every answer comes back with a fresh snapshot of the world. Requests that arrive while the
game is loading are held, not dropped, and a step that takes too long is cut off with an explanation rather
than a hang.

The same thing without the protocol is `GET /health`, `GET /state` and `POST /act` on the same port, which is
handy with `curl`.

## Playing together with other mods

Nothing is wired between this and other mods, which is the point of it being a protocol: install it next to
whatever else you play with and it drives the game itself.

An example worth knowing: with **[Reworked Achievements](https://github.com/Mishaadevv)** installed,
`mc_achievements` opens the *vanilla* achievements screen and that mod swaps it for its own window, exactly as
it does when a player presses the key. The agent reads that window's buttons through `mc_state`, presses them
with `click`, and watches its progress in `achievements.byNamespace`.

## Build from source

```bash
./gradlew build              # jar lands in build/libs
./gradlew runClient -Pmcp    # development client with the server on 25585
```

Java 21 for the game; the Gradle daemon needs Java 25 (Fabric Loom 1.18). On Windows, `run-client.bat` and
`build-mod.bat` find a JDK 25 by themselves, and `run-client.bat SmokeWorld` jumps straight into a world.

## Safety

The server listens on the loopback address only, refuses every request while the game is not in a world, and is
switched off by setting `enabled` to `false` in `config/minecraft-mcp.json` (written on first launch, port
25585 by default). Anything that can reach that port can play as you, so treat it like a debug console: fine on
your own machine, not something to expose to a network.

MIT licensed.
