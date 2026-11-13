#!/usr/bin/env python3
"""
Generates tools/db/seed/seed_test_data.sql: a test data set for a database migrated with db/migration (dev, the VPS),
which also loads on top of db/demo (local).

    python tools/db/seed/gen_seed_test_data.py          # rewrites seed_test_data.sql and files/ next to this file

The business it describes is the one after the rescope (kltn-docs, November 2026): a B2B seller of cups and packaging
printed to its customers' designs by outside producers. A quote becomes an order, the order a contract with delivery
batches and installments, each batch a production order at a producer; the printed goods arrive in LPN cartons, are
received against their production order (the lot is its code), put away, held for the order, picked by carton, checked
and labelled at the carton check station, planned onto a truck and handed to a carrier.

Why a generator and not a hand-written file: the rows have to agree with each other the way the application leaves
them, and the database checks most of that itself (CHECKs, triggers, deferred constraint triggers). The generator
therefore writes the statements in the order the application would - a contract is drawn up as a DRAFT with its
batches and installments and only then sent and signed, a production order is drawn up as a DRAFT and walked through
its transitions, a carton is received into the receiving area before it is put away - so every trigger sees what it
would see in production. Nothing here turns a trigger off.

Everything is deterministic (fixed dates, md5-derived ids), so regenerating gives the same file.
Ids are md5('seed:' || key)::uuid, the same scheme as db/demo with another namespace.

The Ahamove carrier (API_BOOKING) needs its staging API key sealed with the deployment's own secrets key, so the key is
never in this file: seal_ahamove_key.py reads it from the environment at load time and hands the sealed values to psql
(see the header of the generated file).
"""

import hashlib
import json
import shutil
import struct
import zlib
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

VN = timezone(timedelta(hours=7))
TODAY = date(2026, 11, 10)
OUT = Path(__file__).with_name("seed_test_data.sql")
FILES = Path(__file__).with_name("files")
BY = "seed"


# ============================================================================ helpers
def uid(key):
    h = hashlib.md5(("seed:" + key).encode()).hexdigest()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


class Raw(str):
    """An SQL expression emitted verbatim."""


def at(d, h=9, m=0):
    return datetime(d.year, d.month, d.day, h, m, tzinfo=VN)


def day(n):
    """The scenario's calendar: day n before TODAY."""
    return TODAY - timedelta(days=n)


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, Raw):
        return str(v)
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return repr(v)
    if isinstance(v, datetime):
        return "TIMESTAMPTZ '" + v.strftime("%Y-%m-%d %H:%M:%S") + "+07'"
    if isinstance(v, date):
        return "DATE '" + v.isoformat() + "'"
    if isinstance(v, (dict, list)):
        return "'" + json.dumps(v, ensure_ascii=False).replace("'", "''") + "'::jsonb"
    if isinstance(v, tuple):  # text[]
        return "ARRAY[" + ",".join(q(x) for x in v) + "]::text[]"
    return "'" + str(v).replace("'", "''") + "'"


SQL = []          # the statements, in the order they run
COUNTS = {}       # table -> rows inserted

# Tables without created_by (and, most of them, without created_at): no audit columns are added to their rows.
NO_AUDIT = {
    "catalog.product_listing", "customer.default_payment_installment", "design.design_document",
    "design.design_project_quote_request_line", "design.design_review_round_design", "inventory.stock_reservation",
    "ordering.contract_payment_installment", "ordering.delay_alert", "ordering.delivery_batch",
    "ordering.delivery_batch_line", "ordering.delivery_batch_release", "ordering.delivery_batch_window_change",
    "ordering.order_fee_line", "ordering.order_line", "ordering.order_line_reservation",
    "ordering.order_shipment_line", "ordering.production_order_line", "ordering.production_order_receipt",
    "ordering.production_order_sample_media", "ordering.production_order_status_history",
    "ordering.quote_fee_line", "ordering.quote_line", "ordering.quote_price_tier", "ordering.quote_status_history",
    "product.product_change_log",
    # created_at (with a default) but no created_by
    "customer.customer_change_history", "notification.user_notification", "ordering.delivery_batch_hold",
    "ordering.production_order_sample_round", "ordering.production_order_slip", "payment.installment_reduction",
    "payment.payment_receipt_line",
}


