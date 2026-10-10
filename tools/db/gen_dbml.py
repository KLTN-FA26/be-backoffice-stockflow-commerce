#!/usr/bin/env python3
"""
Generate the schema diagram (DBML for dbdiagram.io) from a real database, instead of drawing it.

The DBML in docs/business-design/db-design/schema.dbml is the OUTPUT of the migrations, never an input: drawn by
hand it drifted from the database within a week (QA notes, 27/9). Regenerate it after any migration:

    tools/db/gen_dbml.py <database> docs/business-design/db-design/schema.dbml

Use a database with every migration AND every db/pending contract applied, so the diagram shows
the target model rather than the expand-period mix of old and new tables. Tables, columns,
primary/unique keys, defaults, foreign keys (with ON DELETE) and one colour per module schema.
A "col IN ('A','B')" CHECK is drawn as a DBML Enum for readability; the database itself keeps
VARCHAR + CHECK because every entity maps enums @Enumerated(STRING). No notes, by request: the
diagram goes into the thesis as-is.
"""
import json, os, re, subprocess, sys

DB = sys.argv[1]
OUT = sys.argv[2]
CONTAINER = os.environ.get("PG_CONTAINER", "stockflow-postgres")
PG_USER = os.environ.get("PG_USER", "stockflow")

def q(sql):
    r = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", PG_USER, "-d", DB,
                        "-At", "-c", sql], capture_output=True, text=True, check=True)
    return r.stdout.strip()

SCHEMAS = ["product", "inventory", "warehouse", "procurement", "production", "ordering", "customer", "payment",
           "design", "fulfillment", "catalog", "chat", "notification", "reporting", "identity", "platform", "public"]
COLORS = {"product": "#2E86AB", "inventory": "#3E7C4A", "warehouse": "#8E6C3A", "procurement": "#7D5BA6", "production": "#E67E22",
          "ordering": "#C0392B", "customer": "#D68910", "payment": "#B8860B", "design": "#AA4A9E",
          "fulfillment": "#2C7873", "catalog": "#1B998B", "chat": "#4C93A8", "notification": "#A6763F",
          "reporting": "#607D8B", "identity": "#5A6ACF", "platform": "#616161", "public": "#9E9E9E"}

cols = json.loads(q("""
select json_agg(json_build_object('s',c.table_schema,'t',c.table_name,'c',c.column_name,'pos',c.ordinal_position,
  'type', format_type(a.atttypid, a.atttypmod), 'nn', c.is_nullable='NO', 'def', c.column_default) order by c.table_schema, c.table_name, c.ordinal_position)
from information_schema.columns c
join pg_attribute a on a.attrelid = (quote_ident(c.table_schema)||'.'||quote_ident(c.table_name))::regclass and a.attname = c.column_name
join information_schema.tables t on t.table_schema=c.table_schema and t.table_name=c.table_name and t.table_type='BASE TABLE'
where c.table_schema not in ('pg_catalog','information_schema')"""))

cons = json.loads(q("""
select json_agg(json_build_object('s',n.nspname,'t',r.relname,'name',c.conname,'type',c.contype,
  'cols',(select json_agg(a.attname order by k.ord) from unnest(c.conkey) with ordinality k(n,ord) join pg_attribute a on a.attrelid=c.conrelid and a.attnum=k.n),
  'fs', fn.nspname, 'ft', fr.relname,
  'fcols',(select json_agg(a.attname order by k.ord) from unnest(c.confkey) with ordinality k(n,ord) join pg_attribute a on a.attrelid=c.confrelid and a.attnum=k.n),
  'del', c.confdeltype, 'def', pg_get_constraintdef(c.oid)))
from pg_constraint c join pg_class r on r.oid=c.conrelid join pg_namespace n on n.oid=r.relnamespace
left join pg_class fr on fr.oid=c.confrelid left join pg_namespace fn on fn.oid=fr.relnamespace
where n.nspname not in ('pg_catalog','information_schema') and c.contype in ('p','u','f','c')"""))

# plain (non-partial, non-expression) indexes that are not constraints
idx = json.loads(q("""
select coalesce(json_agg(json_build_object('s',n.nspname,'t',t.relname,'name',i.relname,'unique',x.indisunique,
  'cols',(select json_agg(a.attname order by k.ord) from unnest(x.indkey) with ordinality k(n,ord) join pg_attribute a on a.attrelid=x.indrelid and a.attnum=k.n))),'[]')
from pg_index x join pg_class i on i.oid=x.indexrelid join pg_class t on t.oid=x.indrelid join pg_namespace n on n.oid=t.relnamespace
where n.nspname not in ('pg_catalog','information_schema') and x.indpred is null and 0 <> ALL (x.indkey::int2[])
  and not exists (select 1 from pg_constraint c where c.conindid = x.indexrelid)"""))

tables = {}
for c in cols:
    tables.setdefault((c["s"], c["t"]), []).append(c)

