#!/usr/bin/env python3
"""Talk to Minecraft MCP from the command line.

The mod serves the Model Context Protocol over HTTP, so this is a minimal client for it: handshake, list the
tools, call one. It uses nothing but the standard library, and it is mostly here so the server can be tested
without a full MCP host.

    python tools/mcp_client.py probe
    python tools/mcp_client.py list
    python tools/mcp_client.py state
    python tools/mcp_client.py act '{"steps":[{"walk":{"ticks":40,"sprint":true}}]}'
    python tools/mcp_client.py chat "time set day"

With the game started the usual way: `./gradlew runClient -Pmcp`.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_PORT = int(os.environ.get("MINECRAFT_MCP_PORT", "25585"))
PROTOCOL = "2025-06-18"


class Mcp:
    """A JSON-RPC 2.0 client for one MCP server, over streamable HTTP."""

    def __init__(self, port: int = DEFAULT_PORT, timeout: float = 200.0) -> None:
        self.url = f"http://127.0.0.1:{port}/mcp"
        self.timeout = timeout
        self.next_id = 1

    def send(self, method: str, params: dict | None = None, notify: bool = False) -> dict | None:
        message: dict = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        if not notify:
            message["id"] = self.next_id
            self.next_id += 1

        request = urllib.request.Request(
            self.url,
            data=json.dumps(message).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as answer:
                if answer.status == 202 or not answer.length:
                    return None
                return json.loads(answer.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"the server refused that ({error.code}): {error.read().decode('utf-8', 'replace')}")
        except urllib.error.URLError as error:
            raise RuntimeError(
                f"nothing is listening on 127.0.0.1:{self.url.rsplit(':', 1)[1].split('/')[0]} - is the game "
                f"running with the mod? ({error.reason})"
            )

    def handshake(self) -> dict:
        self.send("notifications/initialized", notify=True)  # ignored by the server, as it should be
        answer = self.send("initialize", {
            "protocolVersion": PROTOCOL,
            "capabilities": {},
            "clientInfo": {"name": "mcp_client.py", "version": "1.0.0"},
        })
        self.send("notifications/initialized", notify=True)
        return answer["result"] if answer else {}

    def tools(self) -> list[dict]:
        answer = self.send("tools/list", {})
        return answer["result"]["tools"] if answer else []

    def call(self, name: str, arguments: dict | None = None) -> str:
        answer = self.send("tools/call", {"name": name, "arguments": arguments or {}})
        if answer is None:
            return "the server sent no answer"
        if "error" in answer:
            return f"error {answer['error']['code']}: {answer['error']['message']}"
        content = answer["result"]["content"]
        return "\n".join(part.get("text", "") for part in content)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Talk to Minecraft MCP.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("probe", help="handshake, list the tools, then read the state")
    commands.add_parser("list", help="list the tools")
    commands.add_parser("state", help="what the player can see right now")

    call = commands.add_parser("call", help="call any tool with raw arguments")
    call.add_argument("tool")
    call.add_argument("--json", default="{}", help="the arguments object")

    act = commands.add_parser("act", help="run a list of steps, as JSON")
    act.add_argument("steps", help='for example \'[{"mine":{"ticks":80}}]\'')

    chat = commands.add_parser("chat", help="say something or run a command")
    chat.add_argument("text")

    options = parser.parse_args(argv)
    client = Mcp(options.port)

    try:
        if options.command == "probe":
            info = client.handshake()
            server = info.get("serverInfo", {})
            print(f"{server.get('name')} {server.get('version')} speaks {info.get('protocolVersion')}")
            for tool in client.tools():
                print(f"  - {tool['name']}: {tool['description'].splitlines()[0][:90]}")
            print()
            print(client.call("mc_state"))
            return 0

        client.handshake()

        if options.command == "list":
            for tool in client.tools():
                print(f"{tool['name']}: {tool['description'].splitlines()[0]}")
            return 0

        if options.command == "state":
            print(client.call("mc_state"))
            return 0

        if options.command == "call":
            print(client.call(options.tool, json.loads(options.json)))
            return 0

        if options.command == "act":
            steps = json.loads(options.steps)
            print(client.call("mc_act", {"steps": steps if isinstance(steps, list) else steps["steps"]}))
            return 0

        if options.command == "chat":
            print(client.call("mc_chat", {"text": options.text}))
            return 0
    except (RuntimeError, json.JSONDecodeError) as problem:
        print(problem, file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