def ins(table, when=None, **row):
    """INSERT one row. `when` is its created_at (default: the start of the scenario)."""
    if table not in NO_AUDIT:
        row.setdefault("created_at", when or at(date(2026, 9, 1), 8))
        row.setdefault("created_by", BY)
    cols = list(row)
    SQL.append(f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({', '.join(q(row[c]) for c in cols)});")
    COUNTS[table] = COUNTS.get(table, 0) + 1
    return row


def upd(table, key, **cols):
    """UPDATE one row by id (or by the given {column: value} key)."""
    where = " AND ".join(f"{k} = {q(v)}" for k, v in (key.items() if isinstance(key, dict) else [("id", key)]))
    SQL.append(f"UPDATE {table} SET {', '.join(f'{k} = {q(v)}' for k, v in cols.items())} WHERE {where};")


def raw(sql):
    SQL.append(sql)


def commit():
    """Where the application would commit: the deferred constraint triggers run now, on the state the step left,
    exactly as they would at the end of that step's own transaction - the whole file being one transaction would
    otherwise check a row's first state against everything that happened to it later."""
    SQL.append("SET CONSTRAINTS ALL IMMEDIATE; SET CONSTRAINTS ALL DEFERRED;")


def section(title):
    SQL.append(f"\n-- ---------------------------------------------------------------------------- {title}")


SEQ = {}   # (document type, date) -> last number used


def next_no(kind, d, fmt):
    """The next number of a document of `kind` on day `d`, as platform.document_sequence would give it."""
    SEQ[(kind, d)] = SEQ.get((kind, d), 0) + 1
    return fmt.format(d=d.strftime("%Y%m%d"), n=SEQ[(kind, d)])


def money(x):
    return int(round(x))


def tax_of(amount, rate):
    return money(amount * rate / 100)


# ============================================================================ files
SEEDED_FILES = {}   # storage key -> bytes


def png_bytes(key, width=240, height=120):
    """A real PNG: horizontal bands in colours derived from `key`, so every file is different."""
    h = hashlib.md5(key.encode()).digest()
    bands = [h[i:i + 3] for i in range(0, 15, 3)]
    rows = b"".join(b"\x00" + bands[y * len(bands) // height] * width for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def pdf_bytes(title):
    """A real one-page PDF naming the document, with a correct cross-reference table."""
    text = title.encode("ascii", "replace").decode().replace("\\", "").replace("(", "").replace(")", "")
    stream = f"BT /F1 18 Tf 40 760 Td ({text}) Tj ET".encode()
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
               b"/Resources << /Font << /F1 5 0 R >> >> >>",
               b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for n, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += f"{n} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


def stored_file(prefix, file_id, content_type, stored_at, title):
    """A file as the storage layer keeps it: <prefix>/<UTC yyyy/MM/dd>/<id>.<ext>, with the real bytes' size and
    SHA-256 (what the application verifies a design file against before printing)."""
    ext = ".pdf" if content_type == "application/pdf" else ".png"
    key = f"{prefix}/{stored_at.astimezone(timezone.utc):%Y/%m/%d}/{file_id}{ext}"
    data = pdf_bytes(title) if ext == ".pdf" else png_bytes(file_id)
    SEEDED_FILES[key] = data
    return dict(key=key, size=len(data), checksum=hashlib.sha256(data).hexdigest())


# ============================================================================ 1. staff
section("1. staff and their roles")
# bcrypt(10) of "Stockflow@2026", in Spring's DelegatingPasswordEncoder format: the published demo password, the
# default of the psql variable seed_password_hash (review S-5). A server anyone can reach is loaded with its own hash.
DEMO_PASSWORD = "{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y"
PASSWORD = Raw(":'seed_password_hash'")
U = {}
ROLE = lambda code: Raw(f"(SELECT id FROM identity.app_role WHERE code = '{code}')")
STAFF = [
    # username, full name, role, warehouses, status
    # No SYSTEM_ADMIN: the database has its own (the application creates it on first start), and nothing here names it.
    ("quanly.hn", "Trần Minh Khoa", "WAREHOUSE_MANAGER", ["HAN"], "ACTIVE"),
    ("quanly.bd", "Lê Thu Hà", "WAREHOUSE_MANAGER", ["BDG"], "ACTIVE"),
    ("kho.hn1", "Phạm Văn Long", "WAREHOUSE_STAFF", ["HAN"], "ACTIVE"),
    ("kho.hn2", "Đỗ Thị Mai", "WAREHOUSE_STAFF", ["HAN"], "ACTIVE"),
    ("kho.bd1", "Ngô Quốc Bảo", "WAREHOUSE_STAFF", ["BDG"], "ACTIVE"),
    ("kho.bd2", "Vũ Thị Lan", "WAREHOUSE_STAFF", ["BDG"], "ACTIVE"),
    ("sales1", "Nguyễn Hải Yến", "SALES_STAFF", [], "ACTIVE"),
    ("sales2", "Lý Gia Hân", "SALES_STAFF", [], "ACTIVE"),
    ("truongkd", "Châu Ngọc Ánh", "SALES_MANAGER", [], "ACTIVE"),
    ("ketoan", "Tạ Thị Hồng", "ACCOUNTANT", [], "ACTIVE"),
    ("dieuphoi", "Mai Xuân Trường", "PRODUCTION_COORDINATOR", [], "ACTIVE"),
    ("nv.nghiviec", "Cao Thị Diễm", "WAREHOUSE_STAFF", ["HAN"], "DISABLED"),
    ("nv.bikhoa", "Quách Văn Đạt", "WAREHOUSE_STAFF", ["BDG"], "LOCKED"),
]


def user(username, full_name, status="ACTIVE", email=None, **extra):
    U[username] = uid("user:" + username)
    ins("identity.app_user", at(date(2026, 8, 20), 8), id=U[username], username=username,
        email=email or f"{username}@stockflow.example", password_hash=PASSWORD, full_name=full_name, status=status,
        **extra)


def grant(username, role):
    ins("identity.user_role", at(date(2026, 8, 20), 8), id=uid(f"ur:{username}:{role}"), user_id=U[username],
        role_id=ROLE(role))


USER_WH = []
for name, full, role, whs, status in STAFF:
    extra = {}
    if status == "LOCKED":
        extra = dict(failed_login_count=5, locked_until=at(date(2026, 12, 31), 23))
    elif status == "ACTIVE":
        extra = dict(last_login_at=at(TODAY, 8, 5))
    user(name, full, status, **extra)
    grant(name, role)
    USER_WH += [(name, w) for w in whs]
ins("identity.user_status_change", at(date(2026, 10, 1), 10), id=uid("usc:nv.nghiviec"), user_id=U["nv.nghiviec"],
    action="DISABLE", from_status="ACTIVE", to_status="DISABLED", reason="Nghỉ việc từ 01/10/2026",
    actor_id=None, actor_username="admin", changed_at=at(date(2026, 10, 1), 10))

# ============================================================================ 2. warehouses
section("2. warehouses: map, shelves, bins and areas")
WH = {
    # prefix: name, address, map width x height (m)
    "HAN": ("Kho Hà Nội", "Lô B2, KCN Nam Thăng Long, Phường Đông Ngạc, TP. Hà Nội", 60, 40),
    "BDG": ("Kho Bình Dương", "Đường số 6, KCN Sóng Thần 1, Phường Dĩ An, TP. Hồ Chí Minh", 60, 40),
}
WID = {p: uid("wh:" + p) for p in WH}
LOC = {}       # location code -> id
BINS = {}      # prefix -> [bin location codes]
AREA = {}      # (prefix, type) -> location code
SHELVES = [("A01", "A"), ("A02", "A"), ("A03", "A"), ("B01", "B"), ("B02", "B")]
LEVELS = 3
BIN_CODES = ["A", "B", "C", "D"]
# Areas: code, type, name, pickable, putaway target, x, y, w, l
AREAS = [
    ("NHAP01", "RECEIVING", "Khu nhận hàng", False, False, 2, 2, 10, 8),
    ("CACHLY01", "QUARANTINE", "Khu cách ly hàng hoàn", False, False, 2, 12, 8, 6),
    ("CHOXUAT01", "STAGING", "Khu chờ xuất", False, False, 14, 30, 12, 6),
    ("XUAT01", "DISPATCH", "Khu xuất hàng", False, False, 30, 30, 12, 8),
    ("TAM01", "OVERFLOW", "Khu tạm khi kho đầy", True, False, 46, 30, 10, 6),
]
for p, (name, address, mw, mh) in WH.items():
    ins("warehouse.warehouse", id=WID[p], name=name, status="ACTIVE", prefix=p, address=address,
        return_address=address, map_unit="M", map_width=mw, map_height=mh)
    for z, color in (("A", "#4F81BD"), ("B", "#9BBB59")):
        ins("warehouse.zone", id=uid(f"zone:{p}:{z}"), warehouse_id=WID[p], name=f"Dãy {z}", color=color)
    ins("warehouse.boundary", id=uid(f"wall:{p}"), warehouse_id=WID[p], type="WALL", start_x=0, start_y=0,
        end_x=mw, end_y=0, is_passable=False, operational_status=None)
    ins("warehouse.boundary", id=uid(f"door:{p}"), warehouse_id=WID[p], type="DOOR", start_x=mw, start_y=2,
        end_x=mw, end_y=8, is_passable=True, operational_status="OPEN")
    BINS[p] = []
    for s_no, (shelf, zone) in enumerate(SHELVES):
        sid = uid(f"shelf:{p}:{shelf}")
        ins("warehouse.shelf", id=sid, warehouse_id=WID[p], zone_id=uid(f"zone:{p}:{zone}"), code=shelf,
            name=f"Kệ {shelf}", x=16 + 8 * s_no, y=4, width=1.2, length=5.0, rotation=0, is_obstacle=True,
            pick_north=False, pick_east=True, pick_south=False, pick_west=True, status="ACTIVE")
        for lv in range(1, LEVELS + 1):
            lid = uid(f"level:{p}:{shelf}:{lv}")
            ins("warehouse.shelf_level", id=lid, shelf_id=sid, level_index=lv, elevation=(lv - 1) * 1.7,
                usable_height=1.6, max_weight=800)
            for b_no, b in enumerate(BIN_CODES):
                code = f"{p}-{shelf}-{lv}-{b}"
                LOC[code] = uid("loc:" + code)
                ins("warehouse.storage_location", id=LOC[code], warehouse_id=WID[p], kind="BIN", location_code=code,
                    max_weight=800, is_pickable=True, is_putaway_target=True, status="ACTIVE")
                ins("warehouse.bin", id=uid("bin:" + code), level_id=lid, location_id=LOC[code], code=b,
                    x=16 + 8 * s_no, y=4 + 1.25 * b_no, width=1.2, length=1.2, rotation=0, type="PALLET")
                BINS[p].append(code)
    for code, typ, name, pickable, putaway, x, y, w, l in AREAS:
        lc = f"{p}-{code}"
        LOC[lc] = uid("loc:" + lc)
        AREA[(p, typ)] = lc
        ins("warehouse.storage_location", id=LOC[lc], warehouse_id=WID[p], kind="AREA", location_code=lc,
            max_weight=None, is_pickable=pickable, is_putaway_target=putaway, status="ACTIVE")
        ins("warehouse.area", id=uid("area:" + lc), warehouse_id=WID[p], location_id=LOC[lc], code=code, type=typ,
            name=name, x=x, y=y, width=w, length=l, rotation=0, is_obstacle=False, status="ACTIVE")
    ins("warehouse.area", id=uid(f"area:{p}-OFFICE"), warehouse_id=WID[p], location_id=None, code="OFFICE",
        type="NON_STORAGE", name="Văn phòng kho", x=2, y=30, width=8, length=6, rotation=0, is_obstacle=True,
        status="ACTIVE")
for name, p in USER_WH:
    ins("identity.user_warehouse", id=uid(f"uw:{name}:{p}"), user_id=U[name], warehouse_id=WID[p])

# ============================================================================ 3. catalogue
section("3. catalogue: customizable products, their blanks and print templates")
ATTR_ID = lambda code: Raw(f"(SELECT id FROM product.attributes WHERE code = '{code}')")
ins("product.brands", id=uid("brand:SFC"), code="SFC", name="StockFlow Cups", slug="stockflow-cups")
ins("product.categories", id=uid("cat:BAO-BI-IN"), parent_id=None, code="BAO-BI-IN", name="Bao bì in theo yêu cầu",
    slug="bao-bi-in-theo-yeu-cau", path="/BAO-BI-IN", depth=0, sort_order=1)
SUBCATS = [("LY-GIAY", "Ly giấy", "ly-giay"), ("LY-NHUA", "Ly nhựa", "ly-nhua"), ("HOP-GIAY", "Hộp giấy", "hop-giay"),
           ("TUI-GIAY", "Túi giấy", "tui-giay")]
for i, (code, name, slug) in enumerate(SUBCATS):
    ins("product.categories", id=uid("cat:" + code), parent_id=uid("cat:BAO-BI-IN"), code=code, name=name,
        slug=slug, path=f"/BAO-BI-IN/{code}", depth=1, sort_order=i + 1)
for code, name, unit in (("DUNG_TICH", "Dung tích", "oz"), ("KICH_THUOC", "Kích thước", None)):
    raw(f"INSERT INTO product.attributes (id, code, name, data_type, unit, is_filterable, created_at, created_by) "
        f"VALUES ({q(uid('attr:' + code))}, {q(code)}, {q(name)}, 'SELECT', {q(unit)}, TRUE, "
        f"{q(at(date(2026, 8, 22), 9))}, 'seed') ON CONFLICT (code) DO NOTHING;")

# Products: code, name, category, tax class, model kind, variant axis, [(variant code, label, kg per piece)]
PRODUCTS = [
    ("LY-GIAY-2L", "Ly giấy 2 lớp in logo", "LY-GIAY", "STANDARD", "parametric-cup", "DUNG_TICH",
     [("8OZ", "8 oz", 0.006), ("12OZ", "12 oz", 0.008), ("16OZ", "16 oz", 0.011)]),
    ("LY-NHUA-PET", "Ly nhựa PET in logo", "LY-NHUA", "STANDARD", "parametric-cup", "DUNG_TICH",
     [("16OZ", "16 oz", 0.012), ("22OZ", "22 oz", 0.016)]),
    ("HOP-BANH-KRAFT", "Hộp bánh giấy kraft in logo", "HOP-GIAY", "REDUCED", "parametric-bag", "KICH_THUOC",
     [("M", "Vừa 18x18 cm", 0.035)]),
    ("TUI-GIAY-QUAI", "Túi giấy quai xoắn in logo", "TUI-GIAY", "STANDARD", "parametric-bag", "KICH_THUOC",
     [("M", "Vừa 25x33 cm", 0.06), ("L", "Lớn 32x43 cm", 0.09)]),
]
VARIANT = {}   # blank sku -> facts
for pcode, pname, cat, taxc, model, axis, variants in PRODUCTS:
    pid = uid("product:" + pcode)
    ins("product.products", at(date(2026, 8, 22), 9), id=pid, brand_id=uid("brand:SFC"), code=pcode, name=pname,
        slug=pcode.lower(), short_description=f"{pname}, in theo thiết kế của khách hàng.", tax_class=taxc,
        kind="CUSTOMIZABLE", status="PUBLISHED", submitted_by=U["sales1"], submitted_at=at(date(2026, 8, 22), 10),
        approved_by=U["truongkd"], approved_at=at(date(2026, 8, 23), 9), published_at=at(date(2026, 8, 23), 9))
    ins("product.product_categories", id=uid("pc:" + pcode), product_id=pid, category_id=uid("cat:" + cat),
        is_primary=True)
    ins("product.product_attributes", id=uid(f"pa:{pcode}:{axis}"), product_id=pid, attribute_id=ATTR_ID(axis),
        role="VARIANT_AXIS")
    for v_no, (vcode, label, kg) in enumerate(variants):
        sku = f"{pcode}-{vcode}"
        vid = uid("variant:" + sku)
        value = Raw(f"(SELECT id FROM product.attribute_values WHERE attribute_id = {ATTR_ID(axis)} "
                    f"AND code = '{vcode}')")
        raw(f"INSERT INTO product.attribute_values (id, attribute_id, code, label, sort_order, created_at, created_by) "
            f"VALUES ({q(uid(f'val:{axis}:{vcode}'))}, {ATTR_ID(axis)}, {q(vcode)}, {q(label)}, {v_no}, "
            f"{q(at(date(2026, 8, 22), 9))}, 'seed') ON CONFLICT (attribute_id, code) DO NOTHING;")
        ins("product.product_attribute_options", id=uid(f"po:{pcode}:{vcode}"),
            product_attribute_id=uid(f"pa:{pcode}:{axis}"), attribute_value_id=value)
        ins("product.variants", at(date(2026, 8, 22), 9), id=vid, product_id=pid, sku=sku, name=f"{pname} - {label}",
            status="ACTIVE", is_default=v_no == 0, attribute_signature=f"{axis}={vcode}", position=v_no)
        ins("product.variant_attribute_values", id=uid("vav:" + sku), variant_id=vid,
            product_attribute_option_id=uid(f"po:{pcode}:{vcode}"))
        tid = uid("template:" + sku)
        ins("product.customization_templates", id=tid, variant_id=vid, code="STD", name=f"Mẫu in {label}",
            min_dpi=300, color_mode="CMYK", allowed_file_formats=("image/png", "image/jpeg"), max_file_size_mb=20,
            max_colors=4, is_active=True, model_params_json={"kind": model})
        cup = model == "parametric-cup"
        ins("product.print_areas", id=uid("printarea:" + sku), template_id=tid, code="BODY" if cup else "FRONT",
            name="Thân ly" if cup else "Mặt trước", x_mm=10, y_mm=10, width_mm=200, height_mm=80, safe_margin_mm=3,
            bleed_mm=2, shape_json={"type": "annular-sector" if cup else "rect"}, uv_mapping_json={"meshPart": "body"})
        VARIANT[sku] = dict(id=vid, product=pcode, product_id=pid, label=label, kg=kg, tax=taxc, name=pname,
                            template=tid)
        raw(f"UPDATE inventory.inventory_items SET weight_kg = {kg}, length_cm = 9, width_cm = 9, height_cm = 11 "
            f"WHERE sku = '{sku}';")
TAX_RATE = {"STANDARD": 10, "REDUCED": 5, "EXEMPT": 0}

# ============================================================================ 4. customers
section("4. customers: accounts, addresses, legal profiles, credit and payment terms")
# code, company, tax code, contact, phone, (province code, name), (ward code, name), address line, credit, status
CUSTOMERS = [
    ("caphemoc", "Công ty TNHH Cà Phê Mộc", "0312345671", "Nguyễn Văn Mộc", "0901000001", ("79", "TP. Hồ Chí Minh"),
     ("26734", "Phường Bến Thành"), "12 Lê Lợi", (500_000_000, 30), "ACTIVE"),
    ("trasuamay", "Công ty CP Trà Sữa Mây", "0109876542", "Trần Thị Mây", "0901000002", ("01", "TP. Hà Nội"),
     ("00004", "Phường Ba Đình"), "45 Kim Mã", (300_000_000, 30), "ACTIVE"),
    ("banhmiviet", "Công ty TNHH Bánh Mì Việt", "0315554443", "Lê Quốc Việt", "0901000003",
     ("79", "TP. Hồ Chí Minh"), ("27139", "Phường Tân Định"), "88 Hai Bà Trưng", None, "ACTIVE"),
    ("comtamsg", "Hộ kinh doanh Cơm Tấm Sài Gòn", "0316667778", "Phạm Thị Hoa", "0901000004",
     ("79", "TP. Hồ Chí Minh"), ("26743", "Phường Sài Gòn"), "7 Nguyễn Trãi", None, "ACTIVE"),
    ("kemtuoi", "Công ty TNHH Kem Tươi Hà Nội", "0101234565", "Đỗ Minh Kem", "0901000005", ("01", "TP. Hà Nội"),
     ("00070", "Phường Hoàn Kiếm"), "21 Hàng Bài", (200_000_000, 45), "ACTIVE"),
    ("nuocepxanh", "Công ty CP Nước Ép Xanh", "0317778889", "Vũ Thanh Xuân", "0901000006",
     ("79", "TP. Hồ Chí Minh"), ("26737", "Phường Bến Thành"), "150 Lý Tự Trọng", (150_000_000, 30), "ACTIVE"),
    ("lauthai", "Công ty TNHH Lẩu Thái Phố", "0108889990", "Hoàng Thái", "0901000007", ("01", "TP. Hà Nội"),
     ("00091", "Phường Cửa Nam"), "9 Tràng Thi", None, "PENDING_ACTIVATION"),
    ("bunbohue", "Hộ kinh doanh Bún Bò Huế O Hà", "0319990001", "Lê Thị Hà", "0901000008",
     ("79", "TP. Hồ Chí Minh"), ("27142", "Phường Tân Định"), "33 Trần Quang Khải", None, "INACTIVE"),
]
CUST = {}
for code, company, tax, contact, phone, prov, ward, line1, credit, status in CUSTOMERS:
    cid = uid("customer:" + code)
    uname = "kh." + code
    user(uname, contact, email=f"{code}@khachhang.example")
    grant(uname, "CUSTOMER")
    ins("customer.customer", at(date(2026, 8, 25), 9), id=cid, full_name=company, email=f"{code}@khachhang.example",
        phone=phone, status=status, user_id=U[uname], tax_code=tax)
    ins("customer.customer_status_history", at(date(2026, 8, 25), 9), id=uid(f"csh:{code}:1"), customer_id=cid,
        from_status=None, to_status="PENDING_ACTIVATION", reason="Mời dùng cổng khách hàng", changed_by=U["sales1"],
        changed_at=at(date(2026, 8, 25), 9))
    if status != "PENDING_ACTIVATION":
        ins("customer.customer_status_history", at(date(2026, 8, 26), 10), id=uid(f"csh:{code}:2"), customer_id=cid,
            from_status="PENDING_ACTIVATION", to_status="ACTIVE", reason="Kích hoạt tài khoản", changed_by=U[uname],
            changed_at=at(date(2026, 8, 26), 10))
    if status == "INACTIVE":
        ins("customer.customer_status_history", at(date(2026, 10, 20), 10), id=uid(f"csh:{code}:3"),
            customer_id=cid, from_status="ACTIVE", to_status="INACTIVE", reason="Ngừng kinh doanh",
            changed_by=U["truongkd"], changed_at=at(date(2026, 10, 20), 10))
    addr = f"{line1}, {ward[1]}, {prov[1]}"
    for typ in ("SHIPPING", "BILLING"):
        ins("customer.address", id=uid(f"addr:{code}:{typ}"), customer_id=cid, type=typ, recipient_name=contact,
            phone=phone, line1=line1, ward=ward[1], province=prov[1], country="VN", is_default=True,
            ward_code=ward[0], province_code=prov[0])
    if status == "ACTIVE":
        ins("customer.customer_legal_profile", id=uid("legal:" + code), customer_id=cid, registered_address=addr,
            representative_name=contact, representative_title="Giám đốc")
    if credit:
        ins("customer.credit_profile", id=uid("credit:" + code), customer_id=cid, credit_limit=credit[0],
            credit_term_days=credit[1], currency="VND", approved_by=U["ketoan"], approved_at=at(date(2026, 8, 28), 14),
            note="Duyệt hạn mức theo báo cáo tài chính 2025")
    CUST[code] = dict(id=cid, company=company, tax=tax, contact=contact, phone=phone, prov=prov, ward=ward,
                      line1=line1, addr=addr, credit=credit, user=U[uname], uname=uname, status=status)
# Default payment terms: 30% on signing, 70% after delivery (credit customers only), approved by accounting.
for code in ("caphemoc", "kemtuoi"):
    sid = uid("dps:" + code)
    ins("customer.default_payment_schedule", id=sid, customer_id=CUST[code]["id"], set_by=U["truongkd"],
        set_at=at(date(2026, 8, 29), 9), approved_by=U["ketoan"], approved_at=at(date(2026, 8, 29), 15),
        note="Điều khoản mặc định theo hợp đồng khung")
    ins("customer.default_payment_installment", schedule_id=sid, seq=1, name="Đặt cọc khi ký", percent=30,
        milestone="ON_SIGNING", due_days=0)
    ins("customer.default_payment_installment", schedule_id=sid, seq=2, name="Thanh toán sau giao hàng", percent=70,
        milestone="AFTER_DELIVERY", due_days=30)

# ============================================================================ 5. producers
section("5. producers: the print shops production orders are sent to")
PRODUCERS = [
    ("INSG", "Công ty In Bao Bì Sài Gòn", "Phạm Văn In", "0283900111", "sanxuat@inbaobisg.example", "EMAIL", 12,
     "ACTIVE"),
    ("INHN", "Xưởng In Ly Giấy Hà Nội", "Nguyễn Thị Ly", "0243900222", "donhang@inlyhn.example", "EMAIL", 10, "ACTIVE"),
    ("TANPHU", "Xưởng In Tân Phú", "Trương Văn Phú", "0283900333", None, "PDF", 15, "ACTIVE"),
    ("INCU", "Xưởng In Cũ Quận 8", "Lê Văn Cũ", "0283900444", "incu@inbaobi.example", "EMAIL", 20, "INACTIVE"),
]
PRODUCER = {}
for code, name, contact, phone, email, channel, lead, status in PRODUCERS:
    PRODUCER[code] = uid("producer:" + code)
    ins("ordering.producer", at(date(2026, 8, 21), 9), id=PRODUCER[code], code=code, name=name, contact_name=contact,
        contact_phone=phone, email=email, channel=channel, standard_lead_days=lead, status=status,
        note="Ngừng hợp tác từ 10/2026" if status == "INACTIVE" else None)

# ============================================================================ 6. designs and customer SKUs
section("6. designs approved for customers, and the SKUs issued on them")
# Pieces to a carton, by product; every carton is 60 x 40 x 50 cm.
CARTON = {"LY-GIAY-2L": 1000, "LY-NHUA-PET": 500, "HOP-BANH-KRAFT": 300, "TUI-GIAY-QUAI": 200}
SKU = {}       # customer sku -> facts
REVIEW_NOTE = "Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm"


def design(key, cust, blank, name, status, when, project=None):
    """A design of the customer on a blank's print template, as the design studio leaves it. APPROVED: two files, the
    review decisions and the confirmed snapshot; PENDING_REVIEW: files, submitted; DRAFT: nothing uploaded yet."""
    c, v = CUST[cust], VARIANT[blank]
    did = uid("design:" + key)
    arts = []
    if status in ("APPROVED", "PENDING_REVIEW", "ARCHIVED"):
        for role, ctype, ext in (("CUSTOMER_PREVIEW", "image/png", "png"), ("PRINT_READY", "application/pdf", "pdf")):
            aid = uid(f"art:{key}:{role}")
            f = stored_file("design-renders", aid, ctype, when + timedelta(hours=2), f"{key} - {role}")
            arts.append(dict(id=aid, role=role, key=f["key"], name=f"{role.lower()}.{ext}", ctype=ctype,
                             size=f["size"], checksum=f["checksum"]))
    spec = f"In {name} theo bộ nhận diện của {c['company']}; 2 màu Pantone, vị trí giữa thân."
    reviewed = when + timedelta(days=2)
    approved = status in ("APPROVED", "ARCHIVED")
    ins("design.design_draft", when, id=did, customer_id=c["id"], product_id=v["product_id"], name=name, spec=spec,
        status=status, owner_user_id=c["user"], current_artifact_id=arts[0]["id"] if arts else None,
        reviewed_by=U["sales1"] if approved else None, review_notes=REVIEW_NOTE if approved else None,
        current_artifacts={a["role"]: a["id"] for a in arts}, variant_id=v["id"], sku=blank, template_id=v["template"],
        template_code="STD", review_requested_at=when + timedelta(days=1) if arts else None,
        reviewed_at=reviewed if approved else None,
        archived_at=at(date(2026, 10, 25), 10) if status == "ARCHIVED" else None, project_id=project)
    for a in arts:
        ins("design.design_artifact", when + timedelta(hours=2), id=a["id"], draft_id=did, storage_key=a["key"],
            original_name=a["name"], content_type=a["ctype"], size_bytes=a["size"], stored_at=when + timedelta(hours=2),
            checksum=a["checksum"], artifact_role=a["role"])
    steps = [("CREATED", None, c["user"], "Tạo thiết kế", 0)]
    if arts:
        steps.append(("SUBMITTED_FOR_REVIEW", arts[0]["id"], c["user"], "Gửi duyệt thiết kế", 1))
    if approved:
        steps.append(("APPROVED", arts[0]["id"], U["sales1"], REVIEW_NOTE, 2))
    if status == "ARCHIVED":
        steps.append(("ARCHIVED", None, c["user"], "Khách ngừng dùng mẫu này", 3))
    for k, (decision, art, actor, notes, d) in enumerate(steps):
        ins("design.design_decision", when + timedelta(days=d), id=uid(f"dec:{key}:{k}"), draft_id=did,
            artifact_id=art, actor_id=actor, decision=decision, notes=notes, draft_version=k)
    if not approved:
        return dict(draft=did)
    sid = uid("snapshot:" + key)
    h = hashlib.sha256()
    for a in sorted(arts, key=lambda x: x["role"]):
        h.update(f"{a['role']}:{a['id']}:{a['checksum']}\n".encode())
    checksum = h.hexdigest()
    ins("design.design_snapshot", reviewed, id=sid, draft_id=did, checksum=checksum, artifact_url=arts[0]["key"],
        confirmed_at=reviewed, artifact_id=arts[0]["id"], confirmed_by=c["user"], spec=spec, reviewed_by=U["sales1"],
        review_notes=REVIEW_NOTE,
        artifact_manifest=[{"artifactId": a["id"], "role": a["role"], "storageKey": a["key"],
                            "originalName": a["name"], "contentType": a["ctype"], "sizeBytes": a["size"],
                            "checksum": a["checksum"]} for a in sorted(arts, key=lambda x: x["role"])])
    return dict(draft=did, snapshot=sid, checksum=checksum, at=reviewed)


def customer_sku(cust, blank, suffix, name, when, project=None):
    """The customer's own SKU drawn on the blank once its design is approved (kltn-docs 12 §4.5, 01 BR-03), sold and
    stocked in cartons of its product's carton unit."""
    v = VARIANT[blank]
    sku = f"{blank}-{suffix}"
    d = design("d:" + sku, cust, blank, name, "APPROVED", when, project)
    ins("product.variants", d["at"], id=uid("variant:" + sku), product_id=v["product_id"], sku=sku,
        name=f"{v['name']} - {name}", status="ACTIVE", is_default=False, attribute_signature="OWNED-SKU:" + sku,
        position=10, owner_customer_id=CUST[cust]["id"], design_snapshot_id=d["snapshot"], source_variant_id=v["id"])
    per = CARTON[v["product"]]
    barcode = "89" + f"{int(hashlib.md5(sku.encode()).hexdigest(), 16) % 10 ** 11:011d}"
    raw(f"UPDATE inventory.inventory_items SET weight_kg = {v['kg']}, length_cm = 9, width_cm = 9, height_cm = 11, "
        f"barcode = '{barcode}' WHERE sku = '{sku}';")
    unit = uid("unit:" + sku)
    weight = round(per * v["kg"] + 0.6, 3)
    ins("inventory.uom_conversions", d["at"], id=unit,
        inventory_item_id=Raw(f"(SELECT id FROM inventory.inventory_items WHERE sku = '{sku}')"),
        unit_of_measure="THUNG", factor=per, is_base=False, weight_kg=weight, length_cm=60, width_cm=40, height_cm=50,
        default_purchase=False, active=True, default_shipping=True)
    SKU[sku] = dict(sku=sku, cust=cust, blank=blank, unit=unit, per=per, snapshot=d["snapshot"],
                    checksum=d["checksum"], tax=v["tax"], rate=TAX_RATE[v["tax"]], name=name, product=v["product"],
                    weight=weight)
    return sku


PROJECT = uid("project:trasuamay")
ins("design.design_project", at(date(2026, 9, 2), 10), id=PROJECT, customer_id=CUST["trasuamay"]["id"],
    owner_user_id=CUST["trasuamay"]["user"], name="Bộ nhận diện mùa thu 2026",
    description="Ly và túi in chung bộ nhận diện mùa thu", status="ACTIVE")
ins("design.design_project_event", at(date(2026, 9, 2), 10), id=uid("pev:trasuamay:1"), project_id=PROJECT,
    actor_id=CUST["trasuamay"]["user"], event="PROJECT_CREATED", notes="Tạo dự án")
CPM_LY12 = customer_sku("caphemoc", "LY-GIAY-2L-12OZ", "CPM", "Logo Cà Phê Mộc", at(date(2026, 8, 30), 10))
CPM_PET16 = customer_sku("caphemoc", "LY-NHUA-PET-16OZ", "CPM", "Logo Cà Phê Mộc trên ly PET",
                         at(date(2026, 8, 30), 11))
TSM_PET22 = customer_sku("trasuamay", "LY-NHUA-PET-22OZ", "TSM", "Mây mùa thu", at(date(2026, 9, 3), 10), PROJECT)
TSM_TUI = customer_sku("trasuamay", "TUI-GIAY-QUAI-M", "TSM", "Túi Mây mùa thu", at(date(2026, 9, 3), 11), PROJECT)
BMV_TUI = customer_sku("banhmiviet", "TUI-GIAY-QUAI-L", "BMV", "Túi Bánh Mì Việt", at(date(2026, 9, 1), 10))
BMV_HOP = customer_sku("banhmiviet", "HOP-BANH-KRAFT-M", "BMV", "Hộp Bánh Mì Việt", at(date(2026, 9, 1), 11))
CTS_HOP = customer_sku("comtamsg", "HOP-BANH-KRAFT-M", "CTS", "Hộp Cơm Tấm Sài Gòn", at(date(2026, 9, 4), 10))
KTH_LY8 = customer_sku("kemtuoi", "LY-GIAY-2L-8OZ", "KTH", "Ly Kem Tươi", at(date(2026, 8, 31), 10))
NEX_PET16 = customer_sku("nuocepxanh", "LY-NHUA-PET-16OZ", "NEX", "Ly Nước Ép Xanh", at(date(2026, 9, 5), 10))
# Designs in the other states of the studio.
design("d:trasuamay:draft", "trasuamay", "LY-GIAY-2L-16OZ", "Ly giấy Mây mùa đông", "DRAFT",
       at(date(2026, 11, 5), 10), PROJECT)
design("d:comtamsg:review", "comtamsg", "TUI-GIAY-QUAI-M", "Túi Cơm Tấm", "PENDING_REVIEW", at(date(2026, 11, 6), 9))
design("d:caphemoc:old", "caphemoc", "LY-GIAY-2L-8OZ", "Logo Cà Phê Mộc cũ", "ARCHIVED", at(date(2026, 8, 28), 9))
ins("design.design_project_event", at(date(2026, 11, 5), 10), id=uid("pev:trasuamay:2"), project_id=PROJECT,
    actor_id=CUST["trasuamay"]["user"], event="ASSET_ADDED", notes="Thêm logo mùa đông")
ins("design.design_quote_request", at(date(2026, 11, 7), 9), id=uid("dqr:kemtuoi"),
    draft_id=uid("design:d:" + KTH_LY8), requested_by=CUST["kemtuoi"]["user"], cartons=20,
    desired_delivery_date=date(2026, 12, 15), status="OPEN")

# ============================================================================ 7. carriers
section("7. carriers: a truck hirer typed by hand, and Ahamove booked through its API")
CARRIER = {"XT-NAMBAC": uid("carrier:XT-NAMBAC"), "AHAMOVE": uid("carrier:AHAMOVE")}
ins("fulfillment.carrier", at(date(2026, 8, 21), 9), id=CARRIER["XT-NAMBAC"], code="XT-NAMBAC",
    name="Vận tải Nam Bắc", contact_name="Điều phối Nam Bắc", contact_phone="0909111222", status="ACTIVE",
    connection_type="MANUAL")
# Ahamove: API_BOOKING when the loader passes the sealed key (seal_ahamove_key.py), else MANUAL until it is entered on
# the carrier screen - the database refuses API_BOOKING without a sealed key, and the key is never committed.
raw(r"""\if :{?ahamove_api_key_sealed}
\set ahamove_api true
\else
\set ahamove_api false
\set ahamove_api_key_sealed ''
\set ahamove_webhook_auth_sealed ''
\set ahamove_api_account ''
\endif""")


def api_or_manual(api, manual=None):
    """What Ahamove's API and webhook write when the carrier loads API_BOOKING, and what a person records instead when
    it loads MANUAL (no sealed key; review S-2): a psql \\if on the variable set just above. Both branches leave the
    generator's own state the same."""
    raw(r"\if :ahamove_api")
    api()
    raw(r"\else")
    if manual:
        manual()
    raw(r"\endif")
raw(f"""INSERT INTO fulfillment.carrier (id, code, name, contact_name, contact_phone, status, connection_type,
       webhook_enabled, webhook_auth_header, webhook_auth_value_sealed, api_environment, api_account, api_key_sealed,
       failure_reason_map, created_at, created_by)
SELECT {q(CARRIER['AHAMOVE'])}, 'AHAMOVE', 'Ahamove', 'Ahamove Partner Support', '1900545411', 'ACTIVE',
       CASE WHEN k.sealed THEN 'API_BOOKING' ELSE 'MANUAL' END, k.sealed,
       CASE WHEN k.sealed THEN 'X-Ahamove-Token' END, NULLIF(:'ahamove_webhook_auth_sealed', ''),
       CASE WHEN k.sealed THEN 'STAGING' END, NULLIF(:'ahamove_api_account', ''), NULLIF(:'ahamove_api_key_sealed', ''),
       '{{"CUSTOMER_NOT_AVAILABLE": "CUSTOMER_ABSENT", "CUSTOMER_REFUSED": "REFUSED",
         "WRONG_ADDRESS": "WRONG_ADDRESS"}}'::jsonb,
       {q(at(date(2026, 8, 21), 9))}, 'seed'
  FROM (SELECT :'ahamove_api_key_sealed' <> '' AS sealed) k;""")
COUNTS["fulfillment.carrier"] += 1


def ahamove_settings():
    for p, (prov, addr) in {"HAN": ("01", WH["HAN"][1]), "BDG": ("79", WH["BDG"][1])}.items():
        ins("fulfillment.carrier_pickup_point", id=uid(f"pickup:AHAMOVE:{p}"), carrier_id=CARRIER["AHAMOVE"],
            warehouse_id=WID[p], province_code=prov, address=addr, contact_name=f"Kho {p}",
            contact_phone="0909000" + prov)
    for code, svc in (("TRUCK-1T25", "TRUCK-1000"), ("TRUCK-2T5", "TRUCK-2000")):
        for prov, city in (("01", "HAN"), ("79", "SGN")):
            ins("fulfillment.carrier_service_code", id=uid(f"svc:{code}:{prov}"), carrier_id=CARRIER["AHAMOVE"],
                vehicle_type_id=Raw(f"(SELECT id FROM fulfillment.vehicle_type WHERE code = '{code}')"),
                province_code=prov, carrier_city_code=city, service_code=f"{city}-{svc}")


api_or_manual(ahamove_settings)

# ============================================================================ 8. the sales cycle
# Seller block of every contract (kltn-docs 18 BR-05).
SELLER = dict(seller_name="Công ty TNHH StockFlow Commerce", seller_tax_code="0318888888",
              seller_address="Lô B2, KCN Nam Thăng Long, Phường Đông Ngạc, TP. Hà Nội",
              seller_representative="Võ Hoàng Minh", seller_representative_title="Giám đốc")
STAFF_OF = {"HAN": ("quanly.hn", "kho.hn1", "kho.hn2"), "BDG": ("quanly.bd", "kho.bd1", "kho.bd2")}
NEXT_BIN = {"HAN": 0, "BDG": 0}
STOCK = {}          # stock row id -> facts (sku, warehouse, location, on_hand, reserved, package, lpn, lot, status)
ORDERS = []


def lpn(d):
    return next_no("LPN", d, "LPN-{d}-{n:06d}")


def bin_for(p):
    """Bins in turn: the putaway suggestion of an empty warehouse."""
    code = BINS[p][NEXT_BIN[p] % len(BINS[p])]
    NEXT_BIN[p] += 1
    return code


def quote(key, cust, lines, when, status, sales="sales1", feedback=None, reason=None):
    """A quote of cartons of the customer's own SKUs (14 §4.2). lines: [(sku, cartons, price of one piece)]."""
    c = CUST[cust]
    qid = uid("quote:" + key)
    number = next_no("QT", when.date(), "QT-{d}-{n:04d}")
    sent = status not in ("DRAFT", "CANCELLED") or reason == "after-send"
    responded = status in ("CHANGES_REQUESTED", "ACCEPTED", "CONVERTED")
    accepted = status in ("ACCEPTED", "CONVERTED")
    valid_until = when + timedelta(days=5 if status == "EXPIRED" else 30)
    row = ins("ordering.quote", when, id=qid, quote_number=number, request_id=uid("quote-req:" + key),
              customer_id=c["id"], status="ACCEPTED" if status == "CONVERTED" else status, revision=1,
              valid_until=valid_until, terms="Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.",
              sent_at=when + timedelta(hours=3) if sent else None, sent_by=U[sales] if sent else None,
              responded_at=when + timedelta(days=1) if responded else None,
              responded_by=c["user"] if responded else None,
              customer_feedback=feedback if status == "CHANGES_REQUESTED" else None,
              cancellation_reason="Khách đổi kế hoạch kinh doanh" if status == "CANCELLED" else None,
              requested_delivery_date=(when + timedelta(days=30)).date())
    offer = []
    for n, (sku, cartons, price) in enumerate(lines, 1):
        s = SKU[sku]
        lid = uid(f"quote-line:{key}:{n}")
        ins("ordering.quote_line", id=lid, quote_id=qid, line_no=n, sku=sku, uom_conversion_id=s["unit"],
            cartons=cartons, units_per_carton=s["per"], tax_class=s["tax"], tax_rate=s["rate"],
            accepted_cartons=cartons if accepted else None, accepted_unit_price=price if accepted else None)
        ins("ordering.quote_price_tier", quote_line_id=lid, min_quantity=1, unit_price=price)
        offer.append({"lineNo": n, "sku": sku, "uomConversionId": s["unit"], "cartons": cartons,
                      "unitsPerCarton": s["per"], "taxClass": s["tax"], "taxRate": s["rate"],
                      "tiers": [{"minQuantity": 1, "unitPrice": price}]})
    hist = [(None, "DRAFT", when, U[sales], None, None)]
    if sent:
        hist.append(("DRAFT", "SENT", when + timedelta(hours=3), U[sales], None,
                     {"revision": 1, "validUntil": valid_until.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                      "terms": row["terms"], "requestedDeliveryDate": row["requested_delivery_date"].isoformat(),
                      "lines": offer, "fees": []}))
    if status == "CHANGES_REQUESTED":
        hist.append(("SENT", status, when + timedelta(days=1), c["user"], feedback, None))
    if accepted:
        hist.append(("SENT", "ACCEPTED", when + timedelta(days=1), c["user"], None, None))
    if status == "EXPIRED":
        hist.append(("SENT", "EXPIRED", valid_until + timedelta(hours=1), None, None, None))
    if status == "CANCELLED":
        hist.append(("SENT" if sent else "DRAFT", "CANCELLED", when + timedelta(days=2), U[sales],
                     row["cancellation_reason"], None))
    for k, (frm, to, t, by, why, off) in enumerate(hist):
        ins("ordering.quote_status_history", id=uid(f"qsh:{key}:{k}"), quote_id=qid, revision=1, from_status=frm,
            to_status=to, reason=why, offer=off, changed_at=t, changed_by=by)
    commit()
    return dict(id=qid, number=number, key=key, lines=lines, cust=cust, at=when, sales=sales,
                valid_until=valid_until, history=len(hist))


class Order:
    """One order of a contract walked through its life, statement by statement."""

    def __init__(self, key, cust, lines, when, ship_from, sales="sales1"):
        self.key, self.cust, self.c, self.sales, self.p = key, cust, CUST[cust], sales, ship_from
        self.q = quote(key, cust, lines, when - timedelta(days=2), "CONVERTED", sales)
        self.id = uid("order:" + key)
        self.number = next_no("SO", when.date(), "SO-{d}-{n:06d}")
        self.status = "DRAFT"
        self.history = 0
        self.lines = []
        net_total = tax_total = 0
        for n, (sku, cartons, price) in enumerate(lines, 1):
            s = SKU[sku]
            units = cartons * s["per"]
            net = money(units * price)
            tax = tax_of(net, s["rate"])
            self.lines.append(dict(id=uid(f"order-line:{key}:{n}"), sku=sku, cartons=cartons, per=s["per"],
                                   units=units, price=price, net=net, tax=tax, s=s, holds=[], allocations=[]))
            net_total += net
            tax_total += tax
        self.total, self.tax = net_total + tax_total, tax_total
        c = self.c
        ship = dict(shipping_recipient_name=c["contact"], shipping_phone=c["phone"], shipping_line1=c["line1"],
                    shipping_ward_code=c["ward"][0], shipping_ward_name=c["ward"][1],
                    shipping_province_code=c["prov"][0], shipping_province_name=c["prov"][1],
                    shipping_country_code="VN", billing_recipient_name=c["company"], billing_phone=c["phone"],
                    billing_line1=c["line1"], billing_ward_code=c["ward"][0], billing_ward_name=c["ward"][1],
                    billing_province_code=c["prov"][0], billing_province_name=c["prov"][1], billing_country_code="VN")
        ins("ordering.customer_order", when, id=self.id, order_number=self.number, customer_id=c["id"],
            request_id=uid("order-req:" + key), status="DRAFT", total_amount=self.total, currency="VND",
            placed_at=when, contact_name=c["contact"], contact_email=f"{cust}@khachhang.example",
            contact_phone=c["phone"], tax_amount=self.tax, **ship)
        self.log(None, "DRAFT", when, U[sales], f"Chuyển từ báo giá {self.q['number']}")
        for ln in self.lines:
            ins("ordering.order_line", id=ln["id"], order_id=self.id, sku=ln["sku"], quantity=ln["units"],
                unit_price=ln["price"], currency="VND", requested_delivery_date=(when + timedelta(days=30)).date(),
                uom_conversion_id=ln["s"]["unit"], cartons=ln["cartons"], units_per_carton=ln["per"],
                tax_rate=ln["s"]["rate"], tax_amount=ln["tax"])
        upd("ordering.quote", self.q["id"], status="CONVERTED", converted_order_id=self.id, converted_at=when)
        ins("ordering.quote_status_history", id=uid(f"qsh:{key}:{self.q['history']}"), quote_id=self.q["id"],
            revision=1, from_status="ACCEPTED", to_status="CONVERTED", reason=self.number, changed_at=when,
            changed_by=U[sales])
        self.batches, self.plan, self.payments = [], [], []
        self.signed_at = self.released = self.delivered_at = self.cancelled_at = None
        self.contract = uid("contract:" + key)
        self.contract_number = "HD-" + self.number[3:] + "-1"
        self.contract_status = None
        self.picks, self.shipments = [], []
        commit()
        ORDERS.append(self)

    def log(self, frm, to, when, actor, reason=None):
        ins("ordering.order_status_history", when, id=uid(f"osh:{self.key}:{self.history}"), order_id=self.id,
            from_status=frm, to_status=to, reason=reason, actor_id=actor, occurred_at=when)
        self.history += 1

    def move(self, to, when, actor, reason=None, **cols):
        upd("ordering.customer_order", self.id, status=to, **cols)
        self.log(self.status, to, when, actor, reason)
        self.status = to
        commit()

    # ---------------------------------------------------------------- contract
    def draw_up(self, when, batches, plan, free_rounds=1):
        """The contract as a DRAFT with its delivery batches (window, cartons of each line) and payment plan
        [(name, milestone, percent, due_days, batch seq)] - 14 BR-13, BR-23."""
        self.contract_status = "DRAFT"
        ins("ordering.sales_contract", when, id=self.contract, contract_number=self.contract_number,
            order_id=self.id, status="DRAFT", buyer_name=self.c["company"], buyer_tax_code=self.c["tax"],
            buyer_address=self.c["addr"], buyer_representative=self.c["contact"],
            buyer_representative_title="Giám đốc", free_sample_rounds=free_rounds, **SELLER)
        for seq, (start, end, cartons) in enumerate(batches, 1):
            bid = uid(f"batch:{self.key}:{seq}")
            self.batches.append(dict(id=bid, seq=seq, start=start, end=end, cartons=cartons, released=None))
            ins("ordering.delivery_batch", id=bid, contract_id=self.contract, seq=seq, window_start=start,
                window_end=end)
            for ln, n in zip(self.lines, cartons):
                if n:
                    ins("ordering.delivery_batch_line", id=uid(f"batch-line:{self.key}:{seq}:{ln['id']}"),
                        delivery_batch_id=bid, order_line_id=ln["id"], cartons=n)
        left = self.total
        for seq, (name, milestone, pct, due_days, batch) in enumerate(plan, 1):
            amount = left if seq == len(plan) else money(self.total * pct / 100)
            left -= amount
            pid = uid(f"plan:{self.key}:{seq}")
            self.plan.append(dict(id=pid, seq=seq, name=name, milestone=milestone, amount=amount, due_days=due_days,
                                  batch=batch))
            ins("ordering.contract_payment_installment", id=pid, contract_id=self.contract, seq=seq, name=name,
                milestone=milestone, percent=pct, amount=amount, due_days=due_days,
                delivery_batch_id=self.batches[batch - 1]["id"] if batch else None)
        commit()

    def send(self, when):
        doc = stored_file("documents", uid("contract-doc:" + self.key), "application/pdf", when,
                          f"Hop dong {self.contract_number}")
        upd("ordering.sales_contract", self.contract, status="SENT", sent_at=when, sent_by=U[self.sales],
            sent_document_key=doc["key"])
        self.contract_status = "SENT"
        self.move("PENDING_CONTRACT", when, U[self.sales], f"Đã gửi hợp đồng {self.contract_number}")

    def sign(self, when):
        scan = stored_file("documents", uid("contract-scan:" + self.key), "application/pdf", when,
                           f"Hop dong da ky {self.contract_number}")
        upd("ordering.sales_contract", self.contract, status="SIGNED", signed_on=when.date(),
            signed_file_key=scan["key"], signed_file_name=f"{self.contract_number}-da-ky.pdf", signed_at=when,
            signed_by=U[self.sales])
        self.contract_status = "SIGNED"
        self.signed_at = when
        pays_first = any(i["milestone"] == "ON_SIGNING" for i in self.plan)
        self.move("PENDING_PAYMENT" if pays_first else "CONFIRMED", when, U[self.sales],
                  f"Hợp đồng {self.contract_number} đã ký", contract_signed_at=when)

    def pay(self, seq, amount, when, reference, void=None):
        """A bank transfer recorded by the accountant against installment `seq` (15 §4.2)."""
        self.payments.append(dict(seq=seq, amount=amount, at=when, reference=reference, void=void))

    def confirm(self, when):
        self.move("CONFIRMED", when, U["ketoan"], "Đã nhận tiền đặt cọc")

    # ---------------------------------------------------------------- warehouse and production
    def ship_from(self, when, by="sales1"):
        upd("ordering.customer_order", self.id, shipping_warehouse_id=WID[self.p], shipping_warehouse_chosen_at=when,
            shipping_warehouse_chosen_by=U[by])
        commit()

    def production(self, key, seq, producer, when, path, sample=False, waiver=None, price=None):
        return ProductionOrder(self, key, seq, producer, when, path, sample, waiver, price)

    def hold(self, when):
        """Sales holds, at the shipping warehouse, the whole cartons each batch not released yet still lacks, as many
        as are free there (17 §4.2 step 3): one SKU reservation per batch and line."""
        for b in self.batches:
            if b["released"]:
                continue
            for ln, n in zip(self.lines, b["cartons"]):
                if not n:
                    continue
                held = sum(h["qty"] for h in ln["holds"] if h["batch"] == b["seq"])
                missing = n * ln["per"] - held
                free = sum(r["on_hand"] - r["reserved"] for r in STOCK.values()
                           if r["sku"] == ln["sku"] and r["wh"] == self.p and r["status"] == "AVAILABLE"
                           and not r["staged"])
                free -= sum(h["qty"] for o in ORDERS for line in o.lines for h in line["holds"]
                            if line["sku"] == ln["sku"] and h["status"] == "HELD")
                qty = min(missing, free // ln["per"] * ln["per"])
                if qty <= 0:
                    continue
                rid = uid(f"hold:{self.key}:{b['seq']}:{ln['id']}:{len(ln['holds'])}")
                ins("inventory.stock_reservation", id=rid, stock_item_id=None, order_id=self.id, root_request_id=rid,
                    request_id=rid, quantity=qty, reserved_at=when, expires_at=None, status="HELD", sku=ln["sku"],
                    warehouse_code=self.p)
                ins("ordering.order_line_reservation", order_line_id=ln["id"], reservation_id=rid)
                ins("ordering.delivery_batch_hold", when, reservation_id=rid, delivery_batch_id=b["id"],
                    order_id=self.id, order_line_id=ln["id"])
                ln["holds"].append(dict(id=rid, qty=qty, batch=b["seq"], status="HELD"))
        commit()

    def release(self, seq, when, by, staging=False):
        """The batch to the warehouse (17 §4.5): its holds become allocations of whole cartons, FIFO, and a pick task
        names each carton by LPN."""
        b = self.batches[seq - 1]
        manager = U[STAFF_OF[self.p][0]]
        if seq == 1:
            self.move("READY_TO_FULFILL", when, U[by], f"Xuất đợt 1 tại kho {self.p}", released_at=when,
                      released_by=U[by], warehouse_id=WID[self.p])
        ins("ordering.delivery_batch_release", delivery_batch_id=b["id"], order_id=self.id, seq=seq,
            warehouse_id=WID[self.p], released_at=when, released_by=U[by])
        b["released"] = when
        allocations = []
        for ln in self.lines:
            for h in [h for h in ln["holds"] if h["batch"] == seq and h["status"] == "HELD"]:
                rows = sorted((r for r in STOCK.values() if r["sku"] == ln["sku"] and r["wh"] == self.p
                               and r["status"] == "AVAILABLE" and r["reserved"] == 0 and not r["staged"]
                               and r["on_hand"] > 0), key=lambda r: (r["received"], r["lpn"]))
                need = h["qty"]
                for r in rows:
                    if need <= 0:
                        break
                    aid = uid(f"alloc:{h['id']}:{r['id']}")
                    ins("inventory.stock_reservation", id=aid, stock_item_id=r["id"], order_id=self.id,
                        root_request_id=h["id"], request_id=aid, quantity=r["on_hand"], reserved_at=when,
                        expires_at=None, status="HELD", sku=ln["sku"], allocated_from_id=h["id"])
                    upd("inventory.stock_item", r["id"], reserved=r["on_hand"])
                    r["reserved"] = r["on_hand"]
                    need -= r["on_hand"]
                    ins("ordering.delivery_batch_hold", when, reservation_id=aid, delivery_batch_id=b["id"],
                        order_id=self.id, order_line_id=ln["id"])
                    allocations.append(dict(id=aid, line=ln, row=r))
                    ln["allocations"].append(aid)
                assert need == 0, f"{self.key}: not enough stock to allocate {ln['sku']}"
                upd("inventory.stock_reservation", h["id"], status="ALLOCATED", closed_at=when)
                h["status"] = "ALLOCATED"
                raw(f"DELETE FROM ordering.order_line_reservation WHERE reservation_id = {q(h['id'])};")
            for aid in ln["allocations"]:
                if not any(a["id"] == aid for a in allocations):
                    continue
                ins("ordering.order_line_reservation", order_line_id=ln["id"], reservation_id=aid)
        commit()
        return Pick(self, b, allocations, when, manager, staging)

    # ---------------------------------------------------------------- money
    def settle_money(self):
        """The installments signing created, in the state the scenario left them (15 §5.1), and the receipts that
        paid them. Written last: an installment's state is what its receipts and milestones made it."""
        if not self.signed_at:
            return
        delivered = self.delivered_at
        for i in self.plan:
            paid = [x for x in self.payments if x["seq"] == i["seq"] and not x["void"]]
            paid_amount = sum(x["amount"] for x in paid)
            if i["milestone"] == "ON_SIGNING":
                reached = self.signed_at
            elif i["milestone"] == "BEFORE_DELIVERY":
                b = self.batches[(i["batch"] or 1) - 1]
                reached = b["released"]
            else:
                reached = delivered
            due = (reached.date() + timedelta(days=i["due_days"])) if reached and i["milestone"] != "BEFORE_DELIVERY" \
                else None
            if self.cancelled_at and paid_amount < i["amount"]:
                status = "CANCELLED"
            elif paid_amount >= i["amount"]:
                status = "PAID"
            # Overdue is a flag worked out from the due day, not a status (V20261121000100).
            elif paid_amount > 0:
                status = "PARTIALLY_PAID"
            elif reached:
                status = "DUE"
            else:
                status = "NOT_DUE"
            if status == "PAID":
                paid_at = max(x["at"] for x in paid)
            else:
                paid_at = None
            b = self.batches[i["batch"] - 1] if i["batch"] else None
            ins("payment.installment", self.signed_at, id=uid(f"inst:{self.key}:{i['seq']}"), contract_id=self.contract,
                contract_installment_id=i["id"], order_id=self.id, customer_id=self.c["id"],
                contract_number=self.contract_number, order_number=self.number, seq=i["seq"], name=i["name"],
                milestone=i["milestone"], delivery_batch_id=b["id"] if b else None,
                delivery_batch_seq=b["seq"] if b else None, amount=i["amount"], paid_amount=paid_amount,
                currency="VND", due_days=i["due_days"], reached_at=reached, due_date=due, status=status,
                paid_at=paid_at, cancelled_at=self.cancelled_at if status == "CANCELLED" else None)
        for k, x in enumerate(self.payments):
            rid = uid(f"receipt:{self.key}:{k}")
            ins("payment.payment_receipt", x["at"], id=rid, customer_id=self.c["id"], reference=x["reference"],
                received_on=x["at"].date(), amount=x["amount"], currency="VND",
                note=f"Chuyển khoản {self.contract_number} đợt {x['seq']}", status="RECORDED",
                recorded_by=U["ketoan"], recorded_at=x["at"])
            ins("payment.payment_receipt_line", x["at"], id=uid(f"receipt-line:{self.key}:{k}"), receipt_id=rid,
                installment_id=uid(f"inst:{self.key}:{x['seq']}"), amount=x["amount"])
            if x["void"]:
                upd("payment.payment_receipt", rid, status="VOIDED", voided_by=U["ketoan"],
                    voided_at=x["at"] + timedelta(hours=4), void_reason=x["void"])
        commit()


class ProductionOrder:
    """A production order for one delivery batch, at one producer (kltn-docs 17 §4.3, §5.3): drawn up as a DRAFT and
    moved only along ordering.production_order_status_transition."""

    def __init__(self, o, key, seq, producer, when, path, sample, waiver, price):
        self.o, self.key = o, key
        self.id = uid("po:" + key)
        self.code = next_no("PRD", when.date(), "PRD-{d}-{n:04d}")
        self.status = "DRAFT"
        self.batch = o.batches[seq - 1]
        self.history = 0
        self.rounds = 0
        ins("ordering.production_order", when, id=self.id, code=self.code, sales_order_id=o.id,
            delivery_batch_id=self.batch["id"], customer_id=o.c["id"], producer_id=PRODUCER[producer],
            receiving_warehouse_id=WID[o.p], need_by_date=self.batch["start"] - timedelta(days=3), status="DRAFT",
            sample_approval_required=sample, sample_required_by_default=True,
            sample_waiver_reason=None if sample else (waiver or "Mẫu in đã được khách duyệt ở đơn trước"),
            unit_price=price, currency="VND", note=f"Đợt {seq} của {o.contract_number}", drawn_up_by=U["dieuphoi"])
        self.lines = []
        for ln, n in zip(o.lines, self.batch["cartons"]):
            if not n:
                continue
            lid = uid(f"po-line:{key}:{ln['id']}")
            ins("ordering.production_order_line", id=lid, production_order_id=self.id, order_line_id=ln["id"],
                sku=ln["sku"], cartons=n, units_per_carton=ln["per"], design_snapshot_id=ln["s"]["snapshot"],
                print_file_checksum=ln["s"]["checksum"], packing_requirements="Đóng thùng carton 5 lớp, dán nhãn LPN")
            self.lines.append(dict(id=lid, line=ln, cartons=n, received=0))
        self.log(None, "DRAFT", when, U["dieuphoi"], "Lập đơn sản xuất")
        commit()
        steps = {"PENDING_APPROVAL": self.submit, "SENT": self.send, "ACCEPTED": self.accept,
                 "IN_PRODUCTION": self.start}
        t = when
        for step in path:
            t = t + timedelta(days=1)
            steps[step](t)

    def log(self, frm, to, when, by, reason=None):
        ins("ordering.production_order_status_history", id=uid(f"posh:{self.key}:{self.history}"),
            production_order_id=self.id, from_status=frm, to_status=to, reason=reason, changed_by=by,
            changed_at=when)
        self.history += 1

    def move(self, to, when, by, reason=None, **cols):
        upd("ordering.production_order", self.id, status=to, **cols)
        self.log(self.status, to, when, by, reason)
        self.status = to
        commit()

    def submit(self, when):
        self.move("PENDING_APPROVAL", when, U["dieuphoi"], "Gửi duyệt", submitted_at=when)

    def send(self, when):
        self.move("SENT", when, U["truongkd"], "Duyệt và gửi xưởng", approved_by=U["truongkd"], approved_at=when,
                  sent_at=when)
        slip = stored_file("documents", uid("slip:" + self.key), "application/pdf", when, f"Phieu san xuat {self.code}")
        ins("ordering.production_order_slip", when, production_order_id=self.id, storage_key=slip["key"],
            sha256=slip["checksum"], size_bytes=slip["size"])
        commit()

    def accept(self, when):
        self.move("ACCEPTED", when, U["dieuphoi"], "Xưởng nhận đơn",
                  expected_completion_date=self.batch["start"] - timedelta(days=4))

    def sample_round(self, when, reprint=None):
        """A round of print sample photos uploaded for the customer (17 §4.3 step 9, 13 BR-13)."""
        self.rounds += 1
        rid = uid(f"round:{self.key}:{self.rounds}")
        ins("ordering.production_order_sample_round", when, id=rid, production_order_id=self.id, round_no=self.rounds,
            reprint_reason=reprint, opened_by=U["dieuphoi"], opened_at=when)
        for k in range(2):
            mid = uid(f"media:{self.key}:{self.rounds}:{k}")
            f = stored_file("print-samples", mid, "image/png", when, f"Mau in {self.code}")
            ins("ordering.production_order_sample_media", id=mid, sample_round_id=rid, storage_key=f["key"],
                file_name=f"mau-in-{self.rounds}-{k + 1}.png", content_type="image/png", size_bytes=f["size"],
                uploaded_by=U["dieuphoi"], uploaded_at=when)
        if self.rounds == 1:
            self.move("SAMPLE_PENDING", when, U["dieuphoi"], "Gửi ảnh mẫu in cho khách")
        commit()
        return rid

    def answer(self, when, approved, note=None):
        upd("ordering.production_order_sample_round", uid(f"round:{self.key}:{self.rounds}"),
            result="APPROVED" if approved else "REPRINT_REQUESTED", customer_note=None if approved else note,
            answered_by=self.o.c["user"], answered_at=when)
        commit()

    def start(self, when):
        self.move("IN_PRODUCTION", when, U["dieuphoi"], "Xưởng bắt đầu in hàng loạt", production_started_at=when)
        if not getattr(self.o, "printing", None):
            upd("ordering.customer_order", self.o.id, printing_started_at=when)
            self.o.printing = when
        commit()

    def cancel(self, when, reason):
        self.move("CANCELLED", when, U["dieuphoi"], reason, cancelled_by=U["dieuphoi"], cancelled_at=when,
                  cancellation_reason=reason)

    def close(self, when, reason):
        self.move("CLOSED", when, U["dieuphoi"], reason, closed_by=U["dieuphoi"], closed_at=when,
                  closing_reason=reason)

    def receive(self, when, counts, put_away=None):
        """Printed goods delivered by the producer (kltn-docs 03 §4.1): a receipt against this production order, its
        cartons labelled while counted, confirmed into the receiving area (lot = this order's code), then put away
        carton by carton. `counts`: cartons per line of this order; `put_away`: how many cartons reach a bin."""
        o, p = self.o, self.o.p
        manager, staff = U[STAFF_OF[p][0]], U[STAFF_OF[p][1]]
        rcv = AREA[(p, "RECEIVING")]
        gid = uid(f"gr:{self.key}:{when.isoformat()}")
        number = next_no("GR", when.date(), "GR-{d}-{n:04d}")
        ins("procurement.goods_receipts", when, id=gid, receipt_number=number, warehouse_id=WID[p], status="DRAFT",
            received_at=when, received_by=staff, delivery_note=f"PGH-{self.code[4:]}", receipt_type="PRODUCTION",
            production_order_id=self.id, note=f"Nhận hàng in theo {self.code}")
        ins("ordering.production_order_receipt", production_order_id=self.id, goods_receipt_id=gid,
            receipt_number=number, status="DRAFT", opened_at=when)
        cartons = []
        for pl, n in zip(self.lines, counts):
            if not n:
                continue
            ln = pl["line"]
            rl = uid(f"gr-line:{gid}:{pl['id']}")
            item = Raw(f"(SELECT id FROM inventory.inventory_items WHERE sku = '{ln['sku']}')")
            ins("procurement.goods_receipt_lines", when, id=rl, receipt_id=gid, inventory_item_id=item,
                location_id=LOC[rcv], received_qty=n * ln["per"], lot_number=self.code,
                uom_conversion_id=ln["s"]["unit"], production_order_line_id=pl["id"])
            for _ in range(n):
                pkg = dict(id=uid(f"pkg:{rl}:{len(cartons)}"), lpn=lpn(when.date()), line=rl, ln=ln, pl=pl)
                ins("inventory.stock_package", when, id=pkg["id"], lpn=pkg["lpn"], inventory_item_id=item,
                    uom_conversion_id=ln["s"]["unit"], nominal_qty=ln["per"], received_qty=ln["per"],
                    location_code=rcv, status="RECEIVING", goods_receipt_line_id=rl)
                cartons.append(pkg)
            pl["received"] += n
        commit()
        confirmed = when + timedelta(hours=2)
        upd("procurement.goods_receipts", gid, status="CONFIRMED", confirmed_at=confirmed, confirmed_by=manager)
        upd("ordering.production_order_receipt", {"goods_receipt_id": gid}, status="CONFIRMED", settled_at=confirmed)
        for pl in self.lines:
            lot = uid(f"lot:{self.id}:{pl['line']['sku']}")
            if not pl.get("lot"):
                pl["lot"] = lot
                ins("inventory.stock_lot", confirmed, id=lot, sku=pl["line"]["sku"], lot_number=self.code,
                    received_at=when, origin_type="PRODUCTION_ORDER", warehouse_id=WID[p],
                    production_order_id=self.id, production_order_code=self.code)
        for pkg in cartons:
            ln = pkg["ln"]
            upd("inventory.stock_package", pkg["id"], status="IN_STOCK")
            row = dict(id=uid("stock:" + pkg["id"]), sku=ln["sku"], wh=p, loc=rcv, on_hand=ln["per"], reserved=0,
                       status="INBOUND", package=pkg["id"], lpn=pkg["lpn"], lot=self.code, lot_id=pkg["pl"]["lot"],
                       received=when, staged=False)
            STOCK[row["id"]] = row
            ins("inventory.stock_item", confirmed, id=row["id"], sku=ln["sku"], location_code=rcv,
                lot_number=self.code, on_hand=ln["per"], reserved=0, status="INBOUND", received_at=when,
                stock_package_id=pkg["id"], lot_id=row["lot_id"])
            ins("inventory.stock_movement", confirmed, id=uid("mv:receipt:" + pkg["id"]), movement_type="RECEIPT",
                sku=ln["sku"], lot_number=self.code, from_location_code=None, to_location_code=rcv,
                quantity=ln["per"], from_status=None, to_status="INBOUND", reference_type="GOODS_RECEIPT_LINE",
                reference_id=pkg["line"], actor_id=manager, occurred_at=confirmed, to_stock_package_id=pkg["id"],
                lot_id=row["lot_id"])
            pkg["row"] = row
        for pl in self.lines:
            upd("ordering.production_order_line", pl["id"], received_cartons=pl["received"])
        full = all(pl["received"] == pl["cartons"] for pl in self.lines)
        self.move("RECEIVED" if full else "PARTIALLY_RECEIVED", confirmed, manager, f"Nhận hàng phiếu {number}")
        # Putaway, carton by carton (06 §4.4): a MOVE out of the receiving area into a bin, INBOUND -> AVAILABLE.
        done = len(cartons) if put_away is None else put_away
        for k, pkg in enumerate(cartons):
            task = uid("putaway:" + pkg["id"])
            row = pkg["row"]
            if k < done:
                to = bin_for(p)
                t = confirmed + timedelta(minutes=20 + 5 * k)
                ins("warehouse.putaway_task", t, id=task, goods_receipt_id=gid, goods_receipt_line_id=pkg["line"],
                    stock_package_id=pkg["id"], sku=row["sku"], quantity=row["on_hand"], target_location_id=LOC[to],
                    status="DONE", source="RECEIPT")
                ins("inventory.stock_movement", t, id=uid("mv:putaway:" + pkg["id"]), movement_type="MOVE",
                    sku=row["sku"], lot_number=row["lot"], from_location_code=rcv, to_location_code=to,
                    quantity=row["on_hand"], from_status="INBOUND", to_status="AVAILABLE",
                    reference_type="PUTAWAY_TASK", reference_id=task, actor_id=staff, occurred_at=t,
                    from_stock_package_id=pkg["id"], to_stock_package_id=pkg["id"], lot_id=row["lot_id"])
                upd("inventory.stock_package", pkg["id"], location_code=to)
                upd("inventory.stock_item", row["id"], location_code=to, status="AVAILABLE")
                row.update(loc=to, status="AVAILABLE")
            else:
                ins("warehouse.putaway_task", confirmed, id=task, goods_receipt_id=gid,
                    goods_receipt_line_id=pkg["line"], stock_package_id=pkg["id"], sku=row["sku"],
                    quantity=row["on_hand"], target_location_id=LOC[bin_for(p)], status="PENDING", source="RECEIPT")
        upd("procurement.goods_receipts", gid, status="CLOSED" if done == len(cartons) else "IN_PUTAWAY",
            closed_at=confirmed + timedelta(hours=2) if done == len(cartons) else None)
        commit()
        return gid


class Pick:
    """A pick task of one released batch (07 §4.2): one line per allocated carton, named by its LPN."""

    def __init__(self, o, batch, allocations, when, manager, staging=False):
        self.o, self.batch = o, batch
        self.id = uid(f"pick:{o.key}:{batch['seq']}")
        self.status = "PENDING"
        self.picker = U[STAFF_OF[o.p][1 + batch["seq"] % 2]]
        self.lines = []
        self.staging = AREA[(o.p, "STAGING")] if staging else None
        ins("fulfillment.pick", when, id=self.id, order_id=o.id, status="PENDING", assigned_user_id=self.picker,
            assigned_at=when, order_number=o.number, warehouse_id=WID[o.p],
            destination="STAGING" if staging else "DISPATCH", staging_location_code=self.staging,
            delivery_batch_id=batch["id"])
        for n, a in enumerate(sorted(allocations, key=lambda a: a["row"]["loc"]), 1):
            lid = uid(f"pick-line:{self.id}:{n}")
            r = a["row"]
            ins("fulfillment.pick_line", when, id=lid, pick_id=self.id, order_line_id=a["line"]["id"],
                reservation_id=a["id"], sku=r["sku"], location_code=r["loc"], lot_number=r["lot"], sequence_no=n,
                allocated_qty=r["on_hand"], status="OPEN", lpn=r["lpn"], whole_carton=True)
            self.lines.append(dict(id=lid, alloc=a, row=r, line=a["line"], status="OPEN", n=n))
        o.picks.append(self)
        commit()

    def start(self, when):
        upd("fulfillment.pick", self.id, status="PICKING", started_at=when)
        self.status = "PICKING"
        if self.o.status == "READY_TO_FULFILL":
            self.o.move("PICKING", when, self.picker, "Bắt đầu lấy hàng", fulfilment_started_at=when)
        commit()

    def _deduct(self, pl, when, frm):
        r, a = pl["row"], pl["alloc"]
        ins("inventory.stock_movement", when, id=uid("mv:pick:" + pl["id"]), movement_type="PICK", sku=r["sku"],
            lot_number=r["lot"], from_location_code=frm, to_location_code=None, quantity=r["on_hand"],
            from_status="AVAILABLE", to_status=None, reference_type="PICK_LINE", reference_id=pl["id"],
            actor_id=self.picker, occurred_at=when, from_stock_package_id=r["package"], lot_id=r["lot_id"],
            order_id=self.o.id)
        upd("inventory.stock_reservation", a["id"], status="CONSUMED", closed_at=when)
        upd("inventory.stock_item", r["id"], on_hand=0, reserved=0)
        upd("inventory.stock_package", r["package"], status="CONSUMED", closed_at=when)
        r.update(on_hand=0, reserved=0, consumed=True)

    def pick(self, when, count=None):
        """The picker scans each carton's LPN (07 BR-04): to the staging area (MOVE) or off stock to dispatch (PICK)."""
        todo = [pl for pl in self.lines if pl["status"] == "OPEN"][:count]
        for k, pl in enumerate(todo):
            t = when + timedelta(minutes=3 * k)
            r = pl["row"]
            scanned = dict(picked_qty=r["on_hand"], picked_by=self.picker, picked_at=t, scanned_location_code=r["loc"],
                           scanned_code=r["lpn"])
            if self.staging:
                ins("inventory.stock_movement", t, id=uid("mv:stage:" + pl["id"]), movement_type="MOVE",
                    sku=r["sku"], lot_number=r["lot"], from_location_code=r["loc"], to_location_code=self.staging,
                    quantity=r["on_hand"], from_status="AVAILABLE", to_status="AVAILABLE", reference_type="PICK_LINE",
                    reference_id=pl["id"], actor_id=self.picker, occurred_at=t, from_stock_package_id=r["package"],
                    to_stock_package_id=r["package"], lot_id=r["lot_id"], order_id=self.o.id)
                upd("inventory.stock_package", r["package"], location_code=self.staging)
                upd("inventory.stock_item", r["id"], location_code=self.staging)
                r.update(loc=self.staging, staged=True)
                upd("fulfillment.pick_line", pl["id"], status="STAGED", staged_at=t,
                    staged_location_code=self.staging, **scanned)
                pl["status"] = "STAGED"
            else:
                self._deduct(pl, t, r["loc"])
                upd("fulfillment.pick_line", pl["id"], status="PICKED", consumed_at=t, **scanned)
                pl["status"] = "PICKED"
        commit()

    def staged(self, when):
        upd("fulfillment.pick", self.id, status="STAGED", staged_at=when)
        self.status = "STAGED"
        commit()

    def to_dispatch(self, when):
        """The manager moves a staged task to dispatch (07 §4.5): every carton leaves stock there."""
        manager = U[STAFF_OF[self.o.p][0]]
        for pl in self.lines:
            if pl["status"] == "STAGED":
                self._deduct(pl, when, self.staging)
                upd("fulfillment.pick_line", pl["id"], status="PICKED", consumed_at=when)
                pl["status"] = "PICKED"
        upd("fulfillment.pick", self.id, status="PICKED", picked_at=when, moved_to_dispatch_at=when,
            moved_to_dispatch_by=manager)
        self.status = "PICKED"
        commit()

    def done(self, when):
        upd("fulfillment.pick", self.id, status="PICKED", picked_at=when)
        self.status = "PICKED"
        commit()

    def check(self, when):
        """The carton check station (08 §4.2, team plan 4C-6): each picked carton is scanned, checked, weighed,
        measured and labelled; the session completes into a shipment waiting for dispatch."""
        o = self.o
        checker = U[STAFF_OF[o.p][2 - self.batch["seq"] % 2]]
        pack = uid("pack:" + self.id)
        ins("fulfillment.pack", when, id=pack, pick_id=self.id, status="IN_PROGRESS", started_at=when,
            started_by=checker)
        ship = uid("shipment:" + self.id)
        code = f"{o.number}-S{len(o.shipments) + 1}"
        packages = []
        for k, pl in enumerate(self.lines):
            pid = uid("package:" + pl["id"])
            s = pl["line"]["s"]
            ins("fulfillment.package", when, id=pid, pack_id=pack, package_no=k + 1, label_code=f"{code}-C{k + 1}",
                status="TO_CHECK", pick_line_id=pl["id"], lpn=pl["row"]["lpn"], rotatable=True)
            t = when + timedelta(minutes=2 * k + 1)
            upd("fulfillment.package", pid, status="CHECKED", checked_at=t, checked_by=checker, weight_kg=s["weight"],
                length_cm=60, width_cm=40, height_cm=50, dim_weight_kg=20)
            packages.append(dict(id=pid, at=t, weight=s["weight"], line=pl["line"], qty=s["per"], lpn=pl["row"]["lpn"],
                                 package=pl["row"]["package"], lot=pl["row"]["lot"], row=pl["row"], pick_line=pl["id"]))
        if not getattr(o, "labeled", None):
            upd("ordering.customer_order", o.id, first_labeled_at=when + timedelta(minutes=1))
            o.labeled = when
        done = when + timedelta(minutes=2 * len(self.lines) + 5)
        ins("fulfillment.shipment", done, id=ship, pack_id=pack, order_id=o.id, status="READY_TO_DISPATCH", code=code,
            request_id=uid("shipment-req:" + self.id), warehouse_id=WID[o.p], package_count=len(packages),
            ready_at=done, ready_by=checker)
        for k, pk in enumerate(packages):
            upd("fulfillment.package", pk["id"], status="LABELED", labeled_at=pk["at"] + timedelta(seconds=30),
                labeled_by=checker, label_version=1, shipment_id=ship)
        upd("fulfillment.pack", pack, status="COMPLETED", packed_at=done, packed_by=checker)
        if o.status == "PICKING":
            o.move("PACKED", done, checker, f"Đã kiểm và dán nhãn {len(packages)} thùng", packed_at=done)
        sh = dict(id=ship, code=code, order=o, pick=self, packages=packages, batch=self.batch, ready=done,
                  status="READY_TO_DISPATCH")
        commit()
        o.shipments.append(sh)
        return sh


# ============================================================================ 9. loads and delivery
VEHICLE = {  # preset vehicle types: kind, inner length, width, height (mm), payload (kg)
    "TRUCK-1T25": ("TRUCK", 3100, 1600, 1600, 1250), "TRUCK-2T5": ("TRUCK", 4300, 1800, 1800, 2500),
}
TRIPS = []


class Trip:
    """A full-truckload trip (kltn-docs 09): a load plan with one vehicle for the shipments, the load (manifest) booked
    with a carrier, loaded carton by carton and handed over with a waybill number."""

    def __init__(self, key, p, shipments, when, carrier, vehicle="TRUCK-2T5", plan_status="CONFIRMED"):
        self.key, self.p, self.shipments, self.carrier = key, p, shipments, carrier
        manager = U[STAFF_OF[p][0]]
        self.manager, self.staff = manager, U[STAFF_OF[p][1]]
        self.plan = uid("plan:" + key)
        self.plan_code = next_no("LP", when.date(), "LP-{d}-{n:04d}")
        self.waybill = None
        kind, length, width, height, payload = VEHICLE[vehicle]
        vt = Raw(f"(SELECT id FROM fulfillment.vehicle_type WHERE code = '{vehicle}')")
        packages = [pk for sh in shipments for pk in sh["packages"]]
        weight = round(sum(pk["weight"] for pk in packages), 3)
        volume = len(packages) * 600 * 400 * 500
        ins("fulfillment.load_plan", when, id=self.plan, code=self.plan_code, request_id=uid("plan-req:" + key),
            warehouse_id=WID[p], source_type="ORDER", selection_mode="SINGLE_TYPE", status="PLANNED",
            calculated_at=when, calculated_by=manager,
            solver_meta={"algorithm": "extreme-points", "elapsedMs": 140, "budgetExhausted": False},
            revised_at=when)
        ins("fulfillment.load_plan_vehicle_type", when, id=uid("plan-vt:" + key), load_plan_id=self.plan,
            vehicle_type_id=vt, sort_order=0)
        self.vehicle = uid("plan-vehicle:" + key)
        ins("fulfillment.load_plan_vehicle", when, id=self.vehicle, load_plan_id=self.plan, seq=1, active=True,
            vehicle_type_id=vt, vehicle_code=vehicle, vehicle_kind=kind, inner_length_mm=length,
            inner_width_mm=width, inner_height_mm=height, max_payload_kg=payload, gusset_mm=0,
            used_volume_mm3=volume, used_weight_kg=weight,
            volume_fill_pct=round(volume * 100 / (length * width * height), 2),
            weight_fill_pct=round(weight * 100 / payload, 2))
        for i, pk in enumerate(packages):
            sh = next(s for s in shipments if pk in s["packages"])
            ins("fulfillment.load_plan_package", when, id=uid(f"plan-pkg:{key}:{pk['id']}"), load_plan_id=self.plan,
                package_id=pk["id"], shipment_id=sh["id"], active=True, added_at=when, added_by=manager)
            ins("fulfillment.load_placement", when, id=uid(f"placement:{key}:{pk['id']}"), load_plan_id=self.plan,
                load_plan_vehicle_id=self.vehicle, package_id=pk["id"], x_mm=(i % 3) * 600, y_mm=(i // 3 % 3) * 500,
                z_mm=(i // 9) * 400, w_mm=600, h_mm=500, d_mm=400, orientation=0)
        self.plan_status = "PLANNED"
        if plan_status in ("CONFIRMED", "DISPATCHED"):
            upd("fulfillment.load_plan", self.plan, status="CONFIRMED", confirmed_at=when + timedelta(minutes=30),
                confirmed_by=manager)
            self.plan_status = "CONFIRMED"
        self.packages = packages
        self.load = None
        TRIPS.append(self)
        commit()

    def open_load(self, when):
        self.load = uid("load:" + self.key)
        self.code = next_no("LD", when.date(), "LD-{d}-{n:04d}")
        ins("fulfillment.dispatch_load", when, id=self.load, code=self.code, request_id=uid("load-req:" + self.key),
            warehouse_id=WID[self.p], carrier_id=CARRIER[self.carrier], load_plan_vehicle_id=self.vehicle,
            status="PLANNED")
        for sh in self.shipments:
            ins("fulfillment.dispatch_load_shipment", when, id=uid(f"load-ship:{self.key}:{sh['id']}"),
                load_id=self.load, shipment_id=sh["id"], active=True, added_at=when, added_by=self.manager)
        self.status = "PLANNED"
        commit()

    def book(self, when, plate, driver, phone):
        upd("fulfillment.dispatch_load", self.load, status="VEHICLE_BOOKED", booked_at=when, booked_by=self.manager,
            vehicle_plate=plate, driver_name=driver, driver_phone=phone)
        self.status = "VEHICLE_BOOKED"
        commit()

    def ahamove_booking(self, when, order_no, failed_first=False):
        """The vehicle booked through Ahamove's API (09 §4.2 step 6): the booking, and the calls it took. With Ahamove
        loaded MANUAL the manager booked it in Ahamove's app and typed its order number as the waybill."""
        api_or_manual(lambda: self._ahamove_api_booking(when, order_no, failed_first),
                      lambda: ins("fulfillment.waybill_change", when, id=uid(f"waybill:{self.key}"), load_id=self.load,
                                  old_waybill_no=None, new_waybill_no=order_no, source="MANUAL",
                                  changed_by=self.manager, changed_at=when + timedelta(seconds=2)))
        self.waybill = order_no
        upd("fulfillment.dispatch_load", self.load, waybill_no=order_no)
        commit()

    def _ahamove_api_booking(self, when, order_no, failed_first):
        o = self.shipments[0]["order"]
        stops = [{"address": WH[self.p][1], "name": f"Kho {self.p}", "mobile": "0909000111"},
                 {"address": o.c["addr"], "name": o.c["contact"], "mobile": o.c["phone"]}]
        service = ("SGN" if self.p == "BDG" else "HAN") + "-TRUCK-2000"
        calls = [("TOKEN", "OK", 200, None, None, None, 180, when)]
        if failed_first:
            fid = uid(f"booking:{self.key}:failed")
            ins("fulfillment.carrier_booking", when - timedelta(minutes=20), id=fid, load_id=self.load,
                carrier_id=CARRIER["AHAMOVE"], service_code=service, stops=stops, order_time=when, status="FAILED",
                requested_by=self.manager, requested_at=when - timedelta(minutes=20),
                last_error="Ahamove trả lời 400: NOT_ENOUGH_BALANCE")
            calls.append(("BOOK", "REFUSED", 400, json.dumps({"service_id": service, "path": stops},
                                                                ensure_ascii=False),
                          '{"title":"NOT_ENOUGH_BALANCE","description":"So du khong du"}', fid, 620,
                          when - timedelta(minutes=20)))
        bid = uid(f"booking:{self.key}")
        ins("fulfillment.carrier_booking", when, id=bid, load_id=self.load, carrier_id=CARRIER["AHAMOVE"],
            service_code=service, stops=stops, order_time=when, status="BOOKED", carrier_order_id=order_no,
            tracking_link=f"https://cloudstg.ahamove.com/share-order/{order_no}", requested_by=self.manager,
            requested_at=when, booked_at=when + timedelta(seconds=2))
        calls.append(("BOOK", "OK", 200, json.dumps({"service_id": service, "path": stops}, ensure_ascii=False),
                      json.dumps({"order_id": order_no, "status": "ASSIGNING"}), bid, 840, when))
        for k, (op, outcome, http, req, resp, booking, ms, t) in enumerate(calls):
            ins("fulfillment.carrier_api_call", t, id=uid(f"api:{self.key}:{k}"), carrier_id=CARRIER["AHAMOVE"],
                booking_id=booking, operation=op, outcome=outcome, http_status=http, request_body=req,
                response_body=resp, error=resp if outcome == "REFUSED" else None, duration_ms=ms, called_at=t)
        ins("fulfillment.waybill_change", when, id=uid(f"waybill:{self.key}"), load_id=self.load, old_waybill_no=None,
            new_waybill_no=order_no, source="API", changed_by=None, changed_at=when + timedelta(seconds=2))

    def load_cartons(self, when, count=None):
        upd("fulfillment.dispatch_load", self.load, status="LOADING", loading_started_at=when,
            loading_started_by=self.staff)
        self.status = "LOADING"
        for k, pk in enumerate(self.packages[:count]):
            sh = next(s for s in self.shipments if pk in s["packages"])
            ins("fulfillment.dispatch_load_package", when, id=uid(f"load-pkg:{self.key}:{pk['id']}"),
                load_id=self.load, package_id=pk["id"], shipment_id=sh["id"], scan_id=uid(f"scan:{pk['id']}"),
                loaded_at=when + timedelta(minutes=k), loaded_by=self.staff, active=True)
        commit()

    def dispatch(self, when, waybill=None, representative="Tài xế"):
        """Handed over (09 §4.2 step 10): the load leaves with its waybill, every shipment HANDED_OVER, the order
        SHIPPED and its ordering-side shipment recorded against the delivery batch."""
        if waybill:
            ins("fulfillment.waybill_change", when, id=uid(f"waybill:{self.key}:manual"), load_id=self.load,
                old_waybill_no=None, new_waybill_no=waybill, source="MANUAL", changed_by=self.manager,
                changed_at=when - timedelta(minutes=10))
            self.waybill = waybill
        upd("fulfillment.dispatch_load", self.load, status="DISPATCHED", dispatched_at=when, dispatched_by=self.manager,
            carrier_representative=representative, signature_ref=f"sign-{self.key}", shipment_count=len(self.shipments),
            package_count=len(self.packages), waybill_no=self.waybill)
        self.status = "DISPATCHED"
        carrier_name = {"XT-NAMBAC": "Vận tải Nam Bắc", "AHAMOVE": "Ahamove"}[self.carrier]
        for sh in self.shipments:
            o = sh["order"]
            upd("fulfillment.shipment", sh["id"], status="HANDED_OVER", handed_over_at=when,
                handed_over_by=self.manager,
                load_id=self.load, carrier_id=CARRIER[self.carrier], carrier=carrier_name,
                tracking_number=self.waybill, dispatched_at=when)
            sh["status"] = "HANDED_OVER"
            for k, (status, t) in enumerate((("READY_TO_DISPATCH", sh["ready"]), ("HANDED_OVER", when))):
                ins("fulfillment.shipment_event", t, id=uid(f"sev:{sh['id']}:{k}"), shipment_id=sh["id"],
                    status=status, occurred_at=t, source="MANUAL", recorded_by=self.manager)
            sh["events"] = 2
            per_line = {}
            for pk in sh["packages"]:
                per_line[pk["line"]["id"]] = per_line.get(pk["line"]["id"], 0) + pk["qty"]
            amount = 0
            for ln in o.lines:
                if ln["id"] in per_line:
                    net = money(per_line[ln["id"]] * ln["price"])
                    amount += net + tax_of(net, ln["s"]["rate"])
            sh["order_shipment"] = sh["id"]
            ins("ordering.order_shipment", when, id=sh["order_shipment"], order_id=o.id, status="HANDED_OVER",
                amount=amount, handed_over_at=when, handed_over_by=self.manager, shipment_code=sh["code"],
                package_count=len(sh["packages"]), carrier_name=carrier_name, delivery_batch_id=sh["batch"]["id"])
            for lid, qty in per_line.items():
                ins("ordering.order_shipment_line", shipment_id=sh["order_shipment"], order_line_id=lid, quantity=qty)
            if o.status == "PACKED":
                o.move("SHIPPED", when, self.manager, f"Bàn giao chuyến {self.code}", shipped_at=when,
                       goods_left_at=when)
        upd("fulfillment.load_plan", self.plan, status="DISPATCHED", dispatched_at=when)
        commit()

    def milestone(self, kind, when, event=None):
        def write(event):
            ins("fulfillment.tracking_milestone", when, id=uid(f"milestone:{self.key}:{kind}"), load_id=self.load,
                milestone=kind, carrier_reported_at=when, source="CARRIER_WEBHOOK" if event else "MANUAL",
                carrier_event_id=event, recorded_at=when + timedelta(seconds=3))
        if event:
            api_or_manual(lambda: write(event), lambda: write(None))
        else:
            write(None)
        commit()


EVENTS = [0]


def carrier_event(trip, kind, when, result="APPLIED", shipment=None, note=None, reference=None, signature=True,
                  resolved=None, dedupe=None):
    """What Ahamove's webhook sent, kept as it came, and what was decided about it (09 §4.4, BR-27)."""
    EVENTS[0] += 1
    eid = uid(f"cevent:{EVENTS[0]}")
    ref = reference or trip.waybill
    payload = json.dumps({"_id": ref, "status": {"PICKED_UP": "IN PROCESS", "IN_TRANSIT": "IN PROCESS",
                                                 "DELIVERED": "COMPLETED", "DELIVERY_FAILED": "FAILED"}.get(kind,
                                                                                                         "ASSIGNING"),
                          "sub_status": kind, "accept_time": int(when.timestamp())})
    matched = trip is not None and result in ("APPLIED", "DUPLICATE", "STALE", "NEEDS_REVIEW")
    api_or_manual(lambda: ins("fulfillment.carrier_event", when, id=eid, carrier_id=CARRIER["AHAMOVE"], carrier_code="AHAMOVE",
        received_at=when + timedelta(seconds=4), payload=payload, signature_valid=signature,
        event_kind=kind if signature else None, carrier_reference=ref, carrier_reported_at=when,
        dedupe_key=dedupe or f"{ref}:{kind}", warehouse_id=WID[trip.p] if matched else None,
        load_id=trip.load if matched else None, shipment_id=shipment["id"] if shipment and matched else None,
        result=result, result_note=note, decided_at=None if result == "RECEIVED" else when + timedelta(seconds=5),
        resolved_by=U[resolved[0]] if resolved else None, resolved_at=resolved[1] if resolved else None,
        resolution_note=resolved[2] if resolved else None))
    commit()
    return eid


def deliver(sh, when, by, failure=None, event=None):
    """The carrier's outcome for a shipment (09 §4.4): DELIVERED, or DELIVERY_FAILED with its reason."""
    o = sh["order"]
    status = "DELIVERY_FAILED" if failure else "DELIVERED"
    upd("fulfillment.shipment", sh["id"], status=status, outcome_at=when, outcome_by=by, failure_reason=failure,
        failure_note="Khách vắng nhà, hẹn giao lại" if failure else None)
    def write(event):
        ins("fulfillment.shipment_event", when, id=uid(f"sev:{sh['id']}:{sh['events']}"), shipment_id=sh["id"],
            status=status, occurred_at=when, source="CARRIER_WEBHOOK" if event else "MANUAL", carrier_event_id=event,
            recorded_by=None if event else by, note="Khách vắng nhà" if failure else "Đã giao, khách ký nhận")
    if event:
        api_or_manual(lambda: write(event), lambda: write(None))
    else:
        write(None)
    sh["events"] += 1
    upd("ordering.order_shipment", sh["order_shipment"], status=status, outcome_at=when, outcome_by=by,
        failure_reason=failure, failure_note="Khách vắng nhà" if failure else None)
    sh["status"] = status
    if failure:
        o.move("DELIVERY_FAILED", when, by, "Giao không thành công", delivery_failure_reason=failure)
    else:
        o.delivered_at = when
        o.move("DELIVERED", when, by, "Khách đã nhận hàng", delivered_at=when)


def complete(o, when):
    upd("ordering.sales_contract", o.contract, status="COMPLETED", completed_at=when)
    o.move("COMPLETED", when, U[o.sales], "Hoàn tất đơn hàng", completed_at=when)


def full_cycle(o, received, released, picked, checked, trip_key, dispatched, carrier="XT-NAMBAC", plate="29C-123.45",
               driver="Nguyễn Văn Tài", staging=False, ahamove=None):
    """Batch 1 of `o` from production to the truck: produced, received, held, released, picked, checked, loaded and
    handed over. Returns the trip."""
    po = o.production(o.key + ":1", 1, "INHN" if o.p == "HAN" else "INSG", received - timedelta(days=12),
                      ["PENDING_APPROVAL", "SENT", "IN_PRODUCTION"])
    if o.status == "CONFIRMED":
        o.move("AWAITING_STOCK", received - timedelta(days=9), U[o.sales], "Chờ hàng từ xưởng in")
    po.receive(received, [pl["cartons"] for pl in po.lines])
    o.hold(received + timedelta(hours=4))
    pick = o.release(1, released, STAFF_OF[o.p][0], staging=staging)
    pick.start(picked)
    pick.pick(picked)
    if staging:
        pick.staged(picked + timedelta(hours=1))
        pick.to_dispatch(picked + timedelta(hours=3))
    else:
        pick.done(picked + timedelta(hours=1))
    sh = pick.check(checked)
    trip = Trip(trip_key, o.p, [sh], checked + timedelta(hours=1), carrier)
    trip.open_load(checked + timedelta(hours=2))
    if ahamove:
        trip.ahamove_booking(checked + timedelta(hours=2, minutes=10), ahamove)
    trip.book(checked + timedelta(hours=3), plate, driver, "0912345678")
    trip.load_cartons(dispatched - timedelta(hours=1))
    trip.dispatch(dispatched, waybill=None if ahamove else f"NB-{dispatched:%m%d}-{len(TRIPS):03d}",
                  representative=driver)
    return trip


def T(m, d, h=9, mi=0):
    return datetime(2026, m, d, h, mi, tzinfo=VN)


# ============================================================================ 10. scenarios
section("8. quotes that did not become orders")
quote("q:caphemoc:draft", "caphemoc", [(CPM_PET16, 10, 1100)], T(11, 9, 10), "DRAFT")
quote("q:trasuamay:sent", "trasuamay", [(TSM_TUI, 15, 2900), (TSM_PET22, 20, 1350)], T(11, 7, 14), "SENT")
quote("q:banhmiviet:changes", "banhmiviet", [(BMV_HOP, 12, 2300)], T(11, 4, 9), "CHANGES_REQUESTED", sales="sales2",
      feedback="Xin giảm giá còn 2.100đ/hộp nếu lấy 20 thùng")
quote("q:kemtuoi:accepted", "kemtuoi", [(KTH_LY8, 20, 370)], T(11, 8, 11), "ACCEPTED")
quote("q:nuocepxanh:expired", "nuocepxanh", [(NEX_PET16, 15, 1150)], T(9, 20, 10), "EXPIRED")
quote("q:comtamsg:cancelled", "comtamsg", [(CTS_HOP, 5, 2200)], T(10, 12, 10), "CANCELLED", sales="sales2")

section("9. orders: completed, delivered, failed, returned (oldest first)")
# ---- delivered and paid, contract completed (September)
o = Order("S14", "kemtuoi", [(KTH_LY8, 10, 380)], T(9, 2, 10), "HAN")
o.draw_up(T(9, 2, 15), [(date(2026, 9, 25), date(2026, 9, 30), [10])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])
o.send(T(9, 3, 9))
o.sign(T(9, 4, 16))
o.pay(1, o.plan[0]["amount"], T(9, 5, 10), "FT26248KEM0001")
o.confirm(T(9, 5, 10, 30))
o.ship_from(T(9, 5, 11))
trip = full_cycle(o, T(9, 22, 8), T(9, 25, 8), T(9, 25, 9), T(9, 25, 14), "trip:S14", T(9, 26, 8))
trip.milestone("PICKED_UP", T(9, 26, 8, 30))
deliver(o.shipments[0], T(9, 26, 15), U["quanly.hn"])
o.pay(2, o.plan[1]["amount"], T(10, 20, 10), "FT26293KEM0002")
complete(o, T(10, 20, 11))

# ---- delivered on credit, the payment after delivery overdue; one receipt voided, one partial
o = Order("S18", "nuocepxanh", [(NEX_PET16, 8, 1150)], T(9, 3, 10), "BDG", sales="sales2")
o.draw_up(T(9, 3, 15), [(date(2026, 9, 18), date(2026, 9, 22), [8])],
          [("Đặt cọc khi ký", "ON_SIGNING", 40, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 60, 20, 1)])
o.send(T(9, 4, 9))
o.sign(T(9, 5, 15))
o.pay(1, o.plan[0]["amount"], T(9, 6, 10), "FT26249NEX0001", void="Nhập nhầm số tiền, sao kê ghi khác")
o.pay(1, o.plan[0]["amount"], T(9, 6, 15), "FT26249NEX0001B")
o.confirm(T(9, 6, 15, 30))
o.ship_from(T(9, 6, 16), "sales2")
trip = full_cycle(o, T(9, 16, 8), T(9, 18, 8), T(9, 18, 9), T(9, 18, 14), "trip:S18", T(9, 19, 8))
deliver(o.shipments[0], T(9, 19, 16), U["quanly.bd"])
o.pay(2, 1_500_000, T(10, 25, 10), "FT26298NEX0002")

# ---- delivered; part of it comes back for a print defect, the cartons into quarantine
o = Order("S16", "comtamsg", [(CTS_HOP, 6, 2200)], T(9, 25, 10), "BDG", sales="sales2")
o.draw_up(T(9, 25, 15), [(date(2026, 10, 15), date(2026, 10, 18), [6])],
          [("Thanh toán khi ký", "ON_SIGNING", 50, 7, None), ("Thanh toán trước giao", "BEFORE_DELIVERY", 50, None, 1)])
o.send(T(9, 26, 9))
o.sign(T(9, 27, 15))
o.pay(1, o.plan[0]["amount"], T(9, 28, 10), "FT26271CTS0001")
o.confirm(T(9, 28, 10, 30))
o.ship_from(T(9, 28, 11), "sales2")
trip = full_cycle(o, T(10, 13, 8), T(10, 15, 8), T(10, 15, 9), T(10, 15, 14), "trip:S16", T(10, 16, 8))
o.pay(2, o.plan[1]["amount"], T(10, 15, 7), "FT26288CTS0002")
deliver(o.shipments[0], T(10, 16, 15), U["quanly.bd"])
RETURNED = o

def return_request(o, key, when, reason, status, cartons=0, channel="PORTAL", note=None):
    """A customer's return (kltn-docs 16): REQUESTED, REJECTED, or approved and its cartons received back on a RETURN
    receipt into the warehouse's QUARANTINE area (03 §4.2, BR-14), where they wait for a decision."""
    rid = uid("return:" + key)
    number = next_no("RMA", when.date(), "RMA-{d}-{n:04d}")
    ln = o.lines[0]
    sh = o.shipments[0]
    back = sh["packages"][:cartons]
    units = sum(pk["qty"] for pk in back) or ln["per"]
    requester = o.c["user"] if channel == "PORTAL" else U["sales2"]
    decided = when + timedelta(days=1)
    approved = status not in ("REQUESTED", "REJECTED")
    ins("ordering.return_request", when, id=rid, rma_number=number, order_id=o.id, customer_id=o.c["id"],
        status="AWAITING_RETURN" if approved else status, reason_code=reason, note=note, requested_at=when,
        decided_by=U["truongkd"] if status != "REQUESTED" else None,
        decided_at=decided if status != "REQUESTED" else None,
        rejection_reason="Lỗi do khách bảo quản, ngoài phạm vi đổi trả" if status == "REJECTED" else None,
        order_number=o.number, request_id=uid("return-req:" + key), channel=channel, requested_by=requester,
        requested_resolution="REFUND", currency="VND",
        decision_note="Đồng ý nhận lại thùng lỗi in" if approved else None,
        return_required=True if approved else (False if status == "REJECTED" else None),
        return_deadline=decided + timedelta(days=7) if approved else None,
        closed_at=decided if status == "REJECTED" else None)
    lid = uid(f"return-line:{key}")
    ins("ordering.return_request_line", id=lid, return_request_id=rid, order_line_id=ln["id"], quantity=units,
        sku=ln["sku"], custom_printed=True, unit_price=ln["price"], approved_quantity=units if approved else None)
    ev = stored_file("return-evidence", uid("return-evidence:" + key), "image/png", when, "Anh loi in")
    ins("ordering.return_request_evidence", when, id=uid("return-evidence:" + key), return_request_id=rid,
        stage="CLAIM", file_key=ev["key"], original_name="anh-loi-in.png", content_type="image/png",
        size_bytes=ev["size"], uploaded_by=requester, uploaded_at=when)
    hist = [(None, "REQUESTED", when, requester, note)]
    if status != "REQUESTED":
        hist.append(("REQUESTED", "AWAITING_RETURN" if approved else status, decided, U["truongkd"], None))
    o.move("RETURN_REQUESTED", when, requester, f"Yêu cầu trả hàng {number}")
    if status == "REJECTED":
        o.move("DELIVERED", decided, U["truongkd"], f"Từ chối yêu cầu trả hàng {number}")
    commit()
    if status == "RECEIVED":
        p = o.p
        qrt = AREA[(p, "QUARANTINE")]
        staff = U[STAFF_OF[p][1]]
        t = decided + timedelta(days=3)
        gid = uid("gr-return:" + key)
        gnumber = next_no("GR", t.date(), "GR-{d}-{n:04d}")
        ins("procurement.goods_receipts", t, id=gid, receipt_number=gnumber, warehouse_id=WID[p], status="DRAFT",
            received_at=t, received_by=staff, receipt_type="RETURN", customer_order_id=o.id, return_request_id=rid,
            quarantine_location_id=LOC[qrt], note=f"Hàng hoàn theo {number}")
        for pk in back:
            ins("procurement.goods_receipt_return_cartons", t, id=uid(f"gr-return-carton:{gid}:{pk['id']}"),
                receipt_id=gid, stock_package_id=pk["package"], lpn=pk["lpn"], sku=ln["sku"], lot_number=pk["lot"],
                quantity=pk["qty"], condition_note="Lỗi lệch màu in trên thân hộp", scanned_by=staff)
        commit()
        for pk in back:
            r = pk["row"]
            raw(f"SELECT set_config('stockflow.same_receipt_merge', '{r['id']}', true);")
            upd("inventory.stock_package", pk["package"], status="IN_STOCK", location_code=qrt, closed_at=None)
            upd("inventory.stock_item", r["id"], location_code=qrt, on_hand=pk["qty"], status="QUARANTINE")
            r.update(loc=qrt, on_hand=pk["qty"], status="QUARANTINE", consumed=False)
            ins("inventory.stock_movement", t, id=uid(f"mv:return:{gid}:{pk['id']}"), movement_type="RECEIPT",
                sku=ln["sku"], lot_number=pk["lot"], from_location_code=None, to_location_code=qrt,
                quantity=pk["qty"], from_status="QUARANTINE", to_status="QUARANTINE", reference_type="GOODS_RECEIPT",
                reference_id=gid, actor_id=staff, occurred_at=t, to_stock_package_id=pk["package"],
                lot_id=r["lot_id"], order_id=o.id)
        raw("SELECT set_config('stockflow.same_receipt_merge', '', true);")
        upd("procurement.goods_receipts", gid, status="CLOSED", confirmed_at=t + timedelta(hours=1),
            confirmed_by=U[STAFF_OF[p][0]], closed_at=t + timedelta(hours=1))
        commit()
        upd("ordering.return_request", rid, status="RECEIVED", goods_receipt_id=gid, received_at=t + timedelta(hours=1),
            received_by=staff, receiving_location_code=qrt)
        upd("ordering.return_request_line", lid, received_quantity=units)
        hist.append(("AWAITING_RETURN", "RECEIVED", t + timedelta(hours=1), staff, f"Nhận lại {len(back)} thùng"))
        o.move("RETURNED", t + timedelta(hours=1), staff, f"Hàng hoàn theo {number} đã về kho")
    for k, (frm, to, at_, by, why) in enumerate(hist):
        ins("ordering.return_request_status_history", at_, id=uid(f"rsh:{key}:{k}"), return_request_id=rid,
            from_status=frm, to_status=to, reason=why, actor_id=by, occurred_at=at_)
    commit()


return_request(RETURNED, "S16", T(10, 20, 10), "PRINT_DEFECT", "RECEIVED", cartons=2,
               note="2 thùng hộp in lệch màu logo")
return_request(ORDERS[1], "S18", T(10, 1, 10), "PRINT_DEFECT", "REJECTED", note="Khách báo ly in mờ")

# ---- delivery failed, reported by Ahamove's webhook
o = Order("S15", "trasuamay", [(TSM_PET22, 4, 1350)], T(10, 1, 10), "HAN")
o.draw_up(T(10, 1, 15), [(date(2026, 10, 24), date(2026, 10, 28), [4])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])
o.send(T(10, 2, 9))
o.sign(T(10, 3, 15))
o.pay(1, o.plan[0]["amount"], T(10, 4, 10), "FT26277TSM0001")
o.confirm(T(10, 4, 10, 30))
o.ship_from(T(10, 4, 11))
trip = full_cycle(o, T(10, 22, 8), T(10, 24, 8), T(10, 24, 9), T(10, 24, 14), "trip:S15", T(10, 25, 8),
                  carrier="AHAMOVE", plate="30H-456.78", driver="Lê Văn Hùng", ahamove="AHM-26102501")
e = carrier_event(trip, "PICKED_UP", T(10, 25, 8, 20))
trip.milestone("PICKED_UP", T(10, 25, 8, 20), e)
e = carrier_event(trip, "IN_TRANSIT", T(10, 25, 9, 0))
trip.milestone("IN_TRANSIT", T(10, 25, 9, 0), e)
e = carrier_event(trip, "DELIVERY_FAILED", T(10, 25, 15, 0), shipment=o.shipments[0])
deliver(o.shipments[0], T(10, 25, 15, 0), U["quanly.hn"], failure="CUSTOMER_ABSENT", event=e)

# ---- delivered, reported by Ahamove; one event came twice
o = Order("S13", "trasuamay", [(TSM_TUI, 5, 2900)], T(10, 10, 10), "HAN", sales="sales2")
o.draw_up(T(10, 10, 15), [(date(2026, 11, 3), date(2026, 11, 6), [5])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])
o.send(T(10, 11, 9))
o.sign(T(10, 12, 15))
o.pay(1, o.plan[0]["amount"], T(10, 13, 10), "FT26286TSM0002")
o.confirm(T(10, 13, 10, 30))
o.ship_from(T(10, 13, 11), "sales2")
trip = full_cycle(o, T(11, 1, 8), T(11, 3, 8), T(11, 3, 9), T(11, 3, 14), "trip:S13", T(11, 4, 8),
                  carrier="AHAMOVE", plate="30H-111.22", driver="Phạm Quốc Toản", ahamove="AHM-26110401")
for kind, t in (("PICKED_UP", T(11, 4, 8, 15)), ("IN_TRANSIT", T(11, 4, 9, 0))):
    e = carrier_event(trip, kind, t)
    trip.milestone(kind, t, e)
e = carrier_event(trip, "DELIVERED", T(11, 4, 14, 0), shipment=o.shipments[0])
carrier_event(trip, "DELIVERED", T(11, 4, 14, 0), result="DUPLICATE", shipment=o.shipments[0],
              note="Đã áp dụng sự kiện này trước đó")
deliver(o.shipments[0], T(11, 4, 14, 0), U["quanly.hn"], event=e)
o.pay(2, 2_000_000, T(11, 7, 10), "FT26311TSM0003")
return_request(o, "S13", T(11, 6, 10), "DAMAGED_IN_TRANSIT", "REQUESTED", channel="BACK_OFFICE",
               note="Khách báo 1 thùng móp khi nhận")

section("10. orders on their way: shipped, packed, picking, released")
# ---- shipped by Ahamove, in transit; the webhook's failures kept for review
o = Order("S12", "caphemoc", [(CPM_LY12, 6, 420)], T(10, 15, 10), "BDG")
o.draw_up(T(10, 15, 15), [(date(2026, 11, 7), date(2026, 11, 12), [6])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])
o.send(T(10, 16, 9))
o.sign(T(10, 17, 15))
o.pay(1, o.plan[0]["amount"], T(10, 18, 10), "FT26291CPM0001")
o.confirm(T(10, 18, 10, 30))
o.ship_from(T(10, 18, 11))
trip = full_cycle(o, T(11, 5, 8), T(11, 7, 8), T(11, 7, 9), T(11, 7, 14), "trip:S12", T(11, 9, 8),
                  carrier="AHAMOVE", plate="51D-789.01", driver="Trần Văn Bình", ahamove="AHM-26110901")
e = carrier_event(trip, "PICKED_UP", T(11, 9, 8, 30))
trip.milestone("PICKED_UP", T(11, 9, 8, 30), e)
carrier_event(trip, "WAYBILL", T(11, 9, 7, 0), result="STALE", note="Sự kiện cũ hơn trạng thái hiện tại")
carrier_event(None, "IN_TRANSIT", T(11, 9, 10, 0), result="INVALID_SIGNATURE", reference="AHM-26110901",
              signature=False, note="Chữ ký webhook không khớp")
carrier_event(None, "DELIVERED", T(11, 9, 11, 0), result="UNMATCHED", reference="AHM-99999999",
              note="Không có chuyến nào mang mã vận đơn này",
              resolved=("quanly.bd", T(11, 9, 15, 0), "Đơn thử của Ahamove, không thuộc chuyến nào"))
carrier_event(trip, "OTHER", T(11, 9, 12, 0), result="NEEDS_REVIEW", note="Ahamove báo đổi tài xế",
              dedupe="AHM-26110901:OTHER:1")

# ---- shipped with a hired truck, the waybill typed by hand
o = Order("S11", "banhmiviet", [(BMV_HOP, 8, 2300)], T(10, 14, 10), "BDG", sales="sales2")
o.draw_up(T(10, 14, 15), [(date(2026, 11, 6), date(2026, 11, 10), [8])],
          [("Thanh toán khi ký", "ON_SIGNING", 50, 7, None), ("Thanh toán trước giao", "BEFORE_DELIVERY", 50, None, 1)])
o.send(T(10, 15, 9))
o.sign(T(10, 16, 15))
o.pay(1, o.plan[0]["amount"], T(10, 17, 10), "FT26290BMV0001")
o.confirm(T(10, 17, 10, 30))
o.ship_from(T(10, 17, 11), "sales2")
o.pay(2, o.plan[1]["amount"], T(11, 6, 9), "FT26310BMV0002")
trip = full_cycle(o, T(11, 4, 8), T(11, 6, 10), T(11, 6, 11), T(11, 6, 15), "trip:S11", T(11, 8, 8),
                  plate="51C-246.80", driver="Võ Văn Đức")
trip.milestone("PICKED_UP", T(11, 8, 8, 30))

# ---- packed, on a confirmed load plan, the truck booked with Ahamove (first attempt refused)
o = Order("S10", "comtamsg", [(CTS_HOP, 6, 2200)], T(10, 20, 10), "BDG", sales="sales2")
o.draw_up(T(10, 20, 15), [(date(2026, 11, 10), date(2026, 11, 14), [6])],
          [("Thanh toán khi ký", "ON_SIGNING", 50, 7, None), ("Thanh toán trước giao", "BEFORE_DELIVERY", 50, None, 1)])
o.send(T(10, 21, 9))
o.sign(T(10, 22, 15))
o.pay(1, o.plan[0]["amount"], T(10, 23, 10), "FT26296CTS0003")
o.confirm(T(10, 23, 10, 30))
o.ship_from(T(10, 23, 11), "sales2")
po = o.production("S10:1", 1, "TANPHU", T(10, 24, 9), ["PENDING_APPROVAL", "SENT", "IN_PRODUCTION"])
o.move("AWAITING_STOCK", T(10, 25, 9), U["sales2"], "Chờ hàng từ xưởng in")
po.receive(T(11, 6, 8), [6])
o.hold(T(11, 6, 12))
o.pay(2, o.plan[1]["amount"], T(11, 7, 9), "FT26311CTS0004")
pick = o.release(1, T(11, 9, 8), "quanly.bd")
pick.start(T(11, 9, 9))
pick.pick(T(11, 9, 9))
pick.done(T(11, 9, 10))
sh = pick.check(T(11, 9, 14))
trip = Trip("trip:S10", "BDG", [sh], T(11, 9, 16), "AHAMOVE")
trip.open_load(T(11, 10, 8))
trip.ahamove_booking(T(11, 10, 8, 30), "AHM-26111001", failed_first=True)
trip.book(T(11, 10, 8, 40), "51D-135.79", "Nguyễn Hữu Phước", "0933444555")

# ---- picking: the cartons go to the staging area first; four of six there
o = Order("S9", "caphemoc", [(CPM_PET16, 6, 1100)], T(10, 22, 10), "BDG")
o.draw_up(T(10, 22, 15), [(date(2026, 11, 12), date(2026, 11, 15), [6])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])
o.send(T(10, 23, 9))
o.sign(T(10, 24, 15))
o.pay(1, o.plan[0]["amount"], T(10, 25, 10), "FT26298CPM0002")
o.confirm(T(10, 25, 10, 30))
o.ship_from(T(10, 25, 11))
po = o.production("S9:1", 1, "INSG", T(10, 25, 14), ["PENDING_APPROVAL", "SENT", "ACCEPTED", "IN_PRODUCTION"])
o.move("AWAITING_STOCK", T(10, 27, 9), U["sales1"], "Chờ hàng từ xưởng in")
po.receive(T(11, 7, 8), [6])
o.hold(T(11, 7, 12))
pick = o.release(1, T(11, 9, 15), "quanly.bd", staging=True)
pick.start(T(11, 10, 8))
pick.pick(T(11, 10, 8), count=4)

# ---- released, the pick task waiting; a second production order closed unused
o = Order("S8", "kemtuoi", [(KTH_LY8, 8, 380)], T(10, 20, 10), "HAN")
o.draw_up(T(10, 20, 15), [(date(2026, 11, 10), date(2026, 11, 14), [8])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])
o.send(T(10, 21, 9))
o.sign(T(10, 22, 15))
o.pay(1, o.plan[0]["amount"], T(10, 23, 10), "FT26296KEM0003")
o.confirm(T(10, 23, 10, 30))
o.ship_from(T(10, 23, 11))
po = o.production("S8:1", 1, "INHN", T(10, 24, 9), ["PENDING_APPROVAL", "SENT", "IN_PRODUCTION"])
spare = o.production("S8:1b", 1, "TANPHU", T(10, 24, 10), ["PENDING_APPROVAL", "SENT", "IN_PRODUCTION"])
o.move("AWAITING_STOCK", T(10, 25, 9), U["sales1"], "Chờ hàng từ xưởng in")
po.receive(T(11, 7, 8), [8])
spare.close(T(11, 7, 10), "Đã đủ hàng từ xưởng In Ly Giấy Hà Nội, hủy phần in thêm")
o.hold(T(11, 7, 12))
o.release(1, T(11, 10, 8), "quanly.hn")

section("11. orders waiting for their goods: production orders in every state")
# ---- part received: 6 of 10 cartons put away and held, 2 more still in the receiving area; batch 2 printing
o = Order("S7", "trasuamay", [(TSM_PET22, 16, 1350)], T(10, 5, 10), "HAN")
o.draw_up(T(10, 5, 15), [(date(2026, 11, 14), date(2026, 11, 18), [10]), (date(2026, 11, 28), date(2026, 12, 2), [6])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None),
           ("Thanh toán sau đợt 1", "AFTER_DELIVERY", 40, 30, 1),
           ("Thanh toán sau đợt 2", "AFTER_DELIVERY", 30, 30, 2)],
          free_rounds=2)
o.send(T(10, 6, 9))
o.sign(T(10, 7, 15))
o.pay(1, o.plan[0]["amount"], T(10, 8, 10), "FT26281TSM0003")
o.confirm(T(10, 8, 10, 30))
o.ship_from(T(10, 8, 11))
po = o.production("S7:1", 1, "INHN", T(10, 9, 9), ["PENDING_APPROVAL", "SENT", "ACCEPTED"], sample=True)
o.move("AWAITING_STOCK", T(10, 12, 9), U["sales1"], "Chờ hàng từ xưởng in")
po.sample_round(T(10, 14, 10))
po.answer(T(10, 15, 9), True)
po.start(T(10, 16, 8))
po.receive(T(11, 5, 8), [6])
o.hold(T(11, 5, 12))
po.receive(T(11, 9, 8), [2], put_away=0)
po2 = o.production("S7:2", 2, "INHN", T(10, 20, 9), ["PENDING_APPROVAL", "SENT", "IN_PRODUCTION"],
                   waiver="Mẫu in đã duyệt ở đợt 1 cùng hợp đồng")

# ---- a print sample sent back for a reprint, the second round waiting for the customer; batch 2 accepted
o = Order("S6", "banhmiviet", [(BMV_TUI, 16, 3200)], T(10, 25, 10), "BDG", sales="sales2")
o.draw_up(T(10, 25, 15), [(date(2026, 11, 25), date(2026, 11, 28), [8]), (date(2026, 12, 10), date(2026, 12, 13), [8])],
          [("Thanh toán khi ký", "ON_SIGNING", 40, 7, None), ("Thanh toán trước đợt 1", "BEFORE_DELIVERY", 30, None, 1),
           ("Thanh toán trước đợt 2", "BEFORE_DELIVERY", 30, None, 2)])
o.send(T(10, 26, 9))
o.sign(T(10, 27, 15))
o.pay(1, o.plan[0]["amount"], T(10, 28, 10), "FT26301BMV0003")
o.confirm(T(10, 28, 10, 30))
o.ship_from(T(10, 28, 11), "sales2")
po = o.production("S6:1", 1, "INSG", T(10, 29, 9), ["PENDING_APPROVAL", "SENT", "ACCEPTED"], sample=True)
o.move("AWAITING_STOCK", T(10, 31, 9), U["sales2"], "Chờ hàng từ xưởng in")
po.sample_round(T(11, 3, 10))
po.answer(T(11, 4, 9), False, "Màu đỏ logo đậm hơn mẫu, xin in lại")
po.sample_round(T(11, 7, 10), reprint="PRINT_DEFECT")
o.production("S6:2", 2, "TANPHU", T(11, 1, 9), ["PENDING_APPROVAL", "SENT", "ACCEPTED"],
             waiver="Dùng mẫu in của đợt 1 khi khách duyệt")

# ---- sent to the producer, not accepted yet
o = Order("S5", "nuocepxanh", [(NEX_PET16, 12, 1150)], T(10, 28, 10), "BDG", sales="sales2")
o.draw_up(T(10, 28, 15), [(date(2026, 11, 28), date(2026, 12, 2), [12])],
          [("Đặt cọc khi ký", "ON_SIGNING", 50, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 50, 20, 1)])
o.send(T(10, 29, 9))
o.sign(T(10, 30, 15))
o.pay(1, o.plan[0]["amount"], T(10, 31, 10), "FT26304NEX0003")
o.confirm(T(10, 31, 10, 30))
o.ship_from(T(10, 31, 11), "sales2")
o.production("S5:1", 1, "INSG", T(11, 6, 9), ["PENDING_APPROVAL", "SENT"])
o.move("AWAITING_STOCK", T(11, 8, 9), U["sales2"], "Chờ hàng từ xưởng in")

# ---- confirmed: a production order cancelled at a producer that stopped, its replacement waiting for approval,
#      batch 2's still a draft
o = Order("S4", "caphemoc", [(CPM_LY12, 20, 420)], T(11, 1, 10), "BDG")
o.draw_up(T(11, 1, 15), [(date(2026, 11, 28), date(2026, 12, 1), [10]), (date(2026, 12, 12), date(2026, 12, 15), [10])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None),
           ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 2)])
o.send(T(11, 2, 9))
o.sign(T(11, 3, 15))
o.pay(1, o.plan[0]["amount"], T(11, 4, 10), "FT26308CPM0003")
o.confirm(T(11, 4, 10, 30))
o.ship_from(T(11, 4, 11))
old = o.production("S4:0", 1, "INCU", T(11, 5, 9), [])
old.cancel(T(11, 5, 15), "Xưởng ngừng hợp tác, chuyển sang In Bao Bì Sài Gòn")
o.production("S4:1", 1, "INSG", T(11, 6, 9), ["PENDING_APPROVAL"], price=320)
o.production("S4:2", 2, "INSG", T(11, 8, 9), [], price=320)

section("12. orders before production: draft, contract sent, payment pending, cancelled")
# ---- cancelled after signing, before printing: the deposit settled back and refunded (15 §4.6, BR-14)
o = Order("S19", "nuocepxanh", [(NEX_PET16, 6, 1150)], T(10, 18, 10), "BDG", sales="sales2")
o.draw_up(T(10, 18, 15), [(date(2026, 11, 20), date(2026, 11, 24), [6])],
          [("Đặt cọc khi ký", "ON_SIGNING", 50, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 50, 20, 1)])
o.send(T(10, 19, 9))
o.sign(T(10, 20, 15))
o.pay(1, o.plan[0]["amount"], T(10, 21, 10), "FT26294NEX0004")
o.confirm(T(10, 21, 10, 30))
ins("ordering.order_cancellation_request", T(10, 23, 10), id=uid("cancel:S19"), order_id=o.id,
    requested_by=o.c["user"], reason_code="CUSTOMER_REQUEST", note="Khách tạm dừng mở chi nhánh mới",
    requested_at=T(10, 23, 10), status="APPROVED", decided_by=U["truongkd"], decided_at=T(10, 24, 10),
    decision_note="Chưa in hàng loạt, hoàn lại toàn bộ tiền cọc", retained_percent=0)
o.move("CANCELLED", T(10, 24, 10), U["truongkd"], "Khách hủy trước khi in",
       cancellation_reason="Khách tạm dừng mở chi nhánh mới", cancellation_reason_code="CUSTOMER_REQUEST",
       cancellation_retained_amount=0)
o.cancelled_at = T(10, 24, 10)


def refund_cancelled(o, settled, refunded, reference):
    """The cancelled order's money: everything collected is surplus, refundable in full (no fee before printing)."""
    collected = sum(x["amount"] for x in o.payments if not x["void"])
    ins("payment.order_settlement", settled, id=uid("settlement:" + o.key), order_id=o.id, contract_id=o.contract,
        customer_id=o.c["id"], order_number=o.number, contract_number=o.contract_number, kind="ORDER_CANCELLED",
        source_id=o.id, currency="VND", collected_amount=collected, closed_amount=0, reduced_amount=0,
        surplus_amount=collected, fee_amount=0, fee_covered_amount=0, refundable_amount=collected, fee_owed_amount=0,
        settled_by=U["ketoan"], settled_at=settled)
    ins("payment.refund_record", refunded, id=uid("refund:" + o.key), order_id=o.id, customer_id=o.c["id"],
        order_number=o.number, amount=collected, currency="VND", refunded_on=refunded.date(), reference=reference,
        note="Hoàn tiền cọc do hủy đơn", status="RECORDED", recorded_by=U["ketoan"], recorded_at=refunded)
    commit()


AFTER_MONEY = [lambda o=o: refund_cancelled(o, T(10, 24, 15), T(10, 26, 10), "HT26299NEX0001")]

# ---- signed, the deposit not paid yet (due in a week); the customer asks to cancel, not decided
o = Order("S3", "comtamsg", [(CTS_HOP, 8, 2200)], T(11, 3, 10), "BDG", sales="sales2")
o.draw_up(T(11, 3, 15), [(date(2026, 12, 1), date(2026, 12, 5), [8])],
          [("Thanh toán khi ký", "ON_SIGNING", 50, 7, None), ("Thanh toán trước giao", "BEFORE_DELIVERY", 50, None, 1)])
o.send(T(11, 4, 9))
o.sign(T(11, 6, 15))
ins("ordering.order_cancellation_request", T(11, 9, 10), id=uid("cancel:S3"), order_id=o.id,
    requested_by=o.c["user"], reason_code="CUSTOMER_REQUEST", note="Khách muốn dời sang năm sau",
    requested_at=T(11, 9, 10), status="PENDING")

# ---- contract sent for signing
o = Order("S2", "trasuamay", [(TSM_TUI, 10, 2900), (TSM_PET22, 6, 1350)], T(11, 6, 10), "HAN")
o.draw_up(T(11, 7, 9), [(date(2026, 12, 5), date(2026, 12, 9), [10, 6])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])
o.send(T(11, 7, 14))

# ---- converted from its quote, the contract still a draft
o = Order("S1", "kemtuoi", [(KTH_LY8, 12, 380)], T(11, 8, 10), "HAN")
o.draw_up(T(11, 9, 9), [(date(2026, 12, 1), date(2026, 12, 5), [12])],
          [("Đặt cọc khi ký", "ON_SIGNING", 30, 7, None), ("Thanh toán sau giao hàng", "AFTER_DELIVERY", 70, 30, 1)])

# ---- cancelled before signing, at the customer's request
o = Order("S17", "banhmiviet", [(BMV_TUI, 4, 3200)], T(10, 10, 10), "BDG", sales="sales2")
o.draw_up(T(10, 10, 15), [(date(2026, 11, 5), date(2026, 11, 8), [4])],
          [("Thanh toán khi ký", "ON_SIGNING", 100, 7, None)])
o.send(T(10, 11, 9))
ins("ordering.order_cancellation_request", T(10, 12, 10), id=uid("cancel:S17"), order_id=o.id,
    requested_by=o.c["user"], reason_code="CUSTOMER_REQUEST", note="Khách đổi nhà cung cấp túi",
    requested_at=T(10, 12, 10), status="APPROVED", decided_by=U["truongkd"], decided_at=T(10, 12, 15),
    decision_note="Chưa ký hợp đồng, hủy không mất phí")
upd("ordering.sales_contract", o.contract, status="CANCELLED", cancelled_at=T(10, 12, 15), cancelled_by=U["truongkd"],
    cancellation_reason="Khách hủy đơn trước khi ký")
o.move("CANCELLED", T(10, 12, 15), U["truongkd"], "Khách hủy đơn trước khi ký",
       cancellation_reason="Khách đổi nhà cung cấp túi", cancellation_reason_code="CUSTOMER_REQUEST")

# @@SCENARIOS@@

section("13. installments and the receipts that paid them")
for o in ORDERS:
    o.settle_money()
for step in AFTER_MONEY:
    step()

# ============================================================================ emit
HEADER = r"""--
-- Run on a database migrated with db/migration (any profile; it also loads on top of db/demo). Not a Flyway migration
-- and not part of db/demo: nothing loads it automatically.
--     psql -v ON_ERROR_STOP=1 -h <host> -U stockflow -d stockflow -f tools/db/seed/seed_test_data.sql
--
-- * One transaction: it loads completely or not at all, and refuses to run twice. A database holding an older version
--   of this seed is reset first (deploy/README.md "Reset the database"), then migrated and loaded.
-- * Nothing turns a trigger off. The statements run in the order the application would write them, and every
--   "SET CONSTRAINTS ALL IMMEDIATE; SET CONSTRAINTS ALL DEFERRED;" pair marks where the application would commit:
--   the deferred constraint triggers check the state each step leaves, as they would at the end of its transaction.
-- * Ahamove is booked through its API (connection API_BOOKING) only when its staging key is given at load time,
--   sealed with the deployment's STOCKFLOW_SECRETS_KEY - the key is never in this file, nor on a command line:
--     read -rs STOCKFLOW_SECRETS_KEY; read -rs AHAMOVE_STAGING_API_KEY; read -rs AHAMOVE_WEBHOOK_TOKEN
--     read -r AHAMOVE_API_ACCOUNT
--     {{ STOCKFLOW_SECRETS_KEY=$STOCKFLOW_SECRETS_KEY AHAMOVE_STAGING_API_KEY=$AHAMOVE_STAGING_API_KEY \
--       AHAMOVE_WEBHOOK_TOKEN=$AHAMOVE_WEBHOOK_TOKEN AHAMOVE_API_ACCOUNT=$AHAMOVE_API_ACCOUNT \
--       python tools/db/seed/seal_ahamove_key.py; cat tools/db/seed/seed_test_data.sql; }} \
--       | psql -v ON_ERROR_STOP=1 -h <host> -U stockflow -d stockflow
--   (the script writes \set lines, so the sealed values travel on psql's input, not its argv). Without them Ahamove is
--   a MANUAL carrier until its key is entered on the carrier screen, and its trips carry what a person records - typed
--   waybill numbers, manual milestones and outcomes - instead of API bookings, API calls and webhook events.
-- * DEMO PASSWORD: every account below signs in with the published password Stockflow@2026. Fine on a laptop; on a
--   server anyone can reach, give the accounts a hash of your own: -v seed_password_hash='{{bcrypt}}$2a$10$...'.
-- * The design files, contract scans, sample photos and return photos it names are in tools/db/seed/files/
--   (key = path), real files whose size and SHA-256 the rows carry: upload them to the bucket too.
--     local:  docker cp tools/db/seed/files stockflow-minio:/tmp/seed-files && docker exec stockflow-minio sh -c \
--               'mc alias set local http://localhost:9000 stockflow stockflow123 >/dev/null &&
--                mc mirror --overwrite /tmp/seed-files local/stockflow && rm -rf /tmp/seed-files'
--     VPS:    scp -r tools/db/seed/files deploy@<vps-ip>:/tmp/seed-files, then in /opt/stockflow:
--             docker compose cp /tmp/seed-files minio:/tmp/seed-files && docker compose exec -T minio sh -c \
--               "mc alias set local http://localhost:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null &&
--                mc mirror --overwrite /tmp/seed-files local/$(grep ^S3_BUCKET= .env | cut -d= -f2) && rm -rf /tmp/seed-files"
-- * Every row has created_by = 'seed' where the table has the column; ids are md5('seed:' || key)::uuid.
-- * Sign in with any account below, password  Stockflow@2026 (unless seed_password_hash was given)
--     quanly.hn / quanly.bd (WAREHOUSE_MANAGER), kho.hn1, kho.hn2, kho.bd1, kho.bd2 (WAREHOUSE_STAFF),
--     sales1, sales2 (SALES_STAFF), truongkd (SALES_MANAGER), ketoan (ACCOUNTANT), dieuphoi (PRODUCTION_COORDINATOR);
--     nv.nghiviec is DISABLED, nv.bikhoa LOCKED. Customers: kh.<code>, e.g. kh.caphemoc, kh.trasuamay (CUSTOMER).
--   No SYSTEM_ADMIN is created: use the one the database already has. Nothing here refers to it.
-- * Scenario "today" is {today}: the history runs from late August 2026, every date is in the past except delivery
--   windows and due dates still to come.
--
-- What is in it: warehouses HAN and BDG (shelves, bins, and RECEIVING, QUARANTINE, STAGING, DISPATCH, OVERFLOW areas);
-- {products} CUSTOMIZABLE products on {blanks} blanks with print templates; {customers} customers, their approved designs
-- and the {skus} SKUs issued on them (sold and stocked in cartons), a design project; {producers} producers; quotes in
-- every state; {orders} orders from DRAFT to COMPLETED, cancelled, delivery failed and returned, each with its contract,
-- delivery batches, installments in every state and the receipts that paid them; production orders in every state
-- with print-sample rounds; receipts against production orders (the lot is the production order's code) into LPN
-- cartons, put away and held for their orders; pick tasks (to dispatch and to the staging area), carton check sessions,
-- load plans and trips with waybill numbers, Ahamove bookings and webhook events (failures included), and returned
-- cartons received back into quarantine.
-- Not in it: cycle counts, stock adjustments, move tasks, chat, notifications, load estimates, delay alerts and the
-- report tables (the application rolls reports up from the orders on start). Suppliers, purchase orders, in-house
-- production, transfers, carts and COD are gone from the schema (rescope, November 2026) and so from the seed."""


def epilogue():
    """The number sequences, so that the application's next number on a seeded day collides with no seeded one."""
    lines = ["", "-- ---------------------------------------------------------------------------- number sequences"]
    tables = {"SO": "ordering.order_number_sequence", "RMA": "ordering.return_number_sequence"}
    for (kind, d), last in sorted(SEQ.items()):
        if kind in tables:
            t = tables[kind]
            lines.append(f"INSERT INTO {t} (sequence_date, last_value) VALUES ({q(d)}, {last}) "
                         f"ON CONFLICT (sequence_date) DO UPDATE SET last_value = GREATEST({t}.last_value, "
                         f"EXCLUDED.last_value);")
        else:
            lines.append(f"INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) "
                         f"VALUES ({q(kind)}, {q(d)}, {last}) ON CONFLICT (document_type, sequence_date) DO UPDATE "
                         f"SET last_value = GREATEST(platform.document_sequence.last_value, EXCLUDED.last_value);")
    lines += ["", "COMMIT;", ""]
    return chr(10).join(lines)


def emit():
    total = sum(COUNTS.values())
    header = HEADER.format(today=TODAY.isoformat(), products=len(PRODUCTS), blanks=len(VARIANT),
                           customers=len(CUST), skus=len(SKU), producers=len(PRODUCER), orders=len(ORDERS))
    out = [f"""-- =============================================================================
-- StockFlowCommerce - TEST DATA ({total:,} rows inserted across {len(COUNTS)} tables).
--
-- GENERATED by tools/db/seed/gen_seed_test_data.py - edit the generator, not this file.
{header}
-- =============================================================================
"""]
    out.append(PRELUDE)
    out += SQL
    out.append(epilogue())
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(out) + "\n")
    shutil.rmtree(FILES, ignore_errors=True)
    for key, data in SEEDED_FILES.items():
        target = FILES / key
        target.parent.mkdir(parents=True, exist_ok=True)
        with open(target, "wb") as f:
            f.write(data)
    print(f"wrote {OUT.name}: {total} rows in {len(COUNTS)} tables, {len(SEEDED_FILES)} files")


PRELUDE = r"""
\set ON_ERROR_STOP on
\if :{?seed_password_hash}
\else
\set seed_password_hash '""" + DEMO_PASSWORD + r"""'
\endif
BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = '30s';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM identity.app_user WHERE username = 'quanly.hn') THEN
        RAISE EXCEPTION 'seed_test_data.sql has already been loaded into this database';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM identity.app_role WHERE code = 'PRODUCTION_COORDINATOR') THEN
        RAISE EXCEPTION 'run every Flyway migration first: identity.app_role has no PRODUCTION_COORDINATOR';
    END IF;
END $$;
"""
emit()
