"""Run the lead's ORIGINAL Python solver (solve_3d_kit) on a benchmark dataset.

    NESTING_API=/path/to/COPY/of/nesting-standalone/api python3 -I -B run_python.py dataset.json BUDGET DISTRIBUTE

Run it on a COPY of the original's api/ directory (its nesting/__init__.py emptied, so openpyxl is not needed). The
original budgets in wall-clock seconds. One 40' high cube (12032 x 2352 x 2698 mm inner), corner gussets 150 mm,
no payload or door limit (the original has neither). Prints one JSON line: vehicles, fill per vehicle, unplaced.
"""
import json, os, sys, time
sys.path.insert(0, os.environ['NESTING_API'])
from nesting.types import Box, Container
from nesting import solver_3d_kit as k

data = json.load(open(sys.argv[1]))
budget = float(sys.argv[2])
dist = sys.argv[3] if len(sys.argv) > 3 else 'auto'
agg = {}
for c in data['cartons']:
    key = (c['part'], c['l'], c['w'], c['h'], c['rot'])
    agg[key] = agg.get(key, 0) + 1
boxes = [Box(width_mm=w, height_mm=h, depth_mm=l, quantity=q, part_name=p, allow_rotation=rot)
         for (p, l, w, h, rot), q in agg.items()]
cont = Container(2352, 2698, 12032, quantity=64, container_code='40HC', reserve_gussets=True, gusset_size_mm=150.0)
t0 = time.time()
r = k.solve_3d_kit(cont, boxes, [], budget_s=budget, distribute=dist, reserve_gussets=True, gusset_size_mm=150.0)
el = time.time() - t0
vol = 12032 * 2352 * 2698
fills = [round(100.0 * c.used_volume_mm3 / vol, 2) for c in r.containers]
print(json.dumps(dict(dataset=data['name'], budget=budget, distribute=dist, vehicles=len(r.containers), fills=fills,
                      avg_fill=round(sum(fills) / len(fills), 2) if fills else 0.0,
                      unplaced=sum(b.quantity for b in r.unplaced), seconds=round(el, 1),
                      pieces=sum(len(c.boxes) for c in r.containers))))