pk, uniq_single, uniq_multi, enums_by_col = {}, set(), {}, {}
fks = []
for c in cons:
    key = (c["s"], c["t"])
    if c["type"] == "p":
        pk[key] = c["cols"]
    elif c["type"] == "u":
        if len(c["cols"]) == 1:
            uniq_single.add((c["s"], c["t"], c["cols"][0]))
        else:
            uniq_multi.setdefault(key, []).append(c["cols"])
    elif c["type"] == "f":
        fks.append(c)
    elif c["type"] == "c":
        m = re.match(r"^CHECK \(\(\((\w+)\)::text = ANY \(\(ARRAY\[(.*)\]\)::text\[\]\)\)\)$", c["def"])
        if not m:
            m = re.match(r"^CHECK \(\(\((\w+) IS NULL\) OR \(\((\w+)\)::text = ANY \(\(ARRAY\[(.*)\]\)::text\[\]\)\)\)\)$", c["def"])
            if m:
                col, vals = m.group(2), m.group(3)
            else:
                continue
        else:
            col, vals = m.group(1), m.group(2)
        values = re.findall(r"'([^']*)'::character varying", vals)
        if values and all(re.match(r"^[A-Z][A-Z0-9_]*$", v) for v in values):
            enums_by_col[(c["s"], c["t"], col)] = values

for i in idx:
    key = (i["s"], i["t"])
    if i["unique"] and len(i["cols"]) == 1:
        uniq_single.add((i["s"], i["t"], i["cols"][0]))
    elif i["unique"]:
        uniq_multi.setdefault(key, []).append(i["cols"])

def dtype(t):
    t = t.replace("character varying", "varchar").replace("timestamp with time zone", "timestamptz") \
         .replace("timestamp without time zone", "timestamp").replace("integer", "int").replace("numeric", "decimal")
    return t

def default(d):
    if d is None:
        return None
    d = d.strip()
    if d.startswith("nextval"):
        return None
    m = re.match(r"^'(.*)'::(character varying|text|bpchar)$", d)
    if m:
        return "'%s'" % m.group(1)
    if re.match(r"^-?\d+(\.\d+)?$", d) or d in ("true", "false"):
        return d
    if d == "now()":
        return "`now()`"
    m = re.match(r"^'(.*)'::jsonb$", d)
    if m:
        return "`'%s'::jsonb`" % m.group(1)
    return "`%s`" % d

out = []
# Enums, grouped by schema; one enum per (table, column).
enum_name = {}
for s in SCHEMAS:
    for (es, et, ec), values in sorted(enums_by_col.items()):
        if es != s:
            continue
        name = "%s.%s_%s" % (es, et, ec)
        enum_name[(es, et, ec)] = name
        out.append("Enum %s {\n%s\n}\n" % (name, "\n".join("  " + v for v in values)))

delmap = {"r": "restrict", "c": "cascade", "n": "set null", "a": None, "d": "set default"}

for s in SCHEMAS:
    names = sorted(t for (ts, t) in tables if ts == s)
    if not names:
        continue
    out.append("// " + "=" * 77 + "\n// SCHEMA: %s\n// " % s + "=" * 77 + "\n")
    for t in names:
        key = (s, t)
        out.append("Table %s.%s [headercolor: %s] {" % (s, t, COLORS[s]))
        width = max(len(c["c"]) for c in tables[key])
        pkcols = pk.get(key, [])
        for c in tables[key]:
            typ = enum_name.get((s, t, c["c"])) or dtype(c["type"])
            attrs = []
            if len(pkcols) == 1 and c["c"] == pkcols[0]:
                attrs.append("pk")
            else:
                if c["nn"]:
                    attrs.append("not null")
                if (s, t, c["c"]) in uniq_single:
                    attrs.append("unique")
            d = default(c["def"])
            if d is not None:
                attrs.append("default: %s" % d)
            out.append("  %s %s%s" % (c["c"].ljust(width), typ, (" [%s]" % ", ".join(attrs)) if attrs else ""))
        ind = []
        if len(pkcols) > 1:
            ind.append("    (%s) [pk]" % ", ".join(pkcols))
        for u in uniq_multi.get(key, []):
            ind.append("    (%s) [unique]" % ", ".join(u))
        if ind:
            out.append("  indexes {")
            out.extend(ind)
            out.append("  }")
        out.append("}\n")

out.append("// " + "=" * 77 + "\n// RELATIONSHIPS\n// " + "=" * 77 + "\n")
def refkey(f):
    return (SCHEMAS.index(f["s"]) if f["s"] in SCHEMAS else 99, f["t"], f["cols"])
for f in sorted(fks, key=refkey):
    a = "%s.%s.%s" % (f["s"], f["t"], f["cols"][0] if len(f["cols"]) == 1 else "(" + ", ".join(f["cols"]) + ")")
    b = "%s.%s.%s" % (f["fs"], f["ft"], f["fcols"][0] if len(f["fcols"]) == 1 else "(" + ", ".join(f["fcols"]) + ")")
    d = delmap.get(f["del"])
    out.append("Ref: %s > %s%s" % (a, b, " [delete: %s]" % d if d else ""))
out.append("")

for s in SCHEMAS:
    names = sorted(t for (ts, t) in tables if ts == s)
    if names:
        out.append("TableGroup %s_group [color: %s] {\n%s\n}\n" % (s, COLORS[s], "\n".join("  %s.%s" % (s, t) for t in names)))

open(OUT, "w").write("\n".join(out))
print("tables", len(tables), "refs", len(fks), "enums", len(enum_name))
