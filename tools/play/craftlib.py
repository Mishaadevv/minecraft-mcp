"""Crafting through the open container window.

The agent has one tool for the grid - clicking slots - so a recipe is: move each ingredient into the right
slot of the grid, click the result, put the finished item away. Everything here is that, in order.
"""

import time

import mcplay as m

# The player's own 2x2 window: 0 is the result, 1..4 the grid, 5..8 the armour, 9..35 the bag, 36..44 the
# hotbar, 45 the off hand. A crafting table puts its own 3x3 grid at 1..9 and pushes the rest along.
INVENTORY_GRID = [1, 2, 3, 4]
TABLE_GRID = [1, 2, 3, 4, 5, 6, 7, 8, 9]


def slots():
    return (m.state().get("menu") or {}).get("slots", [])


def find(item, own=None):
    """The first slot holding this item, empty string to mean 'any empty slot'."""
    for slot in slots():
        if item == "empty":
            if slot["item"] == "empty" and (own is None or slot["own"] == own):
                return slot["index"]
        elif slot["item"].startswith(item) and (own is None or slot["own"] == own):
            return slot["index"]
    return None


def count(item):
    total = 0
    for slot in slots():
        if slot["item"].startswith(item):
            total += slot["count"] if "count" in slot else 1
    return total


def _empties():
    return [s["index"] for s in slots() if s["item"] == "empty" and s["own"]]


def fill(placements):
    """placements: [(grid index, item id, how many)] - one item at a time, right clicked into place."""
    for index, item, how_many in placements:
        source = find(item)
        if source is None:
            raise RuntimeError("no %s in the inventory to put in the grid" % item)
        m.slot(source)
        for _ in range(how_many):
            m.slot(index, button=1)
        m.slot(source)


def take(result_index=0, count=1):
    """Click the result and stash whatever comes out; returns how much was crafted."""
    made = 0
    for _ in range(count):
        before = slots()[result_index]["item"]
        if before == "empty":
            return made
        m.slot(result_index)
        m.wait(ticks=2)
        empty = _empties()
        if not empty:
            raise RuntimeError("no room to put the crafted item")
        m.slot(empty[0])
        made += 1
    return made


def empty_grid(grid):
    for index in grid:
        if slots()[index]["item"] != "empty":
            m.slot(index)
            empty = _empties()
            if empty:
                m.slot(empty[0])


def craft(placements, grid=INVENTORY_GRID, times=1, result_index=0):
    """One recipe, repeated. The grid is left clean, so the next recipe starts from nothing."""
    empty_grid(grid)
    made = 0
    for _ in range(times):
        if not all(find(item) is not None for _, item, _ in placements):
            break
        fill(placements)
        m.wait(ticks=2)
        got = take(result_index)
        made += got
        if got == 0:
            break
        empty_grid(grid)
    return made


def open_inventory():
    m.menu("inventory")
    m.wait(ticks=3)


def close():
    m.menu("close")
