#!/usr/bin/env python3
"""Point an AI agent at Minecraft MCP.

The mod serves MCP over HTTP at http://127.0.0.1:25585/mcp, so every agent needs the same one thing: that URL.
What differs is only where each of them keeps its configuration, which is what this writes for you.

    python configure.py list                    # who it knows about
    python configure.py claude                  # prints the command to paste
    python configure.py codex                   # merges ~/.codex/config.toml
    python configure.py opencode                # merges ~/.config/opencode/opencode.json
    python configure.py cursor                  # merges ~/.cursor/mcp.json
    python configure.py vscode                  # merges .vscode/mcp.json in this folder
    python configure.py freebuff                # what to paste into its MCP settings
    python configure.py generic                 # every shape at once, for anything else

Options:

    --port 25585        the port the mod is listening on
    --name minecraft    what to call the server in the config
    --write             for claude, actually run the `claude mcp add` command instead of printing it
    --dry-run           show what would change, write nothing

Only the Python standard library is used, and an existing config is never thrown away: it is merged and backed
up next to the original as <file>.bak.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

AGENTS = ("claude", "codex", "opencode", "cursor", "vscode", "freebuff", "generic")


def url(port: int) -> str:
    return f"http://127.0.0.1:{port}/mcp"


def home(*parts: str) -> Path:
    return Path(os.path.expanduser("~")).joinpath(*parts)


def merge_json(path: Path, update: dict, dry_run: bool) -> None:
    """Reads a JSON config, folds in the update, and writes it back with a backup."""
    if path.exists():
        try:
            current = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            print(f"  {path} is not valid JSON - leaving it alone; add the block by hand")
            print(json.dumps(update, indent=2))
            return
        shutil.copy2(path, path.with_suffix(path.suffix + ".bak"))
    else:
        current = {}

    deep_update(current, update)
    if dry_run:
        print(f"  would write {path}")
        print(json.dumps(update, indent=2))
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(current, indent=2) + "\n", encoding="utf-8")
    print(f"  wrote {path}")


def deep_update(target: dict, update: dict) -> None:
    for key, value in update.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            deep_update(target[key], value)
        else:
            target[key] = value


def configure_claude(port: int, name: str, write: bool) -> None:
    command = ["claude", "mcp", "add", "--transport", "http", name, url(port)]
    printable = " ".join(command)
    if not write:
        block = {"mcpServers": {name: {"type": "http", "url": url(port)}}}
        print("  run this:")
        print(f"    {printable}")
        print("  or put this into ~/.claude.json:")
        print("\n".join("    " + line for line in json.dumps(block, indent=2).splitlines()))
        return

    if shutil.which("claude") is None:
        print("  the claude command is not on PATH - is Claude Code installed?")
        print(f"    {printable}")
        return
    print(f"  running: {printable}")
    result = subprocess.run(command, capture_output=True, text=True)
    print(f"  {result.stdout.strip() or result.stderr.strip()}")


def configure_codex(port: int, name: str, dry_run: bool) -> None:
    path = home(".codex", "config.toml")
    block = f'[mcp_servers.{name}]\nurl = "{url(port)}"\n# Minecraft MCP answers a whole batch of steps at once,\n# so give a long action more room than the 60 s default.\ntool_timeout_sec = 120\n'

    if path.exists():
        text = path.read_text(encoding="utf-8")
        header = f"[mcp_servers.{name}]"
        if header in text:
            lines = text.splitlines()
            start = next(i for i, line in enumerate(lines) if line.strip() == header)
            end = start + 1
            while end < len(lines) and not lines[end].startswith("["):
                end += 1
            text = "\n".join(lines[:start] + block.splitlines() + lines[end:]) + "\n"
        else:
            text = text.rstrip("\n") + "\n\n" + block
    else:
        text = block

    if dry_run:
        print(f"  would write {path}")
        print(block)
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        shutil.copy2(path, path.with_suffix(".toml.bak"))
    path.write_text(text, encoding="utf-8")
    print(f"  wrote {path}")
    print(f"  (codex mcp add {name} --url {url(port)} does the same thing)")


def configure_opencode(port: int, name: str, dry_run: bool, project: bool) -> None:
    path = Path("opencode.json") if project else home(".config", "opencode", "opencode.json")
    if not project and (jsonc := path.with_suffix(".jsonc")).exists() and not path.exists():
        print(f"  found {jsonc} instead - add this under its \"mcp\" key:")
        print(json.dumps({name: {"type": "remote", "url": url(port), "enabled": True}}, indent=2))
        return
    merge_json(path, {"mcp": {name: {"type": "remote", "url": url(port), "enabled": True}}}, dry_run)


def configure_cursor(port: int, name: str, dry_run: bool) -> None:
    merge_json(home(".cursor", "mcp.json"), {"mcpServers": {name: {"url": url(port)}}}, dry_run)


def configure_vscode(port: int, name: str, dry_run: bool) -> None:
    merge_json(Path(".vscode", "mcp.json"), {"servers": {name: {"type": "http", "url": url(port)}}}, dry_run)


def configure_freebuff(port: int, name: str) -> None:
    print("  Freebuff takes MCP servers from its settings; add one of type HTTP with this URL:")
    print(f"    {url(port)}")
    print("  If it asks for a JSON block, use the generic one below.")


def configure_generic(port: int, name: str) -> None:
    print("  MCP is a protocol, so the URL is all any client needs. The shapes in the wild:")
    print()
    print("  JSON with mcpServers:")
    print(json.dumps({"mcpServers": {name: {"type": "http", "url": url(port)}}}, indent=2))
    print()
    print("  JSON with a remote/url pair:")
    print(json.dumps({"mcp": {name: {"type": "remote", "url": url(port), "enabled": True}}}, indent=2))
    print()
    print("  TOML:")
    print(f'[mcp_servers.{name}]\nurl = "{url(port)}"')
    print()
    print("  Command line, for clients that have one:")
    print(f"    claude mcp add --transport http {name} {url(port)}")
    print(f"    codex mcp add {name} --url {url(port)}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Point an AI agent at Minecraft MCP.")
    parser.add_argument("agent", nargs="?", choices=AGENTS, help="who to configure")
    parser.add_argument("--port", type=int, default=int(os.environ.get("MCP_PORT", "25585")))
    parser.add_argument("--name", default="minecraft")
    parser.add_argument("--write", action="store_true", help="for claude: run the command, do not just print it")
    parser.add_argument("--project", action="store_true", help="for opencode: write ./opencode.json")
    parser.add_argument("--dry-run", action="store_true")
    options = parser.parse_args(argv)

    if not options.agent:
        parser.print_help()
        print(f"\nagents: {', '.join(AGENTS)}")
        return 0

    print(f"Minecraft MCP -> {url(options.port)}")
    if options.agent == "claude":
        configure_claude(options.port, options.name, options.write)
    elif options.agent == "codex":
        configure_codex(options.port, options.name, options.dry_run)
    elif options.agent == "opencode":
        configure_opencode(options.port, options.name, options.dry_run, options.project)
    elif options.agent == "cursor":
        configure_cursor(options.port, options.name, options.dry_run)
    elif options.agent == "vscode":
        configure_vscode(options.port, options.name, options.dry_run)
    elif options.agent == "freebuff":
        configure_freebuff(options.port, options.name)
    else:
        configure_generic(options.port, options.name)

    print("\n  Make sure the game is running with the mod, then check:"
          f" curl {url(options.port).rsplit('/mcp', 1)[0]}/health")
    return 0


if __name__ == "__main__":
    sys.exit(main())
