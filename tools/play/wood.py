import mcplay as m

TREE = ['minecraft:oak_log', 'minecraft:oak_leaves', 'minecraft:birch_log', 'minecraft:birch_leaves']


def chop(wanted=8, reach=96, verbose=True):
    """Walk to trees and punch out their trunks until `wanted` logs are in the inventory."""
    start = sum(v for k, v in m.inventory().items() if 'log' in k)
    saw = 0
    for _ in range(wanted * 4):
        if sum(v for k, v in m.inventory().items() if 'log' in k) - start >= wanted:
            break
        found = m.find(TREE, step=10, pitches=[25, 15, 5, -5, -15, -25], reach=reach)
        if found is None:
            if verbose:
                print('   no tree in view', flush=True)
            return
        m.approach(found['pos'][0], found['pos'][2], stop=3.0)
        trunk = m.find(['minecraft:oak_log', 'minecraft:birch_log'], step=10,
                       pitches=[45, 30, 15, 0, -20], reach=5)
        if trunk is None:
            saw += 1
            if saw > 6:
                return
            continue
        saw = 0
        m.look(look_at=[trunk['pos'][0] + 0.5, trunk['pos'][1] + 0.5, trunk['pos'][2] + 0.5])
        answer = m.mine(ticks=120)
        step = (answer.get('steps') or [{}])[0]
        if verbose:
            logs = sum(v for k, v in m.inventory().items() if 'log' in k)
            print('   %-40s logs=%d' % (step.get('outcome') or step, logs), flush=True)
        m.walk(ticks=6, sprint=False)


def fell(wanted=8, reach=6, tries=80):
    """Break the leaves that hide a trunk, then the trunk itself, until `wanted` logs are in hand."""
    logs = 0
    for _ in range(tries):
        if sum(v for k, v in m.inventory().items() if 'log' in k) >= wanted:
            break
        trunk = m.find(['minecraft:oak_log', 'minecraft:birch_log', 'minecraft:jungle_log',
                        'minecraft:spruce_log', 'minecraft:dark_oak_log'],
                       step=10, pitches=[30, 15, 0, -15, -30], reach=reach)
        if trunk:
            m.look(look_at=[trunk['pos'][0] + 0.5, trunk['pos'][1] + 0.5, trunk['pos'][2] + 0.5])
            step = (m.mine(ticks=120).get('steps') or [{}])[0]
            if step.get('broken'):
                logs += 1
                print('   log %d at %s' % (logs, trunk['pos']), flush=True)
            else:
                print('   trunk attempt: %s' % step.get('outcome'), flush=True)
            continue
        leaf = m.find(['minecraft:oak_leaves', 'minecraft:birch_leaves', 'minecraft:jungle_leaves',
                       'minecraft:spruce_leaves', 'minecraft:dark_oak_leaves'],
                      step=20, pitches=[20, 0, -25, -45], reach=reach)
        if leaf is None:
            print('   nothing woody left in reach', flush=True)
            break
        m.look(look_at=[leaf['pos'][0] + 0.5, leaf['pos'][1] + 0.5, leaf['pos'][2] + 0.5])
        m.mine(ticks=70)
    return logs
