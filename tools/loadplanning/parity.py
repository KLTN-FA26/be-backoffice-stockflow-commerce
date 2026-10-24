"""Regenerate the expected results of KitSolverParityTest from the ORIGINAL Python kit solver.

The original budgets its search in time.time(); the Java port budgets it in greedy constructions (WorkClock). This
script patches a copy's clock to read constructions / rate - in memory, the files stay untouched - so both run the
same search and their placements can be compared exactly.

    NESTING_API=/path/to/a/copy/of/nesting-standalone/api python -B parity.py fixture.json expected.json

Run it on a COPY of the original's api/ directory and with -B, so nothing is written next to the original.
"""
import os, sys, json, types
sys.path.insert(0, os.environ['NESTING_API'])
from nesting.types import Box, Container
from nesting import solver_3d_kit as k

fx = json.load(open(sys.argv[1]))
ticks = [0]
rate = float(fx['ticks_per_second'])
fake = types.SimpleNamespace(time=lambda: ticks[0] / rate)
k.time = fake
orig = k._grasp_recreate
def counted(*a, **kw):
    ticks[0] += 1
    return orig(*a, **kw)
k._grasp_recreate = counted

containers = [Container(c['w'], c['h'], c['d'], quantity=c['quantity'], container_code=c['code'],
                        max_weight_kg=c.get('payload', 0.0), reserve_gussets=c.get('gusset', 0) > 0,
                        gusset_size_mm=c.get('gusset', 150.0) or 150.0) for c in fx['containers']]
boxes = [Box(b['w'], b['h'], b['d'], quantity=b['quantity'], part_name=b['part'], weight_kg=b.get('weight', 0.0),
             allow_rotation=b['rotatable']) for b in fx['boxes']]
kits = [k.KitSpec(x['name'], dict(x['members'])) for x in fx.get('kits', [])]
g = max((c.get('gusset', 0) for c in fx['containers']), default=0)
r = k.solve_3d_kit(containers, boxes, kits, budget_s=fx['budget'], distribute=fx['distribute'],
                   reserve_gussets=g > 0, gusset_size_mm=g or 150.0)
out = {
    'containers': [[[p.x_mm, p.y_mm, p.z_mm, p.width_mm, p.height_mm, p.depth_mm, p.part_name] for p in c.boxes]
                   for c in r.containers],
    'leftover': [[b.part_name, b.quantity] for b in r.unplaced],
    'utilization': r.utilization_percentage,
    'meta': {kk: r.meta.get(kk) for kk in ('sets_total', 'min_containers', 'kit_balance', 'distribute')},
    'ticks': ticks[0],
}
json.dump(out, open(sys.argv[2], 'w'))
print(sys.argv[1], 'containers', len(r.containers), 'util', round(r.utilization_percentage, 2), 'ticks', ticks[0])
