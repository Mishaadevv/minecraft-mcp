import collections
import mcplay as m

WOOD_WORDS = ('log', 'leaves', 'planks', 'stripped')


def wood_in_view(reach=96, step=10):
    blocks = m.scan(step=step, pitches=[25, 15, 5, -5, -15], reach=reach).get('blocks', [])
    return [b for b in blocks if any(w in b['block'] for w in WOOD_WORDS)], collections.Counter(
        b['block'] for b in blocks)


def wander(legs=10, leg=60):
    """Walk in a slowly turning circle, looking for wood, and stop as soon as some is in view."""
    for i in range(legs):
        yaw = (i * 45) % 360
        found, kinds = wood_in_view()
        print('  leg %d yaw %3d at %s: wood=%d  %s' % (
            i, yaw, [round(v) for v in m.here()], len(found), dict(kinds.most_common(5))), flush=True)
        if found:
            print('  nearest wood:', found[0])
            return found[0]
        m.look(yaw=yaw, pitch=0)
        m.walk(ticks=int(leg / 0.28), sprint=True)
    return None
