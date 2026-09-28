"""Small driver for playing Minecraft through the mod's MCP endpoint.

Everything goes through POST /mcp with the tools the mod already exposes, so this file is only convenience:
turn a "step" into a call, keep the last state, and read the achievement counter.
"""

import json
import time
import urllib.request

URL = "http://127.0.0.1:25585/mcp"
_id = [0]


def call(name, arguments=None):
    _id[0] += 1
    body = {
        "jsonrpc": "2.0",
        "id": _id[0],
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    request = urllib.request.Request(
        URL,
        data=json.dumps(body).encode(),
        headers={"content-type": "application/json"},
        method="POST",
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=300) as answer:
                payload = json.load(answer)
            break
        except Exception as problem:  # the bridge is single threaded: a slow step can look like a failure
            if attempt == 2:
                raise
            print("   (retry after", problem, ")")
            time.sleep(2)
    text = payload["result"]["content"][0]["text"]
    try:
        return json.loads(text)
    except ValueError:
        return {"text": text}


def state(**kwargs):
    return call("mc_state", kwargs)


def act(*steps):
    flat = []
    for step in steps:
        flat.extend(step if isinstance(step, list) else [step])
    return call("mc_act", {"steps": flat})


def achievements():
    return call("mc_achievements", {"open": False})["achievements"]


def score():
    return achievements()["unlocked"]


def walk(ticks=20, forward=True, sprint=True, jump=False):
    return act({"walk": {"ticks": ticks, "forward": forward, "sprint": sprint, "jump": jump}})


def look(yaw=None, pitch=None, look_at=None):
    if look_at is not None:
        step = {"look_at": {"x": look_at[0], "y": look_at[1], "z": look_at[2]}}
    else:
        step = {"look": {k: v for k, v in (("yaw", yaw), ("pitch", pitch)) if v is not None}}
    return act(step)


def wait(ticks=20):
    return act({"wait": {"ticks": ticks}})


def mine(ticks=80):
    return act({"mine": {"ticks": ticks}})


def attack(ticks=20):
    return act({"attack": {"ticks": ticks}})


def slot(index, button=0, mode="pickup"):
    return act({"slot": {"index": index, "button": button, "mode": mode}})


def menu(open="inventory"):
    return act({"menu": {"open": open}})


def scan(**kwargs):
    answer = call("mc_scan", kwargs)
    steps = answer.get("steps") or []
    return steps[0] if steps else {}


def find(block, **kwargs):
    """Nearest block of that id (or of any id in a list) in view, or None."""
    wanted = [block] if isinstance(block, str) else list(block)
    found = [b for b in scan(**kwargs).get("blocks", []) if b["block"] in wanted]
    return found[0] if found else None


def select(slot):
    return act({"select": {"slot": slot}})


def place():
    return act({"place": {}})


def use():
    return act({"use": {}})


def chat(text):
    return call("mc_chat", {"text": text})


def inventory():
    """Item name -> total count, from a fresh state."""
    counts = {}
    for item in state().get("inventory", []):
        counts[item["item"]] = counts.get(item["item"], 0) + item["count"]
    return counts


def ascension():
    """How many achievements of the achievements mod are done - the number this session is judged on."""
    return achievements().get("byNamespace", {}).get("ascension", 0)


def here():
    return state()["pos"]


def approach(x, z, stop=3.0, tries=14):
    """Sprint towards a horizontal spot until the player stands within `stop` blocks of it."""
    for _ in range(tries):
        position = here()
        distance = ((x - position[0]) ** 2 + (z - position[2]) ** 2) ** 0.5
        if distance <= stop:
            return True
        look(look_at=[x, position[1], z])
        walk(ticks=int(max(5, min(60, distance * 3.8))), sprint=True)
        if here()[:1] + here()[2:] == position[:1] + position[2:]:
            # Nothing moved: a block in the way, or a dip to climb out of. Jumping is how a player fixes both.
            walk(ticks=25, forward=True, sprint=False, jump=True)
    return False


def reach_block(block, stop=3.5, swings=8, reach=24):
    """Finds the nearest block of that id, walks to it and keeps breaking it until it is gone."""
    for _ in range(swings):
        found = find(block, step=15, pitches=[30, 15, 0, -20, -40], reach=reach)
        if found is None:
            return False
        approach(found["pos"][0], found["pos"][2], stop=stop)
        found = find(block, step=15, pitches=[30, 15, 0, -20, -40], reach=reach)
        if found is None:
            return True
        look(look_at=[found["pos"][0] + 0.5, found["pos"][1] + 0.5, found["pos"][2] + 0.5])
        answer = mine(ticks=90)
        outcome = (answer.get("steps") or [{}])[0].get("outcome", "")
        if not outcome.startswith("broke"):
            continue
        walk(ticks=8, sprint=False)
    return False


def report(label, before=None, after=None):
    now = achievements()
    line = "%-34s unlocked=%d known=%d %s" % (label, now["unlocked"], now["known"], now.get("byNamespace", {}))
    if before is not None:
        line += "   (+%d)" % (now["unlocked"] - before)
    print(line, flush=True)
    return now["unlocked"]


def pickup(radius=14, rounds=6):
    """Walk over the dropped items nearby - logs, saplings, mob loot - until none are left."""
    for _ in range(rounds):
        drops = [e for e in state().get("entities", [])
                 if e["type"] == "minecraft:item" and e["distance"] <= radius]
        if not drops:
            return True
        for drop in drops[:8]:
            approach(drop["pos"][0], drop["pos"][2], stop=1.2, tries=5)
        wait(ticks=10)
    return False
