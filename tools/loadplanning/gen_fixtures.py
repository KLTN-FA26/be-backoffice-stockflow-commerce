"""Write the parity fixtures (src/test/resources/loadplanning/parity/*.fixture.json); see parity.py."""
import json, random
C20 = dict(code='20DC', w=2352, h=2393, d=5898)
C40 = dict(code='40DC', w=2352, h=2393, d=12032)
C40HC = dict(code='40HC', w=2352, h=2698, d=12032)
def cont(c, q=64, gusset=0): return dict(c, quantity=q, payload=1e9, gusset=gusset)
def box(part, w, h, d, q, rot=True): return dict(part=part, w=w, h=h, d=d, quantity=q, rotatable=rot, weight=1.0)
def rnd_boxes(seed, n_lines, pool, prefix='L', rot_share=0.8):
    r = random.Random(seed); out = []
    for i in range(n_lines):
        w, h, d = r.choice(pool)
        out.append(box('%s%d' % (prefix, i), w, h, d, r.randint(2, 12), r.random() < rot_share))
    return out
POOL = [(600,400,400),(800,600,500),(1200,800,1000),(500,500,500),(1000,600,700),(300,300,300),(1500,1000,800),(700,500,450),(400,300,250)]
fx = {
 'bulk_20dc_first': dict(containers=[cont(C20)], boxes=rnd_boxes(1, 8, POOL), kits=[], distribute='first', budget=4),
 'bulk_40hc_gusset': dict(containers=[cont(C40HC, gusset=150)], boxes=rnd_boxes(2, 14, POOL), kits=[], distribute='first', budget=6),
 'kit_bulk_even': dict(containers=[cont(C40)], boxes=[box('T-MB',1590,90,1590,6), box('T-CB',730,800,730,12)] + rnd_boxes(3, 6, POOL),
                        kits=[dict(name='Table', members=[['T-MB',1],['T-CB',2]])], distribute='even', budget=6),
 'two_kits_auto': dict(containers=[cont(C40, gusset=120)], boxes=[box('A1',1200,500,800,10), box('A2',600,400,400,10), box('B1',1000,700,600,8,False), box('B2',500,300,500,16)] + rnd_boxes(4, 5, POOL),
                        kits=[dict(name='A', members=[['A1',1],['A2',1]]), dict(name='B', members=[['B1',1],['B2',2]])], distribute='auto', budget=8),
 'non_rotatable_20dc': dict(containers=[cont(C20)], boxes=rnd_boxes(5, 10, POOL, rot_share=0.0), kits=[], distribute='first', budget=4),
 'many_lines_40hc': dict(containers=[cont(C40HC, gusset=150)], boxes=rnd_boxes(6, 30, POOL), kits=[], distribute='auto', budget=10),
}
for name, f in fx.items():
    f['ticks_per_second'] = 8.0
    json.dump(f, open('%s.fixture.json' % name, 'w'), indent=1)
print(list(fx))
more = {
 'kits_even_three_20dc': dict(containers=[cont(C20)], boxes=[box('P-A',1100,700,900,30), box('P-B',800,500,600,30), box('Q-1',600,600,600,12), box('Q-2',400,300,500,24)] + rnd_boxes(7, 4, POOL),
                        kits=[dict(name='P', members=[['P-A',1],['P-B',1]]), dict(name='Q', members=[['Q-1',1],['Q-2',2]])], distribute='even', budget=8),
 'bulk_large_40dc': dict(containers=[cont(C40, gusset=150)], boxes=rnd_boxes(8, 40, POOL), kits=[], distribute='first', budget=10),
}
for name, f in more.items():
    f['ticks_per_second'] = 8.0
    json.dump(f, open('%s.fixture.json' % name, 'w'), indent=1)
print(list(more))
