#!/usr/bin/env python3
"""
Generates tools/db/seed/seed_test_data.sql: a large, internally consistent test data set for a database
migrated with db/migration only (dev / deployed environments; it also coexists with db/demo on local).

    python tools/db/seed/gen_seed_test_data.py          # rewrites seed_test_data.sql and files/ next to this file

Why a generator rather than a hand-written file: the data has to agree with itself the way the
application would have left it. Stock on hand is what the receipts put away, minus what the picks
consumed, plus the posted adjustments; a reservation's quantity is in its stock row's `reserved`;
order, PO and invoice totals are the sums of their lines; design checksums are the manifest hash
DesignSnapshotJpaEntity computes. Keeping that true by hand across ~3,000 rows is not realistic.

The design artifacts are real files (files/, keyed exactly as the bucket keeps them): prepress, QC and packing hash
the stored bytes against the snapshot, so the seed's checksums are the files' SHA-256 and the files must be uploaded
to the bucket with the SQL (see the header of seed_test_data.sql).

Everything is deterministic (fixed seed, md5-derived ids), so regenerating gives the same file.
Ids are md5('seed:' || key)::uuid, the same scheme as db/demo with another namespace.
"""

import hashlib
import json
import random
import shutil
import struct
import zlib
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_CEILING, ROUND_HALF_UP, Decimal
from pathlib import Path

random.seed(20261012)
VN = timezone(timedelta(hours=7))
TODAY = date(2026, 10, 12)
OUT = Path(__file__).with_name("seed_test_data.sql")
# The design files the seeded artifacts name, laid out as the bucket keeps them (key = path under this folder).
FILES = Path(__file__).with_name("files")
BY = "seed"


# ============================================================================ helpers
def uid(key):
    h = hashlib.md5(("seed:" + key).encode()).hexdigest()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def sha(key):
    return hashlib.sha256(("seed:" + key).encode()).hexdigest()


class Raw(str):
    """An SQL expression emitted verbatim."""


def at(d, h=9, m=0):
    return datetime(d.year, d.month, d.day, h, m, tzinfo=VN)


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


def money(x):
    return round(x)


ROWS = {}        # table -> list of dict
POST = []        # (section, sql) emitted after a given table
SECTION = {}     # table -> comment printed before it


def add(table, **row):
    row.setdefault("created_by", BY) if table not in NO_AUDIT else None
    ROWS.setdefault(table, []).append(row)
    return row


STAGED = []  # (priority, table, row, initial values)


def staged(table, row, priority, **initial):
    """The row is inserted with `initial` and moved to its final values by an UPDATE later on, the way
    the application gets there: the triggers only let a receipt take lines while it is DRAFT, and only
    let a PO take a receipt while it is CONFIRMED."""
    STAGED.append((priority, table, row, initial))


NO_AUDIT = {
    "inventory.stock_reservation", "ordering.order_line", "ordering.order_line_reservation",
    "ordering.order_payment", "ordering.order_line_production", "ordering.approved_sample",
    "production.production_order_status_history", "production.production_qc_result",
    "production.production_scrap", "payment.transfer_allocation", "catalog.product_listing",
    "platform.audit_log", "procurement.po_delivery_decision", "notification.po_delivery_control",
    "production.production_material_issue", "production.production_print_run",
    "production.production_subcontract_receipt", "production.production_subcontract_hold",
    "production.production_subcontract_recall", "procurement.subcontract_settlement",
    # no audit columns: written by plain SQL (product history, customer changes, the inbox) or a domain without them
    "product.product_change_log", "customer.customer_change_history", "notification.user_notification",
    "inventory.stock_alert",
}

U = {}  # username -> user id
ROLE = lambda code: Raw(f"(SELECT id FROM identity.app_role WHERE code = '{code}')")
# Attributes are a shared dictionary: on a local database db/demo already has COLOR and MATERIAL, so
# they are inserted ON CONFLICT DO NOTHING and always referenced by code.
ATTR_ID = lambda code: Raw(f"(SELECT id FROM product.attributes WHERE code = '{code}')")
CONFLICT = {"product.attributes": " ON CONFLICT (code) DO NOTHING"}

# ============================================================================ design files
def png_bytes(key, width=240, height=120):
    """A real PNG: horizontal bands in colours derived from `key`, so every artifact is a different file."""
    h = hashlib.md5(key.encode()).digest()
    bands = [h[i:i + 3] for i in range(0, 15, 3)]
    raw = b"".join(b"\x00" + bands[y * len(bands) // height] * width for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def pdf_bytes(title):
    """A real one-page PDF naming the job, with a correct cross-reference table."""
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


SEEDED_FILES = {}   # storage key -> bytes


def design_file(artifact_id, content_type, stored_at, title):
    """A design artifact's file as DesignArtifactService stores it: key design-renders/<UTC yyyy/MM/dd>/<id>.<ext>
    (StorageKeys.generate, FileCategory.DESIGN_RENDER - the only prefix the storage layer accepts for a design file),
    size and SHA-256 of the real bytes (BufferedUpload), so prepress and packing verify it against the bucket."""
    ext = {"image/png": ".png", "application/pdf": ".pdf"}[content_type]
    key = f"design-renders/{stored_at.astimezone(timezone.utc):%Y/%m/%d}/{artifact_id}{ext}"
    data = png_bytes(artifact_id) if ext == ".png" else pdf_bytes(title)
    SEEDED_FILES[key] = data
    return dict(key=key, size=len(data), checksum=hashlib.sha256(data).hexdigest())


# ============================================================================ 1. identity
STAFF = [
    # username, full name, roles, warehouses, status
    # No SYSTEM_ADMIN here: the target database already has its own, and nothing seeded points at it.
    ("quanly.hn", "Trần Minh Khoa", ["WAREHOUSE_MANAGER"], ["HN"], "ACTIVE"),
    ("quanly.bd", "Lê Thu Hà", ["WAREHOUSE_MANAGER"], ["BD", "DN"], "ACTIVE"),
    ("kho.hn1", "Phạm Văn Long", ["WAREHOUSE_STAFF"], ["HN"], "ACTIVE"),
    ("kho.hn2", "Đỗ Thị Mai", ["WAREHOUSE_STAFF"], ["HN"], "ACTIVE"),
    ("kho.bd1", "Ngô Quốc Bảo", ["WAREHOUSE_STAFF"], ["BD"], "ACTIVE"),
    ("kho.bd2", "Vũ Thị Lan", ["WAREHOUSE_STAFF"], ["BD"], "ACTIVE"),
    ("kho.dn1", "Huỳnh Tấn Phát", ["WAREHOUSE_STAFF"], ["DN"], "ACTIVE"),
    ("qc.hn", "Bùi Thị Ngọc", ["QC_STAFF"], ["HN"], "ACTIVE"),
    ("qc.bd", "Đặng Minh Tuấn", ["QC_STAFF"], ["BD"], "ACTIVE"),
    ("muahang1", "Hoàng Anh Thư", ["PROCUREMENT_STAFF"], [], "ACTIVE"),
    ("muahang2", "Phan Đức Huy", ["PROCUREMENT_STAFF"], [], "ACTIVE"),
    ("kehoach", "Trịnh Thị Yến", ["INVENTORY_PLANNER"], [], "ACTIVE"),
    ("sales1", "Nguyễn Hải Yến", ["SALES_STAFF"], [], "ACTIVE"),
    ("sales2", "Lý Gia Hân", ["SALES_STAFF"], [], "ACTIVE"),
    ("dieuphoi", "Mai Xuân Trường", ["ORDER_COORDINATOR"], [], "ACTIVE"),
    ("ketoan", "Tạ Thị Hồng", ["ACCOUNTANT"], [], "ACTIVE"),
    ("ecom", "Châu Ngọc Ánh", ["ECOMMERCE_ADMIN"], [], "ACTIVE"),
    ("ecom2", "Vương Thảo Vy", ["ECOMMERCE_ADMIN"], [], "ACTIVE"),
    ("sanxuat.hn", "Lâm Văn Tài", ["PRODUCTION_STAFF"], ["HN"], "ACTIVE"),
    ("sanxuat.bd", "Kiều Văn Sơn", ["PRODUCTION_STAFF"], ["BD"], "ACTIVE"),
    ("thietke", "Dương Thùy Linh", ["SALES_STAFF"], [], "ACTIVE"),
    ("kiemduyet", "Tô Hoàng Nam", ["PRODUCTION_STAFF"], ["HN", "BD"], "ACTIVE"),
    ("nv.nghiviec", "Cao Thị Diễm", ["WAREHOUSE_STAFF"], ["HN"], "DISABLED"),
    ("nv.bikhoa", "Quách Văn Đạt", ["WAREHOUSE_STAFF"], ["BD"], "LOCKED"),
]
# bcrypt(10) of "Stockflow@2026", in Spring's DelegatingPasswordEncoder format.
PASSWORD = "{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y"


def user(username, full_name, status="ACTIVE", email=None, created=None, **extra):
    U[username] = uid("user:" + username)
    add("identity.app_user", id=U[username], username=username,
        email=email or f"{username}@stockflow.example", password_hash=PASSWORD, full_name=full_name,
        status=status, created_at=created or at(date(2026, 8, 1), 8), **extra)


USER_WH = []
for name, full, roles, whs, status in STAFF:
    extra = {}
    if status == "LOCKED":
        extra = dict(failed_login_count=5, locked_until=at(date(2026, 12, 31), 23))
    if status == "ACTIVE":
        extra["last_login_at"] = at(TODAY - timedelta(days=random.randint(0, 6)), random.randint(7, 17))
    user(name, full, status, **extra)
    for r in roles:
        add("identity.user_role", id=uid(f"ur:{name}:{r}"), user_id=U[name], role_id=ROLE(r),
            created_at=at(date(2026, 8, 1), 8))
    USER_WH += [(name, w) for w in whs]

# ============================================================================ 2. warehouses
WAREHOUSES = {
    # prefix: name, address, return address, map w, h, shelves [(code, zone, class, levels)]
    "HN": ("Kho Hà Nội", "Lô B2, KCN Nam Thăng Long, Phường Đông Ngạc, TP. Hà Nội",
           "Lô B2, KCN Nam Thăng Long, Phường Đông Ngạc, TP. Hà Nội", 80, 50,
           [("A01", "A", "NORMAL", 3), ("A02", "A", "NORMAL", 3), ("B01", "B", "NORMAL", 3),
            ("B02", "B", "FRAGILE", 3), ("C01", "C", "HAZMAT", 3)]),
    "BD": ("Kho Bình Dương", "Số 18 Đại lộ Tự Do, KCN VSIP 1, Phường Thuận An, TP. Hồ Chí Minh",
           "Số 18 Đại lộ Tự Do, KCN VSIP 1, Phường Thuận An, TP. Hồ Chí Minh", 100, 60,
           [("A01", "A", "NORMAL", 3), ("A02", "A", "NORMAL", 3), ("A03", "A", "NORMAL", 3),
            ("B01", "B", "NORMAL", 3), ("B02", "B", "FRAGILE", 3), ("C01", "C", "HAZMAT", 3)]),
    "DN": ("Kho Đà Nẵng", "Đường số 9, KCN Hòa Khánh, Phường Liên Chiểu, TP. Đà Nẵng", None, 40, 30,
           [("A01", "A", "NORMAL", 2), ("B01", "B", "NORMAL", 2)]),
}
ZONES = {"A": ("Khu A - Ly & nắp", "#4F81BD"), "B": ("Khu B - Hộp & túi", "#9BBB59"),
         "C": ("Khu C - Vật tư in", "#C0504D")}
AREAS = [  # code, type, name, (x, y, w, l), warehouses
    ("RCV01", "RECEIVING", "Khu nhận hàng", (2, 2, 14, 8), "HN BD DN"),
    ("QCA01", "QUALITY_CONTROL", "Khu kiểm tra chất lượng", (18, 2, 8, 6), "HN BD DN"),
    ("QRT01", "QUARANTINE", "Khu cách ly / hàng lỗi", (28, 2, 6, 6), "HN BD DN"),
    ("RTV01", "QUARANTINE", "Khu hàng chờ trả NCC", (36, 2, 6, 6), "HN BD"),
    ("PACK01", "PACKING", "Khu đóng gói", (2, 40, 12, 6), "HN BD DN"),
    ("DSP01", "DISPATCH", "Khu chờ giao", (16, 40, 12, 6), "HN BD DN"),
    ("OVF01", "OVERFLOW", "Khu tràn kho", (60, 40, 14, 10), "BD"),
    # kltn-docs 19: the print shop is a PRODUCTION area; blanks are issued to it, printed and inspected there (BR-03,
    # BR-05). Only HN and BD print (their production and QC staff); DN has no print shop, so a DN customer's print
    # order is released from BD (BR-20) - see PRINT_WAREHOUSE.
    ("PRD01", "PRODUCTION", "Xưởng in", (40, 14, 20, 14), "HN BD"),
]
PRINT_WAREHOUSE = {"HN": "HN", "BD": "BD", "DN": "BD"}   # where a customer's print orders are made and shipped from
WH = {}          # prefix -> id
LOC = {}         # location code -> {id, wh, kind, class}
BINS = {}        # prefix -> {class: [location codes]}
for p, (name, addr, raddr, mw, mh, shelves) in WAREHOUSES.items():
    WH[p] = uid("wh:" + p)
    created = at(date(2026, 8, 1), 8)
    add("warehouse.warehouse", id=WH[p], name=name, status="ACTIVE", prefix=p, address=addr,
        return_address=raddr, map_unit="M", map_width=mw, map_height=mh, created_at=created)
    for z in sorted({s[1] for s in shelves}):
        add("warehouse.zone", id=uid(f"zone:{p}:{z}"), warehouse_id=WH[p], name=ZONES[z][0],
            color=ZONES[z][1], created_at=created)
    BINS[p] = {}
    for i, (sc, z, cls, levels) in enumerate(shelves):
        sy = 14 + i * 4
        add("warehouse.shelf", id=uid(f"shelf:{p}:{sc}"), warehouse_id=WH[p], zone_id=uid(f"zone:{p}:{z}"),
            code=sc, name=f"Kệ {sc}", x=4, y=sy, width=12, length=1.2, rotation=0, is_obstacle=True,
            pick_north=False, pick_east=False, pick_south=True, pick_west=False,
            default_storage_class=cls, status="ACTIVE", created_at=created)
        for lv in range(1, levels + 1):
            add("warehouse.shelf_level", id=uid(f"level:{p}:{sc}:{lv}"), shelf_id=uid(f"shelf:{p}:{sc}"),
                level_index=lv, elevation=round((lv - 1) * 1.6, 1), usable_height=1.5, max_weight=800,
                created_at=created)
            for bi, bc in enumerate("ABC"):
                code = f"{p}-{sc}-{lv}-{bc}"
                status = "ACTIVE"
                if code == "HN-A02-3-C":
                    status = "BLOCKED"
                if code == "BD-A03-3-C":
                    status = "MAINTENANCE"
                LOC[code] = dict(id=uid("loc:" + code), wh=p, kind="BIN", cls=cls, status=status)
                add("warehouse.storage_location", id=LOC[code]["id"], warehouse_id=WH[p], kind="BIN",
                    location_code=code, storage_class=cls, capacity_units=600, max_weight=800,
                    is_pickable=True, is_putaway_target=True, status=status, created_at=created)
                add("warehouse.bin", id=uid("bin:" + code), level_id=uid(f"level:{p}:{sc}:{lv}"),
                    location_id=LOC[code]["id"], code=bc, x=bi * 4, y=0, width=4, length=1.2, rotation=0,
                    type="PALLET" if cls != "HAZMAT" else "STANDARD", created_at=created)
                if status == "ACTIVE":
                    BINS[p].setdefault(cls, []).append(code)
    for code, typ, aname, (x, y, w, l), whs in AREAS:
        if p not in whs.split():
            continue
        lc = f"{p}-{code}"
        LOC[lc] = dict(id=uid("loc:" + lc), wh=p, kind="AREA", cls="OVERSIZE", status="ACTIVE", type=typ)
        add("warehouse.storage_location", id=LOC[lc]["id"], warehouse_id=WH[p], kind="AREA", location_code=lc,
            storage_class="OVERSIZE", capacity_units=None, max_weight=None, is_pickable=False,
            is_putaway_target=typ == "OVERFLOW", status="ACTIVE", created_at=created)
        add("warehouse.area", id=uid("area:" + lc), warehouse_id=WH[p], location_id=LOC[lc]["id"], code=code,
            type=typ, name=aname, x=x, y=y, width=w, length=l, rotation=0, is_obstacle=False, status="ACTIVE",
            created_at=created)
    add("warehouse.area", id=uid(f"area:{p}-OFFICE"), warehouse_id=WH[p], location_id=None, code="OFFICE",
        type="NON_STORAGE", name="Văn phòng kho", x=mw - 12, y=2, width=10, length=6, rotation=0, is_obstacle=True,
        status="ACTIVE", created_at=created)
    walls = [(0, 0, mw, 0), (mw, 0, mw, mh), (mw, mh, 0, mh), (0, mh, 0, 0)]
    for i, (a, b, c, d) in enumerate(walls):
        add("warehouse.boundary", id=uid(f"wall:{p}:{i}"), warehouse_id=WH[p], type="WALL", start_x=a, start_y=b,
            end_x=c, end_y=d, is_passable=False, operational_status=None, created_at=created)
    add("warehouse.boundary", id=uid(f"door:{p}:in"), warehouse_id=WH[p], type="DOOR", start_x=4, start_y=0,
        end_x=12, end_y=0, is_passable=True, operational_status="OPEN", created_at=created)
    add("warehouse.boundary", id=uid(f"door:{p}:out"), warehouse_id=WH[p], type="DOOR", start_x=18, start_y=mh,
        end_x=26, end_y=mh, is_passable=True, operational_status="OPEN" if p != "DN" else "CLOSED",
        created_at=created)
    if p != "DN":
        add("warehouse.slotting_rule", id=uid(f"slot:{p}:1"), warehouse_id=WH[p],
            name="Hàng bán chạy gần cửa xuất", criteria='{"velocity":"A","preferLevel":1,"zone":"A"}',
            priority=10, active=True, created_at=created)
        add("warehouse.slotting_rule", id=uid(f"slot:{p}:2"), warehouse_id=WH[p],
            name="Mực in chỉ để kệ HAZMAT", criteria='{"storageClass":"HAZMAT","shelf":"C01"}',
            priority=20, active=True, created_at=created)
        add("warehouse.slotting_rule", id=uid(f"slot:{p}:3"), warehouse_id=WH[p],
            name="Hộp quà để kệ hàng dễ vỡ", criteria='{"storageClass":"FRAGILE"}', priority=5,
            active=p == "HN", created_at=created)

for name, w in USER_WH:
    add("identity.user_warehouse", id=uid(f"uw:{name}:{w}"), user_id=U[name], warehouse_id=WH[w],
        created_at=at(date(2026, 8, 1), 8))

for i, name in enumerate(["ecom2", "quanly.hn", "kho.hn1", "sales1", "ketoan", "muahang1", "kho.bd1"]):
    d = TODAY - timedelta(days=i)
    revoked = i in (3, 5)
    add("identity.user_session", id=uid(f"session:{name}"), user_id=U[name], issued_at=at(d, 8),
        expires_at=at(d + timedelta(days=7), 8), revoked_at=at(d, 17) if revoked else None,
        revoked_reason=("LOGOUT" if i == 3 else "PASSWORD_CHANGED") if revoked else None,
        client_address=f"10.0.{i}.{20 + i}", user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/129.0",
        created_at=at(d, 8))
for name in ["ecom2", "quanly.hn", "quanly.bd", "muahang1", "sales1", "ketoan"]:
    for ch, en in (("EMAIL", True), ("SMS", name in ("quanly.hn", "quanly.bd")), ("PUSH", True)):
        add("notification.preference", id=uid(f"pref:{name}:{ch}"), user_id=U[name], channel=ch, enabled=en,
            created_at=at(date(2026, 8, 1), 8))

# ============================================================================ 3. product catalogue
BRANDS = [("SFPACK", "SF Pack", "sf-pack"), ("GREENLY", "Greenly", "greenly"), ("VINAPACK", "VinaPack", "vinapack")]
for code, name, slug in BRANDS:
    add("product.brands", id=uid("brand:" + code), code=code, name=name, slug=slug,
        logo_url=f"https://placehold.co/200x80?text={code}", created_at=at(date(2026, 8, 1), 8))

CATS = [  # code, parent, name, slug
    ("CUPS", None, "Ly & cốc", "ly-coc"),
    ("PAPER-CUPS", "CUPS", "Ly giấy", "ly-giay"),
    ("PLASTIC-CUPS", "CUPS", "Ly nhựa", "ly-nhua"),
    ("LIDS", "CUPS", "Nắp ly", "nap-ly"),
    ("BOXES", None, "Hộp giấy", "hop-giay"),
    ("FOOD-BOXES", "BOXES", "Hộp đựng thực phẩm", "hop-dung-thuc-pham"),
    ("GIFT-BOXES", "BOXES", "Hộp quà", "hop-qua"),
    ("BAGS", None, "Túi", "tui"),
    ("KRAFT-BAGS", "BAGS", "Túi giấy kraft", "tui-giay-kraft"),
    ("ACCESSORIES", None, "Phụ kiện", "phu-kien"),
    ("STRAWS", "ACCESSORIES", "Ống hút", "ong-hut"),
    ("SLEEVES", "ACCESSORIES", "Tay cầm & khay ly", "tay-cam-khay-ly"),
    ("MATERIALS", None, "Vật tư in", "vat-tu-in"),
    ("INKS", "MATERIALS", "Mực in", "muc-in"),
]
CAT_PATH = {}
for i, (code, parent, name, slug) in enumerate(CATS):
    CAT_PATH[code] = (CAT_PATH[parent] if parent else "") + "/" + code
    add("product.categories", id=uid("cat:" + code), parent_id=uid("cat:" + parent) if parent else None,
        code=code, name=name, slug=slug, path=CAT_PATH[code], depth=0 if parent is None else 1, sort_order=i,
        image_url=f"https://placehold.co/400x300?text={code}", seo_title=f"{name} in logo theo yêu cầu",
        seo_description=f"{name} cho quán cà phê, trà sữa, nhà hàng - in logo số lượng lớn.",
        created_at=at(date(2026, 8, 1), 8))

ATTRS = {  # code: name, data type, unit, values [(code, label, number, hex)]
    "CAPACITY": ("Dung tích", "SELECT", "oz",
                 [(f"{n}OZ", f"{n} oz", n, None) for n in (4, 8, 12, 16, 22, 24)]),
    "COLOR": ("Màu sắc", "COLOR", None,
              [("WHITE", "Trắng", None, "#FFFFFF"), ("BLACK", "Đen", None, "#1A1A1A"),
               ("KRAFT", "Nâu kraft", None, "#C8A27A"), ("PINK", "Hồng", None, "#F4A6C0"),
               ("CYAN", "Xanh Cyan", None, "#00AEEF"), ("MAGENTA", "Đỏ Magenta", None, "#EC008C"),
               ("YELLOW", "Vàng", None, "#FFF200")]),
    "SIZE": ("Kích thước", "SELECT", None,
             [("S", "Nhỏ (S)", None, None), ("M", "Vừa (M)", None, None), ("L", "Lớn (L)", None, None),
              ("D80", "Miệng 80 mm", 80, None), ("D90", "Miệng 90 mm", 90, None), ("D95", "Miệng 95 mm", 95, None),
              ("8IN", "8 inch", 8, None), ("10IN", "10 inch", 10, None), ("12IN", "12 inch", 12, None),
              ("2CUP", "2 ô ly", 2, None), ("4CUP", "4 ô ly", 4, None)]),
    "MATERIAL": ("Chất liệu", "SELECT", None,
                 [("PAPER-PE", "Giấy tráng PE", None, None), ("KRAFT", "Giấy kraft", None, None),
                  ("PET", "Nhựa PET", None, None), ("PP", "Nhựa PP", None, None),
                  ("IVORY", "Giấy Ivory", None, None), ("WATER-BASED", "Mực gốc nước", None, None)]),
    "WALL": ("Số lớp thành ly", "SELECT", None,
             [("SINGLE", "1 lớp", None, None), ("DOUBLE", "2 lớp", None, None)]),
    "PCS_PER_CARTON": ("Số cái / thùng", "NUMBER", "cái", []),
    "FOOD_GRADE": ("Đạt chuẩn an toàn thực phẩm", "BOOLEAN", None, []),
    "ORIGIN": ("Xuất xứ", "TEXT", None, []),
}
for i, (code, (name, dt_, unit, values)) in enumerate(ATTRS.items()):
    add("product.attributes", id=uid("attr:" + code), code=code, name=name, data_type=dt_, unit=unit,
        is_filterable=dt_ in ("SELECT", "COLOR", "BOOLEAN"), is_searchable=dt_ == "TEXT", sort_order=i,
        created_at=at(date(2026, 8, 1), 8))
    for j, (vc, label, num, hx) in enumerate(values):
        add("product.attribute_values", id=uid(f"val:{code}:{vc}"), attribute_id=ATTR_ID(code), code=vc,
            label=label, value_number=num, hex_color=hx, sort_order=j, created_at=at(date(2026, 8, 1), 8))

CAT_ATTRS = {
    "PAPER-CUPS": [("CAPACITY", "VARIANT_AXIS", True), ("MATERIAL", "DESCRIPTIVE", False),
                   ("WALL", "DESCRIPTIVE", False), ("PCS_PER_CARTON", "DESCRIPTIVE", True),
                   ("FOOD_GRADE", "DESCRIPTIVE", False)],
    "PLASTIC-CUPS": [("CAPACITY", "VARIANT_AXIS", True), ("MATERIAL", "DESCRIPTIVE", True),
                     ("PCS_PER_CARTON", "DESCRIPTIVE", True)],
    "LIDS": [("SIZE", "VARIANT_AXIS", True), ("COLOR", "VARIANT_AXIS", False)],
    "FOOD-BOXES": [("SIZE", "VARIANT_AXIS", True), ("MATERIAL", "DESCRIPTIVE", False),
                   ("FOOD_GRADE", "DESCRIPTIVE", False)],
    "GIFT-BOXES": [("SIZE", "VARIANT_AXIS", True), ("ORIGIN", "DESCRIPTIVE", False)],
    "KRAFT-BAGS": [("SIZE", "VARIANT_AXIS", True), ("COLOR", "VARIANT_AXIS", False)],
    "STRAWS": [("COLOR", "VARIANT_AXIS", True), ("PCS_PER_CARTON", "DESCRIPTIVE", False)],
    "SLEEVES": [("CAPACITY", "VARIANT_AXIS", False), ("SIZE", "VARIANT_AXIS", False)],
    "INKS": [("COLOR", "VARIANT_AXIS", True), ("MATERIAL", "DESCRIPTIVE", False), ("ORIGIN", "DESCRIPTIVE", False)],
}
for cat, attrs in CAT_ATTRS.items():
    for i, (a, role, req) in enumerate(attrs):
        add("product.category_attributes", id=uid(f"ca:{cat}:{a}"), category_id=uid("cat:" + cat),
            attribute_id=ATTR_ID(a), default_role=role, is_required=req, sort_order=i,
            created_at=at(date(2026, 8, 1), 8))

# Product spec:
#  code, name, english name, category, brand, kind, status, axes [(attr, [values])], descriptive {attr: value},
#  prices per variant (VND per selling unit; None = not sold), item spec
#  item spec: uom, (weight kg, l, w, h cm) of the carton, storage class, flags
PRODUCTS = [
    ("LYG-1L", "Ly giấy 1 lớp in logo", "Single wall paper cup", "PAPER-CUPS", "SFPACK", "CUSTOMIZABLE", "PUBLISHED",
     [("CAPACITY", ["8OZ", "12OZ", "16OZ", "22OZ"])],
     {"MATERIAL": "PAPER-PE", "WALL": "SINGLE", "PCS_PER_CARTON": 1000, "FOOD_GRADE": True},
     [420000, 520000, 610000, 780000], ("CTN", (6.5, 52, 42, 60), "NORMAL", "")),
    ("LYG-2L", "Ly giấy 2 lớp cách nhiệt", "Double wall paper cup", "PAPER-CUPS", "SFPACK", "CUSTOMIZABLE",
     "PUBLISHED", [("CAPACITY", ["8OZ", "12OZ", "16OZ"])],
     {"MATERIAL": "PAPER-PE", "WALL": "DOUBLE", "PCS_PER_CARTON": 500, "FOOD_GRADE": True},
     [650000, 790000, 920000], ("CTN", (7.2, 55, 45, 62), "NORMAL", "")),
    ("LYG-KRAFT", "Ly giấy kraft thân thiện môi trường", "Kraft paper cup", "PAPER-CUPS", "GREENLY", "CUSTOMIZABLE",
     "PUBLISHED", [("CAPACITY", ["12OZ", "16OZ"])],
     {"MATERIAL": "KRAFT", "WALL": "SINGLE", "PCS_PER_CARTON": 1000, "FOOD_GRADE": True},
     [690000, 760000], ("CTN", (6.8, 52, 42, 60), "NORMAL", "")),
    ("LYG-ESP", "Ly giấy espresso 4oz", "Espresso paper cup 4oz", "PAPER-CUPS", "SFPACK", "CUSTOMIZABLE",
     "PUBLISHED", [("CAPACITY", ["4OZ"])],
     {"MATERIAL": "PAPER-PE", "WALL": "SINGLE", "PCS_PER_CARTON": 2000, "FOOD_GRADE": True},
     [310000], ("CTN", (4.2, 45, 35, 40), "NORMAL", "")),
    ("LYN-PET", "Ly nhựa PET in logo", "Printed PET cup", "PLASTIC-CUPS", "VINAPACK", "CUSTOMIZABLE", "PUBLISHED",
     [("CAPACITY", ["12OZ", "16OZ", "22OZ", "24OZ"])], {"MATERIAL": "PET", "PCS_PER_CARTON": 1000},
     [480000, 560000, 650000, 700000], ("CTN", (8.0, 60, 45, 55), "NORMAL", "qc")),
    ("LYN-PP", "Ly nhựa PP dày", "Thick PP cup", "PLASTIC-CUPS", "VINAPACK", "STANDARD", "PUBLISHED",
     [("CAPACITY", ["16OZ", "22OZ"])], {"MATERIAL": "PP", "PCS_PER_CARTON": 1000},
     [430000, 520000], ("CTN", (7.5, 60, 45, 55), "NORMAL", "")),
    ("NAP-GIAY", "Nắp ly giấy", "Paper cup lid", "LIDS", "SFPACK", "STANDARD", "PUBLISHED",
     [("SIZE", ["D80", "D90"]), ("COLOR", ["WHITE", "BLACK"])], {},
     [260000, 270000, 280000, 290000], ("CTN", (5.0, 50, 40, 40), "NORMAL", "")),
    ("NAP-PET", "Nắp cầu PET", "PET dome lid", "LIDS", "VINAPACK", "STANDARD", "PUBLISHED",
     [("SIZE", ["D90", "D95"])], {}, [280000, 300000], ("CTN", (4.5, 50, 40, 45), "NORMAL", "")),
    ("HOP-COM", "Hộp cơm giấy kraft", "Kraft meal box", "FOOD-BOXES", "GREENLY", "CUSTOMIZABLE", "PUBLISHED",
     [("SIZE", ["S", "M", "L"])], {"MATERIAL": "KRAFT", "FOOD_GRADE": True},
     [550000, 640000, 720000], ("CTN", (9.0, 60, 40, 45), "NORMAL", "")),
    ("HOP-PIZZA", "Hộp bánh pizza", "Pizza box", "FOOD-BOXES", "SFPACK", "CUSTOMIZABLE", "PUBLISHED",
     [("SIZE", ["8IN", "10IN", "12IN"])], {"MATERIAL": "KRAFT", "FOOD_GRADE": True},
     [380000, 450000, 520000], ("CTN", (10.0, 35, 35, 50), "NORMAL", "")),
    ("HOP-QUA", "Hộp quà cứng cao cấp", "Premium rigid gift box", "GIFT-BOXES", "VINAPACK", "CUSTOMIZABLE",
     "PUBLISHED", [("SIZE", ["S", "M"])], {"ORIGIN": "Sản xuất tại Bình Dương"},
     [1800000, 2400000], ("CTN", (12.0, 60, 50, 50), "FRAGILE", "")),
    ("TUI-KRAFT", "Túi giấy kraft in logo", "Printed kraft bag", "KRAFT-BAGS", "GREENLY", "CUSTOMIZABLE",
     "PUBLISHED", [("SIZE", ["S", "M", "L"]), ("COLOR", ["KRAFT", "WHITE"])], {},
     [450000, 470000, 520000, 540000, 600000, 620000], ("CTN", (11.0, 50, 40, 35), "NORMAL", "")),
    ("TUI-QUAI", "Túi giấy quai xoắn", "Twisted handle paper bag", "KRAFT-BAGS", "GREENLY", "STANDARD", "PUBLISHED",
     [("SIZE", ["M", "L"])], {}, [520000, 610000], ("CTN", (12.0, 50, 40, 40), "NORMAL", "")),
    ("OHG", "Ống hút giấy", "Paper straw", "STRAWS", "GREENLY", "STANDARD", "PUBLISHED",
     [("COLOR", ["WHITE", "BLACK", "PINK"])], {"PCS_PER_CARTON": 5000},
     [350000, 360000, 360000], ("CTN", (5.5, 45, 30, 25), "NORMAL", "")),
    ("OHN-PP", "Ống hút nhựa PP", "PP plastic straw", "STRAWS", "VINAPACK", "STANDARD", "DISCONTINUED",
     [("COLOR", ["BLACK"])], {"PCS_PER_CARTON": 5000}, [None], ("CTN", (4.0, 45, 30, 25), "NORMAL", "obsolete")),
    ("TAYCAM", "Tay cầm ly giấy in logo", "Printed cup sleeve", "SLEEVES", "SFPACK", "CUSTOMIZABLE", "PUBLISHED",
     [("CAPACITY", ["12OZ", "16OZ"])], {}, [290000, 310000], ("CTN", (5.0, 45, 35, 30), "NORMAL", "")),
    ("KHAY-LY", "Khay giấy đựng ly", "Cup carrier tray", "SLEEVES", "SFPACK", "STANDARD", "PUBLISHED",
     [("SIZE", ["2CUP", "4CUP"])], {}, [330000, 410000], ("CTN", (6.0, 50, 40, 40), "NORMAL", "")),
    ("MUC-FLEXO", "Mực in flexo gốc nước", "Water-based flexo ink", "INKS", "SFPACK", "STANDARD", "APPROVED",
     [("COLOR", ["CYAN", "MAGENTA", "YELLOW", "BLACK"])],
     {"MATERIAL": "WATER-BASED", "ORIGIN": "Nhập khẩu Nhật Bản"},
     [None, None, None, None], ("CAN", (5.0, 20, 20, 25), "HAZMAT", "qc lot")),
    ("HOP-SALAD", "Hộp salad nắp trong", "Salad box with clear lid", "FOOD-BOXES", "GREENLY", "CUSTOMIZABLE",
     "PENDING_APPROVAL", [("SIZE", ["M"])], {"MATERIAL": "KRAFT", "FOOD_GRADE": True}, [None],
     ("CTN", (7.0, 50, 40, 40), "NORMAL", "draft")),
    ("LYG-TET", "Ly giấy in sẵn Tết 2027", "Lunar New Year 2027 cup", "PAPER-CUPS", "SFPACK", "STANDARD", "DRAFT",
     [("CAPACITY", ["12OZ"])], {"MATERIAL": "PAPER-PE", "PCS_PER_CARTON": 1000}, [None],
     ("CTN", (6.5, 52, 42, 60), "NORMAL", "draft")),
    ("HOP-TRUNGTHU", "Hộp bánh trung thu", "Mooncake box", "GIFT-BOXES", "VINAPACK", "CUSTOMIZABLE", "APPROVED",
     [("SIZE", ["S", "M"])], {"ORIGIN": "Sản xuất tại Bình Dương"}, [None, None],
     ("CTN", (10.0, 60, 50, 45), "FRAGILE", "")),
    ("TUI-VAI", "Túi vải canvas", "Canvas tote bag", "KRAFT-BAGS", "GREENLY", "STANDARD", "APPROVED",
     [("SIZE", ["M"])], {}, [None], ("CTN", (8.0, 50, 40, 30), "NORMAL", "blocked")),
]

EDITOR, APPROVER = "ecom", "ecom2"  # BR-PRD-003: whoever approves did not submit
VARIANTS = []        # dicts: sku, product, price, cls, flags, kind, product status, name
PROD = {}
P_CREATED = at(date(2026, 8, 2), 9)
for pi, (pcode, pname, pen, cat, brand, kind, status, axes, descr, prices, item) in enumerate(PRODUCTS):
    pid = uid("product:" + pcode)
    slug = pcode.lower()
    approved = status in ("APPROVED", "ACTIVE", "PUBLISHED", "INACTIVE", "DISCONTINUED")
    submitted = approved or status == "PENDING_APPROVAL"
    PROD[pcode] = dict(id=pid, name=pname, kind=kind, status=status, slug=slug)
    add("product.products", id=pid, brand_id=uid("brand:" + brand), code=pcode, name=pname, name_en=pen,
        slug=slug, short_description=f"{pname} - nhận in logo theo thiết kế của khách hàng."
        if kind == "CUSTOMIZABLE" else f"{pname}, đóng thùng, giao toàn quốc.",
        description=f"{pname}. Sản phẩm dành cho khách hàng doanh nghiệp (B2B), bán theo thùng. "
                    f"Đặt tối thiểu 5 thùng / mã. Hỗ trợ in ấn và giao hàng toàn quốc.",
        description_en=f"{pen}. Sold by carton to business customers.",
        tax_class="STANDARD", kind=kind, status=status,
        seo_title=f"{pname} giá sỉ | SF Pack", seo_description=f"Mua {pname.lower()} số lượng lớn, in logo theo yêu cầu.",
        submitted_by=U[EDITOR] if submitted else None, submitted_at=at(date(2026, 8, 3), 10) if submitted else None,
        approved_by=U[APPROVER] if approved else None, approved_at=at(date(2026, 8, 4), 14) if approved else None,
        published_at=at(date(2026, 8, 5), 9) if status in ("PUBLISHED", "INACTIVE", "DISCONTINUED") else None,
        discontinued_at=at(date(2026, 9, 30), 17) if status == "DISCONTINUED" else None,
        # SCRUM-461: a discontinued product carries why, a suspended one when and why (ck_products_discontinue_reason,
        # ck_products_inactive).
        discontinue_reason="Ngừng sản xuất, chuyển sang ống hút giấy" if status == "DISCONTINUED" else None,
        suspended_at=at(date(2026, 9, 20), 11) if status == "INACTIVE" else None,
        suspend_reason="Tạm ngừng để kiểm tra chất lượng lô hàng" if status == "INACTIVE" else None,
        rejection_reason=None, created_at=P_CREATED)
    add("product.product_categories", id=uid(f"pc:{pcode}"), product_id=pid, category_id=uid("cat:" + cat),
        is_primary=True, created_at=P_CREATED)
    if cat in ("PAPER-CUPS", "PLASTIC-CUPS"):  # also listed under the parent, as a shop would
        add("product.product_categories", id=uid(f"pc:{pcode}:CUPS"), product_id=pid, category_id=uid("cat:CUPS"),
            is_primary=False, created_at=P_CREATED)
    for ai, (a, values) in enumerate(axes):
        add("product.product_attributes", id=uid(f"pa:{pcode}:{a}"), product_id=pid, attribute_id=ATTR_ID(a),
            role="VARIANT_AXIS", sort_order=ai, created_at=P_CREATED)
        for v in values:
            add("product.product_attribute_options", id=uid(f"po:{pcode}:{a}:{v}"),
                product_attribute_id=uid(f"pa:{pcode}:{a}"), attribute_value_id=uid(f"val:{a}:{v}"),
                created_at=P_CREATED)
    for di, (a, v) in enumerate(descr.items()):
        add("product.product_attributes", id=uid(f"pa:{pcode}:{a}"), product_id=pid, attribute_id=ATTR_ID(a),
            role="DESCRIPTIVE", sort_order=10 + di, created_at=P_CREATED)
        dt_ = ATTRS[a][1]
        add("product.product_descriptive_values", id=uid(f"pd:{pcode}:{a}"),
            product_attribute_id=uid(f"pa:{pcode}:{a}"),
            attribute_value_id=uid(f"val:{a}:{v}") if dt_ in ("SELECT", "COLOR") else None,
            value_text=v if dt_ == "TEXT" else None, value_number=v if dt_ == "NUMBER" else None,
            value_boolean=v if dt_ == "BOOLEAN" else None, created_at=P_CREATED)
    combos = [[]]
    for a, values in axes:
        combos = [c + [(a, v)] for c in combos for v in values]
    uom, dims, cls, flags = item
    for vi, combo in enumerate(combos):
        sku = pcode + "-" + "-".join(v for _, v in combo)
        vstatus = {"DRAFT": "DRAFT", "PENDING_APPROVAL": "DRAFT"}.get(status, "ACTIVE")
        if "obsolete" in flags:
            vstatus = "OBSOLETE"
        if "blocked" in flags:
            vstatus = "BLOCKED"
        labels = " / ".join(next(lab for c, lab, *_ in ATTRS[a][3] if c == v) for a, v in combo)
        vname = f"{pname} - {labels}"
        vid = uid("variant:" + sku)
        add("product.variants", id=vid, product_id=pid, sku=sku, name=vname, status=vstatus, is_default=vi == 0,
            attribute_signature=";".join(f"{a}={v}" for a, v in combo), position=vi,
            obsoleted_at=at(date(2026, 9, 30), 17) if vstatus == "OBSOLETE" else None, created_at=P_CREATED)
        for a, v in combo:
            add("product.variant_attribute_values", id=uid(f"vav:{sku}:{a}"), variant_id=vid,
                product_attribute_option_id=uid(f"po:{pcode}:{a}:{v}"), created_at=P_CREATED)
        published = status == "PUBLISHED"
        add("product.media", id=uid("media:" + sku), variant_id=vid, kind="IMAGE",
            url=f"https://placehold.co/800x800/png?text={sku}", alt_text=vname, sort_order=0, is_primary=True,
            is_published=published or status in ("INACTIVE", "DISCONTINUED"),
            published_at=at(date(2026, 8, 5), 9) if published or status in ("INACTIVE", "DISCONTINUED") else None,
            published_by=U[EDITOR] if published or status in ("INACTIVE", "DISCONTINUED") else None,
            created_at=P_CREATED)
        if vi == 0 and published:
            add("product.media", id=uid("media2:" + sku), variant_id=vid, kind="IMAGE",
                url=f"https://placehold.co/800x800/png?text={pcode}+mockup", alt_text=f"{pname} - ảnh thực tế",
                sort_order=1, is_primary=False, is_published=True, published_at=at(date(2026, 8, 5), 9),
                published_by=U[EDITOR], created_at=P_CREATED)
        if kind == "CUSTOMIZABLE":
            tid = uid("tpl:" + sku)
            add("product.customization_templates", id=tid, variant_id=vid, code="TPL-" + sku,
                name=f"Khuôn in {vname}", template_2d_url=f"https://placehold.co/1200x600/png?text=DIELINE+{sku}",
                model_3d_url=f"https://cdn.stockflow.example/3d/{sku.lower()}.glb" if pcode.startswith("LY") else None,
                min_dpi=300, color_mode="CMYK", allowed_file_formats=("PDF", "AI", "PNG"), max_file_size_mb=50,
                max_colors=4 if pcode.startswith("LY") else None, allowed_font_codes=None, created_at=P_CREATED)
            areas = [("WRAP", "Thân ly (quấn quanh)", 0, 0, 240, 90)] if pcode.startswith(("LY", "TAY")) else \
                [("FRONT", "Mặt trước", 10, 10, 180, 120), ("BACK", "Mặt sau", 10, 140, 180, 120)] \
                if pcode.startswith("TUI") else [("LID", "Nắp hộp", 5, 5, 200, 150), ("SIDE", "Cạnh hộp", 5, 160, 200, 50)]
            for ai, (ac, an, x, y, w, h) in enumerate(areas):
                add("product.print_areas", id=uid(f"area:{sku}:{ac}"), template_id=tid, code=ac, name=an, x_mm=x,
                    y_mm=y, width_mm=w, height_mm=h, safe_margin_mm=3, bleed_mm=2, sort_order=ai,
                    created_at=P_CREATED)
        VARIANTS.append(dict(sku=sku, id=vid, product=pcode, price=prices[vi], uom=uom, dims=dims, cls=cls,
                             flags=flags, kind=kind, pstatus=status, vstatus=vstatus, name=vname, idx=len(VARIANTS)))
VAR = {v["sku"]: v for v in VARIANTS}
SELLABLE = [v for v in VARIANTS if v["pstatus"] == "PUBLISHED" and v["price"]]
STOCKED = [v for v in VARIANTS if v["vstatus"] == "ACTIVE" and (v["pstatus"] == "PUBLISHED" or "lot" in v["flags"])]

# ============================================================================ 4. suppliers
SUPPLIERS = [
    # code, name, legal, tax, terms days, tolerance, lead, subcontractor, channel, status, product prefixes
    ("GIAYVN", "Giấy Việt", "Công ty CP Bao bì Giấy Việt", "0312345601", 30, 5, 10, False, "EMAIL", "ACTIVE",
     ["LYG-", "TAYCAM", "KHAY-LY"]),
    ("NHUAMN", "Nhựa Miền Nam", "Công ty TNHH Nhựa Miền Nam", "0312345602", 30, 3, 7, False, "EMAIL", "ACTIVE",
     ["LYN-", "NAP-PET", "OHN-PP"]),
    ("KRAFTPACK", "Kraft Pack", "Công ty TNHH Kraft Pack Việt Nam", "0312345603", 45, 5, 14, False, "API", "ACTIVE",
     ["HOP-COM", "HOP-PIZZA", "TUI-", "HOP-SALAD"]),
    ("MUCIN", "Mực in Sakura", "Công ty TNHH Mực in Sakura Việt Nam", "0312345604", 30, 0, 21, False, "EMAIL",
     "ACTIVE", ["MUC-"]),
    ("ONGHUT", "Ống hút Xanh", "Hợp tác xã Ống hút Xanh", "0312345605", 15, 10, 7, False, "EMAIL", "ACTIVE",
     ["OHG"]),
    ("HOPCAOCAP", "Hộp Cao Cấp", "Công ty CP Hộp Cao Cấp Bình Dương", "0312345606", 30, 2, 20, False, "EMAIL",
     "ACTIVE", ["HOP-QUA", "HOP-TRUNGTHU", "NAP-GIAY"]),
    ("INSG", "Xưởng in Sài Gòn", "Công ty TNHH In ấn Sài Gòn", "0312345607", 15, 0, 5, True, "EMAIL", "ACTIVE", []),
    ("INHN", "In Hà Nội", "Công ty CP In Hà Nội", "0312345608", 15, 0, 6, True, "API", "ACTIVE", []),
    ("BAOBIXYZ", "Bao bì XYZ", "Công ty TNHH Bao bì XYZ", "0312345609", 30, 5, 10, False, "EMAIL", "INACTIVE", []),
    ("NCCVIPHAM", "NCC vi phạm hợp đồng", "Công ty TNHH Thương mại ABC", "0312345610", 30, 0, 10, False, "EMAIL",
     "BLACKLISTED", []),
]
SUP = {}
DEFAULT_SUP = {}
for i, (code, name, legal, tax, days, tol, lead, sub, ch, status, prefixes) in enumerate(SUPPLIERS):
    SUP[code] = uid("supplier:" + code)
    add("procurement.suppliers", id=SUP[code], code=code, name=name, legal_name=legal, tax_id=tax, status=status,
        currency="VND", payment_terms=f"NET{days}", incoterms="DAP", over_receipt_tolerance=tol, lead_time_days=lead,
        bank_account_name=legal.upper(), bank_account_number=f"0071000{100000 + i * 7919}",
        bank_name=["Vietcombank", "BIDV", "Techcombank", "ACB"][i % 4],
        note="NCC chặn giao dịch do giao hàng lỗi nhiều lần" if status == "BLACKLISTED" else None,
        is_print_subcontractor=sub, loss_tolerance_percent=3 if sub else 2, payment_term_days=days,
        communication_channel=ch, api_endpoint=f"https://api.{code.lower()}.example/v1/purchase-orders"
        if ch == "API" else None, created_at=at(date(2026, 8, 1), 10))
    add("procurement.supplier_contacts", id=uid(f"contact:{code}:1"), supplier_id=SUP[code],
        full_name=["Nguyễn Văn An", "Trần Thị Bích", "Lê Hoàng Cường", "Phạm Thị Dung", "Võ Minh Em",
                   "Đinh Thị Phương", "Hồ Văn Giang", "Lưu Thị Hạnh", "Mạc Văn Ích", "Tăng Thị Kim"][i],
        phone=f"09{(10000000 + i * 1234567) % 100000000:08d}", email=f"sales@{code.lower()}.example",
        position="Trưởng phòng kinh doanh", is_primary=True, created_at=at(date(2026, 8, 1), 10))
    if i % 2 == 0:
        add("procurement.supplier_contacts", id=uid(f"contact:{code}:2"), supplier_id=SUP[code],
            full_name="Bộ phận kế toán", phone=None, email=f"ketoan@{code.lower()}.example", position="Kế toán",
            is_primary=False, created_at=at(date(2026, 8, 1), 10))
    hcm = i % 3 != 1
    add("procurement.supplier_addresses", id=uid(f"saddr:{code}:office"), supplier_id=SUP[code], type="OFFICE",
        line1=f"{12 + i * 7} Đường số {i + 3}", line2=None,
        ward="Phường Tân Thuận" if hcm else "Phường Cầu Giấy", ward_code="27097" if hcm else "00166",
        province="TP. Hồ Chí Minh" if hcm else "TP. Hà Nội", province_code="79" if hcm else "01",
        is_default=True, created_at=at(date(2026, 8, 1), 10))
    if not sub:
        add("procurement.supplier_addresses", id=uid(f"saddr:{code}:wh"), supplier_id=SUP[code], type="WAREHOUSE",
            line1=f"Lô {chr(65 + i)}{i + 1}, KCN Tân Tạo" if hcm else f"Lô {chr(65 + i)}{i + 1}, KCN Quang Minh",
            ward="Phường Tân Tạo" if hcm else "Xã Quang Minh", ward_code="27460" if hcm else "08791",
            province="TP. Hồ Chí Minh" if hcm else "TP. Hà Nội", province_code="79" if hcm else "01",
            is_default=True, created_at=at(date(2026, 8, 1), 10))
    for v in VARIANTS:
        if any(v["product"].startswith(p) for p in prefixes):
            DEFAULT_SUP[v["sku"]] = code

ITEM = {}  # sku -> inventory item id
for v in VARIANTS:
    uom, (w, l, wd, h), cls, flags = v["uom"], v["dims"], v["cls"], v["flags"]
    lot = "lot" in flags
    ITEM[v["sku"]] = uid("item:" + v["sku"])
    sup = DEFAULT_SUP.get(v["sku"])
    sets = dict(id=ITEM[v["sku"]], unit_of_measure=uom, barcode=f"893{8000000000 + v['idx'] * 137:010d}",
                weight_kg=round(w * 0.92, 3), length_cm=l - 2, width_cm=wd - 2, height_cm=h - 2,
                package_weight_kg=w, package_length_cm=l, package_width_cm=wd, package_height_cm=h, package_count=1,
                pack_size=1, storage_class=cls, lot_tracked=lot, expiry_tracked=lot, serial_tracked=False,
                max_qty=400 if not lot else 120, reorder_point=40 if not lot else 20,
                safety_stock=15 if not lot else 8, default_supplier_id=SUP[sup] if sup else None,
                qc_required="qc" in flags, removal_strategy="FEFO" if lot else "FIFO",
                max_shelf_life_days=None, policy_configured=v["vstatus"] == "ACTIVE",
                requires_adult_signature=False,
                shipping_restriction_note="Hàng nguy hiểm nhóm 3 - không gửi đường hàng không" if cls == "HAZMAT"
                else ("Hàng dễ vỡ - xếp tối đa 3 tầng" if cls == "FRAGILE" else None),
                last_modified_at=at(date(2026, 8, 2), 15), last_modified_by=BY)
    POST.append(("procurement.supplier_addresses",
                 "UPDATE inventory.inventory_items SET " + ", ".join(f"{k} = {q(x)}" for k, x in sets.items())
                 + f" WHERE sku = {q(v['sku'])};"))

# Packing units for the load-planning demo (SCRUM-287 PR P): the item's base unit is its carton (above), a pallet
# is the larger packing unit. Three ways the packaging-spec resolver picks the carton for an estimate:
#   default shipping flag on the pallet        LYG-1L-12OZ, LYG-1L-16OZ   (level 1)
#   no flag: the largest packing unit wins     LYG-2L-12OZ                (level 2)
#   flag on the base unit: ship the carton     NAP-GIAY-D80-WHITE         (level 1, the pallet is ignored)
# A pallet weighs its cartons plus 25 kg of wood; sizes in cm, weights in kg. No random draw.
PALLET_DEMO = [
    # sku, cartons per pallet, (length, width, height) cm, flag on the pallet, flag on the base unit
    ("LYG-1L-12OZ", 24, (120, 100, 150), True, False),
    ("LYG-1L-16OZ", 24, (120, 100, 150), True, False),
    ("LYG-2L-12OZ", 20, (120, 100, 155), False, False),
    ("NAP-GIAY-D80-WHITE", 36, (120, 100, 150), False, True),
]
for sku, per_pallet, (pl, pw, ph), flag_pallet, flag_base in PALLET_DEMO:
    carton_kg = VAR[sku]["dims"][0]
    POST.append(("procurement.supplier_addresses",
                 "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, "
                 "weight_kg, length_cm, width_cm, height_cm, default_purchase, active, default_shipping, created_by) "
                 f"SELECT {q(uid('uom:' + sku + ':PALLET'))}, i.id, 'PALLET', {per_pallet}, FALSE, "
                 f"{q(round(per_pallet * carton_kg + 25, 3))}, {pl}, {pw}, {ph}, FALSE, TRUE, {q(flag_pallet)}, "
                 f"{q(BY)} FROM inventory.inventory_items i WHERE i.sku = {q(sku)};"))
    if flag_base:
        POST.append(("procurement.supplier_addresses",
                     "UPDATE inventory.uom_conversions SET default_shipping = TRUE WHERE is_base AND "
                     f"inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = {q(sku)});"))

for v in VARIANTS:
    code = DEFAULT_SUP.get(v["sku"])
    if not code or v["vstatus"] != "ACTIVE":
        continue
    base = v["price"] or 900000
    cost = money(base * random.uniform(0.55, 0.68) / 1000) * 1000 if v["uom"] == "CTN" else 1250000
    v["cost"] = cost
    si = uid(f"si:{code}:{v['sku']}")
    add("procurement.supplier_items", id=si, supplier_id=SUP[code], inventory_item_id=ITEM[v["sku"]],
        supplier_sku_code=f"{code[:3]}-{v['idx']:04d}", current_price=cost, moq=5 if v["uom"] == "CTN" else 2,
        pack_size=1, lead_time_days=10, created_at=at(date(2026, 8, 1), 11))
    # Preferred in the catalog = the item's default_supplier_id set above (V20261019000600), no flag of its own.
    # kltn-docs 01 "Thông tin mua hàng" (V20261019000200): the item's own reference purchase price, lead time
    # and MOQ, here those its default supplier quotes. No random draw: the rest of the data must not shift.
    POST.append(("procurement.supplier_items",
                 f"UPDATE inventory.inventory_items SET reference_purchase_price = {q(cost)}, "
                 f"reference_purchase_currency = 'VND', lead_time_days = 10, "
                 f"moq = {q(5 if v['uom'] == 'CTN' else 2)} WHERE sku = {q(v['sku'])};"))
    add("procurement.supplier_item_prices", id=uid(f"sip:{code}:{v['sku']}:0"), supplier_item_id=si,
        unit_price=money(cost * 0.96 / 1000) * 1000, effective_from=at(date(2026, 1, 1), 0),
        effective_to=at(date(2026, 9, 1), 0), created_at=at(date(2026, 8, 1), 11))
    add("procurement.supplier_item_prices", id=uid(f"sip:{code}:{v['sku']}:1"), supplier_item_id=si,
        unit_price=cost, effective_from=at(date(2026, 9, 1), 0), effective_to=None,
        created_at=at(date(2026, 8, 1), 11))
# second, non-preferred source for the paper cups
for v in VARIANTS:
    if v["product"] in ("LYG-1L", "LYG-2L") and v["vstatus"] == "ACTIVE":
        add("procurement.supplier_items", id=uid(f"si:BAOBIXYZ:{v['sku']}"), supplier_id=SUP["BAOBIXYZ"],
            inventory_item_id=ITEM[v["sku"]], supplier_sku_code=f"XYZ-{v['idx']:04d}",
            current_price=money(v["cost"] * 1.05 / 1000) * 1000, moq=20, pack_size=1, lead_time_days=15,
            created_at=at(date(2026, 8, 1), 11))

for role, amount in (("PROCUREMENT_STAFF", 50000000), ("WAREHOUSE_MANAGER", 300000000)):
    add("procurement.approval_limits", id=uid("limit:" + role), role_id=ROLE(role), currency="VND",
        max_po_amount=amount, effective_from=date(2026, 1, 1), effective_to=None, created_at=at(date(2026, 8, 1), 11))

# ============================================================================ 5. customers
SEGMENTS = [("CHAIN", "Chuỗi F&B", "Chuỗi cà phê, trà sữa nhiều chi nhánh"),
            ("DISTRIBUTOR", "Đại lý bao bì", "Đại lý phân phối lại, giá sỉ cấp 1"),
            ("HORECA", "Nhà hàng - Khách sạn", "Nhà hàng, khách sạn, bếp ăn"),
            ("SME", "Cửa hàng nhỏ", "Quán độc lập, cửa hàng bánh, tiệm hoa")]
for code, name, desc in SEGMENTS:
    add("customer.segment", id=uid("seg:" + code), code=code, name=name, description=desc,
        created_at=at(date(2026, 8, 1), 8))

PROVINCES = {
    "HN": [("01", "TP. Hà Nội", [("00004", "Phường Ba Đình"), ("00166", "Phường Cầu Giấy"),
                                  ("00091", "Phường Hoàn Kiếm"), ("00220", "Phường Đống Đa")]),
           ("31", "TP. Hải Phòng", [("11311", "Phường Hồng Bàng")])],
    "BD": [("79", "TP. Hồ Chí Minh", [("26734", "Phường Bến Thành"), ("27097", "Phường Tân Thuận"),
                                       ("26890", "Phường Thủ Đức"), ("25747", "Phường Thuận An")]),
           ("75", "Tỉnh Đồng Nai", [("26041", "Phường Trấn Biên")])],
    "DN": [("48", "TP. Đà Nẵng", [("20194", "Phường Hải Châu"), ("20263", "Phường Sơn Trà")])],
}
CUSTOMERS = [
    # code, company, contact, segment, region, status, terms (None | ('DEPOSIT', pct) | ('CREDIT', limit, days))
    ("caphemoc", "Công ty TNHH Cà phê Mộc", "Nguyễn Thanh Tùng", "CHAIN", "BD", "ACTIVE", ("CREDIT", 300000000, 30)),
    ("trasuamay", "Công ty CP Trà sữa Mây", "Phạm Ngọc Hân", "CHAIN", "HN", "ACTIVE", ("CREDIT", 200000000, 30)),
    ("bepnhalam", "Nhà hàng Bếp Nhà Lam", "Lam Thị Thu", "HORECA", "HN", "ACTIVE", ("DEPOSIT", 30)),
    ("pizza4u", "Công ty TNHH Pizza 4U", "Trần Gia Bảo", "CHAIN", "BD", "ACTIVE", ("CREDIT", 150000000, 15)),
    ("banhmisaigon", "Hộ kinh doanh Bánh mì Sài Gòn", "Lê Văn Phúc", "SME", "BD", "ACTIVE", None),
    ("hoatuoi", "Tiệm hoa Hoa Tươi", "Đỗ Thị Hoa", "SME", "HN", "ACTIVE", None),
    ("daily-tanphu", "Đại lý bao bì Tân Phú", "Vương Văn Khánh", "DISTRIBUTOR", "BD", "ACTIVE",
     ("CREDIT", 500000000, 45)),
    ("daily-longbien", "Đại lý bao bì Long Biên", "Hà Minh Quân", "DISTRIBUTOR", "HN", "ACTIVE", ("DEPOSIT", 20)),
    ("khachsanbien", "Khách sạn Biển Xanh", "Ngô Thị Thanh", "HORECA", "DN", "ACTIVE", ("DEPOSIT", 30)),
    ("cafebien", "Quán Cà phê Biển", "Phan Văn Lộc", "SME", "DN", "ACTIVE", None),
    ("tiembanhngot", "Tiệm bánh Ngọt Ngào", "Huỳnh Thị Mỹ", "SME", "BD", "ACTIVE", None),
    ("comtamcali", "Chuỗi Cơm tấm Cali", "Đặng Quốc Việt", "CHAIN", "BD", "ACTIVE", ("CREDIT", 120000000, 30)),
    ("bubbletea", "Công ty TNHH Bubble Tea Hà Nội", "Kim Ngọc Anh", "CHAIN", "HN", "ACTIVE", ("DEPOSIT", 30)),
    ("nhahangsen", "Nhà hàng Sen Vàng", "Trịnh Văn Hòa", "HORECA", "HN", "ACTIVE", None),
    ("sieuthimini", "Chuỗi Siêu thị mini Xanh", "Bạch Thị Lệ", "CHAIN", "BD", "ACTIVE", ("CREDIT", 250000000, 30)),
    ("quanbunbo", "Quán Bún bò Huế O Xuân", "Tôn Nữ Xuân", "SME", "DN", "ACTIVE", None),
    ("kemtuoi", "Tiệm kem Tuổi Thơ", "Lục Văn Tâm", "SME", "HN", "ACTIVE", None),
    ("daily-danang", "Đại lý bao bì Đà Nẵng", "Âu Dương Phong", "DISTRIBUTOR", "DN", "ACTIVE", ("DEPOSIT", 20)),
    ("bakeryhouse", "Công ty TNHH Bakery House", "Thái Thị Ngân", "CHAIN", "HN", "ACTIVE", ("CREDIT", 100000000, 30)),
    ("trasuanho", "Quán Trà sữa Nhỏ", "Lâm Bảo Ngọc", "SME", "BD", "ACTIVE", None),
    ("cateringviet", "Công ty CP Suất ăn Việt", "Cù Văn Thành", "HORECA", "BD", "ACTIVE", ("CREDIT", 80000000, 15)),
    ("hotelhanoi", "Khách sạn Hà Nội Pearl", "Giáp Thị Oanh", "HORECA", "HN", "ACTIVE", None),
    ("ngungban", "Công ty TNHH Ngừng Hợp Tác", "Ông Văn Ngừng", "SME", "BD", "INACTIVE", None),
    ("bichan", "Hộ kinh doanh Nợ Xấu", "Bà Thị Chặn", "SME", "HN", "BLOCKED", None),
]
CUST = {}
for i, (code, company, contact, seg, region, status, terms) in enumerate(CUSTOMERS):
    cid = uid("customer:" + code)
    created = at(date(2026, 8, 1) + timedelta(days=i % 9), 10)
    prov_code, prov_name, wards = PROVINCES[region][i % len(PROVINCES[region])] \
        if i % 5 else PROVINCES[region][0]
    ward_code, ward_name = wards[i % len(wards)]
    phone = f"09{(12345678 + i * 2357911) % 100000000:08d}"
    email = f"muahang@{code}.example"
    has_user = i < 12
    if has_user:
        user("kh." + code, contact, email=f"kh.{code}@{code}.example", created=created,
             last_login_at=at(TODAY - timedelta(days=i % 5), 20))
        add("identity.user_role", id=uid(f"ur:kh.{code}:CUSTOMER"), user_id=U["kh." + code],
            role_id=ROLE("CUSTOMER"), created_at=created)
    add("customer.customer", id=cid, full_name=company, email=email, phone=phone, segment_id=uid("seg:" + seg),
        status=status, user_id=U["kh." + code] if has_user else None, created_at=created)
    street = f"{10 + i * 3} {['Lê Lợi', 'Nguyễn Huệ', 'Trần Hưng Đạo', 'Hai Bà Trưng', 'Lý Thường Kiệt'][i % 5]}"
    addr = dict(recipient_name=contact, phone=phone, line1=street, line2=None, ward=ward_name, ward_code=ward_code,
                province=prov_name, province_code=prov_code, country="VN", postal_code=None)
    add("customer.address", id=uid(f"addr:{code}:ship"), customer_id=cid, type="SHIPPING", is_default=True,
        created_at=created, **addr)
    add("customer.address", id=uid(f"addr:{code}:bill"), customer_id=cid, type="BILLING", is_default=True,
        created_at=created, **{**addr, "recipient_name": company, "line2": "Phòng kế toán"})
    if seg == "CHAIN":
        w2c, w2n = wards[(i + 1) % len(wards)]
        add("customer.address", id=uid(f"addr:{code}:ship2"), customer_id=cid, type="SHIPPING", is_default=False,
            created_at=created, **{**addr, "line1": f"{50 + i} Võ Văn Tần (chi nhánh 2)", "ward": w2n,
                                   "ward_code": w2c, "recipient_name": "Quản lý chi nhánh 2"})
    if terms:
        kind = terms[0]
        profile = add("customer.credit_profile", id=uid("credit:" + code), customer_id=cid, allow_prepaid=True,
            allow_deposit=kind == "DEPOSIT" or kind == "CREDIT" and i % 2 == 0,
            allow_credit=kind == "CREDIT", default_payment_term=kind,
            deposit_percent=terms[1] if kind == "DEPOSIT" else (30 if kind == "CREDIT" and i % 2 == 0 else None),
            credit_limit=terms[1] if kind == "CREDIT" else None, credit_term_days=terms[2] if kind == "CREDIT" else None,
            currency="VND", approved_by=U["ketoan"], approved_at=created + timedelta(days=2),
            note="Hạn mức duyệt theo doanh số 6 tháng" if kind == "CREDIT" else "Khách mới, đặt cọc trước khi sản xuất",
            created_at=created + timedelta(days=2))
        # G2-3: a deposit customer pays the balance before delivery unless an order of theirs lets the carrier
        # collect it (set below, when such an order is placed).
        profile["deposit_balance_policy"] = "BEFORE_DELIVERY" if profile["allow_deposit"] else None
    CUST[code] = dict(id=cid, company=company, contact=contact, seg=seg, wh=region, status=status, terms=terms,
                      email=email, phone=phone, addr=addr, user=U.get("kh." + code),
                      profile=profile if terms else None)

# selling prices
SEG_DISCOUNT = {"DISTRIBUTOR": 0.92, "CHAIN": 0.96}
for v in SELLABLE:
    add("catalog.pricing_rule", id=uid("price:base:" + v["sku"]), name="BASE:" + v["sku"], sku=v["sku"],
        segment_id=None, price=v["price"], currency="VND", priority=0, active=True, created_at=at(date(2026, 8, 5), 9))
for seg, f in SEG_DISCOUNT.items():
    for v in SELLABLE:
        if v["product"] in ("LYG-1L", "LYG-2L", "LYN-PET", "NAP-GIAY", "OHG", "HOP-COM"):
            add("catalog.pricing_rule", id=uid(f"price:{seg}:{v['sku']}"), name=f"{seg}:{v['sku']}", sku=v["sku"],
                segment_id=uid("seg:" + seg), price=money(v["price"] * f / 1000) * 1000, currency="VND",
                priority=10, active=True, created_at=at(date(2026, 8, 5), 9))
add("catalog.pricing_rule", id=uid("price:old-promo"), name="Giá cũ tháng 7 - ly giấy 12oz", sku="LYG-1L-12OZ",
    segment_id=None, price=500000, currency="VND", priority=5, active=False, created_at=at(date(2026, 7, 1), 9))


def unit_price(cust, sku):
    f = SEG_DISCOUNT.get(cust["seg"])
    if f and VAR[sku]["product"] in ("LYG-1L", "LYG-2L", "LYN-PET", "NAP-GIAY", "OHG", "HOP-COM"):
        return money(VAR[sku]["price"] * f / 1000) * 1000
    return VAR[sku]["price"]


for code, name, typ, val, s, e, active in [
    ("KHAITRUONG10", "Giảm 10% đơn đầu tiên", "PERCENTAGE", 10, date(2026, 8, 1), date(2026, 12, 31), True),
    ("FREESHIP500K", "Giảm 500.000đ phí vận chuyển đơn từ 20 thùng", "FIXED_AMOUNT", 500000, date(2026, 9, 1),
     date(2026, 11, 30), True),
    ("TRUNGTHU2026", "Ưu đãi mùa Trung thu 2026", "PERCENTAGE", 15, date(2026, 8, 15), date(2026, 9, 30), False),
    ("TET2027", "Đặt sớm ly Tết 2027", "PERCENTAGE", 8, date(2026, 11, 1), date(2027, 1, 31), True),
]:
    add("catalog.promotion", id=uid("promo:" + code), code=code, name=name, type=typ, value=val,
        starts_at=at(s, 0), ends_at=at(e, 23, 59), active=active, created_at=at(date(2026, 8, 1), 9))

for pcode, p in PROD.items():
    if p["status"] != "PUBLISHED":
        continue
    add("catalog.product_listing", product_id=p["id"], slug=p["slug"], seo_title=f"{p['name']} giá sỉ",
        seo_description=f"{p['name']} - in logo theo yêu cầu, giao toàn quốc.", revision=0, projected_revision=0,
        enabled=True, ever_published=True, published_title=p["name"],
        published_description=f"{p['name']}. Bán theo thùng cho doanh nghiệp.",
        published_seo_title=f"{p['name']} giá sỉ",
        published_seo_description=f"{p['name']} - in logo theo yêu cầu, giao toàn quốc.",
        last_attempt_at=at(date(2026, 8, 5), 9))
for v in SELLABLE:
    add("catalog.catalog_entry", id=uid("entry:" + v["sku"]), sku=v["sku"], title=v["name"],
        slug=v["sku"].lower(), description=f"{v['name']} - bán theo thùng.", price=v["price"], currency="VND",
        atp=0, published=True, seo_title=v["name"], seo_description=f"{v['name']} giá sỉ.",
        product_id=PROD[v["product"]]["id"], source_revision=0, created_at=at(date(2026, 8, 5), 9))

# ============================================================================ 6. stock model
STOCK = []   # stock rows in the order they were created


class Stock:
    """One row of inventory.stock_item. Stock arriving with the identity of an existing row (sku,
    location, lot, receipt time: uk_stock_item_sku_location_lot) joins that row."""

    def __new__(cls, sku, loc, qty, received, status="AVAILABLE", lot=None, expiry=None, key=None):
        for s in STOCK:
            if (s.sku, s.loc, s.lot, s.received) == (sku, loc, lot, received):
                assert s.status == status, (sku, loc, s.status, status)
                s.on_hand += qty
                return s
        self = super().__new__(cls)
        self.id = uid("stock:" + (key or f"{sku}:{loc}:{lot}:{received.isoformat()}"))
        self.sku, self.loc, self.on_hand, self.reserved, self.status = sku, loc, qty, 0, status
        self.lot, self.expiry, self.received = lot, expiry, received
        STOCK.append(self)
        return self

    @property
    def free(self):
        return self.on_hand - self.reserved if self.status == "AVAILABLE" else 0


MOVES = []


# The schema allows a richer ledger than the application reads: StockMovement.MovementType and
# ReferenceType hold only what the code writes, and a row outside them fails every ledger query with
# "No enum constant". So rows are written the way the application writes them: a putaway is a MOVE,
# receiving a transfer is a RECEIPT, a count correction or a scrap is an ADJUSTMENT, a returned parcel is
# RETURN_IN naming its return line (SCRUM-243), and a confirmed pick line writes one PICK row naming the line
# (reference PICK_LINE, SCRUM-267), as the application does since V20261014001000. The ledger reconciles to
# stock on hand.
APP_KIND = {"PUTAWAY": "MOVE", "COUNT_CORRECTION": "ADJUSTMENT", "SCRAP": "ADJUSTMENT", "TRANSFER_IN": "RECEIPT"}


def move(kind, sku, qty, ref_type, ref_id, when, actor, frm=None, to=None, lot=None, fs=None, ts_=None, reason=None):
    kind = APP_KIND.get(kind, kind)
    MOVES.append(dict(id=uid(f"mv:{len(MOVES)}:{kind}:{ref_id}"), movement_type=kind, sku=sku, lot_number=lot,
                      from_location_code=frm, to_location_code=to, quantity=qty, from_status=fs, to_status=ts_,
                      reference_type=ref_type, reference_id=ref_id, reason=reason,
                      actor_id=U[actor] if actor else None,
                      occurred_at=when, created_at=when))


def staff_of(p):
    return {"HN": ("kho.hn1", "kho.hn2", "quanly.hn", "qc.hn"), "BD": ("kho.bd1", "kho.bd2", "quanly.bd", "qc.bd"),
            "DN": ("kho.dn1", "kho.dn1", "quanly.bd", "qc.bd")}[p]


# ============================================================================ 7. purchase orders
SEQ = {}


def next_no(prefix, d, width=4):
    k = (prefix, d)
    SEQ[k] = SEQ.get(k, 0) + 1
    return f"{prefix}-{d.strftime('%Y%m%d')}-{SEQ[k]:0{width}d}"


PO = {}


def make_po(key, p, sup, d, status, lines, submitter="muahang1", approver=None, expected_days=10, note=None,
            shipping=0, confirm_status=None, close_kind=None, close_reason=None, cancel_reason=None,
            rejected_first=False, po_type="STANDARD", production_order_id=None, source_proposal=None, number=None,
            times=None):
    """lines: [(sku, qty, unit price)]. Returns the PO dict with line ids."""
    pid = uid("po:" + key)
    approver = approver or ("quanly.hn" if p == "HN" else "quanly.bd")
    number = number or next_no("PO", d, 6)
    order_dt = at(d, 9, 15)
    sub_dt, app_dt, conf_dt = order_dt + timedelta(hours=1), order_dt + timedelta(hours=4), order_dt + timedelta(days=1)
    if times:
        # A SUBCONTRACT order is raised by the conversion that needs it (raised_with), not at the start of a
        # purchasing day.
        order_dt, sub_dt, app_dt, conf_dt = times
    plines, subtotal, tax = [], 0, 0
    for n, (sku, qty, price) in enumerate(lines, 1):
        ls = qty * price
        lt = money(ls * 10 / 100)
        subtotal, tax = subtotal + ls, tax + lt
        plines.append(dict(id=uid(f"po:{key}:line:{n}"), no=n, sku=sku, qty=qty, price=price, sub=ls, tax=lt,
                           total=ls + lt, received=0))
    total = subtotal + tax + shipping
    reached = lambda s: ["DRAFT", "PENDING_APPROVAL", "APPROVED", "CONFIRMED", "PARTIALLY_RECEIVED", "RECEIVED",
                         "CLOSED"].index(s) if s != "CANCELLED" else 3
    lvl = reached(status)
    revs = []
    if status != "DRAFT":
        revs.append(uid(f"po:{key}:rev:0"))
        if rejected_first:
            revs.append(uid(f"po:{key}:rev:1"))
    active = revs[-1] if revs and status not in ("PENDING_APPROVAL",) else None
    pending = revs[-1] if status == "PENDING_APPROVAL" else None
    if status == "CANCELLED":
        active, pending = revs[-1], None
    supplier_conf = confirm_status or ("CONFIRMED" if lvl >= 3 and status != "CANCELLED" else
                                       "PENDING" if status == "APPROVED" else "NOT_SENT")
    row = add("procurement.purchase_orders", id=pid, po_number=number, revision_no=len(revs) - 1 if revs else 0,
              status=status, supplier_id=SUP[sup], warehouse_id=WH[p], currency="VND", order_date=d,
              expected_date=d + timedelta(days=expected_days), payment_terms="NET30", incoterms="DAP",
              subtotal=subtotal, discount_total=0, tax_total=tax, shipping_fee=shipping, total_amount=total,
              active_revision_id=active, pending_revision_id=pending, source_proposal_id=source_proposal,
              note=note, close_kind=close_kind, close_reason=close_reason, cancel_reason=cancel_reason,
              submitted_at=sub_dt if lvl >= 1 else None, submitted_by=U[submitter] if lvl >= 1 else None,
              approved_at=app_dt if lvl >= 2 and status != "CANCELLED" else None,
              approved_by=U[approver] if lvl >= 2 and status != "CANCELLED" else None,
              confirmed_at=conf_dt if lvl >= 3 and status != "CANCELLED" else None,
              confirmed_by=U[submitter] if lvl >= 3 and status != "CANCELLED" else None,
              closed_at=None, closed_by=None, po_type=po_type, production_order_id=production_order_id,
              payment_term_days=30, lead_time_days=expected_days, supplier_confirmation_status=supplier_conf,
              supplier_responded_at=conf_dt if supplier_conf in ("CONFIRMED", "REJECTED") else None,
              supplier_reference=f"SO-{sup}-{d.strftime('%m%d')}" if supplier_conf == "CONFIRMED" else None,
              supplier_response_note="Xác nhận giao đúng hẹn" if supplier_conf == "CONFIRMED" else None,
              created_at=order_dt, created_by=BY)
    add("procurement.purchase_order_events", id=uid(f"po:{key}:ev:created"), po_id=pid, po_revision_id=None,
        action="CREATED", actor_id=U[submitter], from_status=None, to_status="DRAFT", created_at=order_dt)
    for r, rid in enumerate(revs):
        rdt = sub_dt if r == 0 else sub_dt + timedelta(days=1)
        add("procurement.purchase_order_revisions", id=rid, po_id=pid, revision_no=r,
            kind="INITIAL" if r == 0 else "AMENDMENT",
            snapshot_header={"supplierId": SUP[sup], "warehouseId": WH[p], "currency": "VND",
                             "orderDate": d.isoformat(), "expectedDate": (d + timedelta(days=expected_days)).isoformat(),
                             "subtotal": subtotal, "taxTotal": tax, "totalAmount": total, "paymentTermDays": 30,
                             "note": note},
            change_summary=None if r == 0 else "Gửi lại sau khi điều chỉnh số lượng theo góp ý",
            changed_at=rdt, changed_by=U[submitter], created_at=rdt)
        for ln in plines:
            add("procurement.purchase_order_line_revisions", id=uid(f"po:{key}:lrev:{r}:{ln['no']}"),
                po_line_id=ln["id"], po_revision_id=rid, inventory_item_id=ITEM[ln["sku"]],
                uom=VAR[ln["sku"]]["uom"], ordered_qty=ln["qty"], unit_price=ln["price"], tax_rate=10,
                discount_rate=0, line_subtotal=ln["sub"], line_discount_amount=0, line_net=ln["sub"],
                line_tax=ln["tax"], line_total=ln["total"], note=None, created_at=rdt)
        add("procurement.purchase_order_events", id=uid(f"po:{key}:ev:sub:{r}"), po_id=pid, po_revision_id=rid,
            action="SUBMITTED", actor_id=U[submitter], from_status="DRAFT", to_status="PENDING_APPROVAL",
            created_at=rdt)
        if r == 0 and rejected_first:
            add("procurement.purchase_order_approvals", id=uid(f"po:{key}:appr:{r}"), po_revision_id=rid, step_no=1,
                approver_id=U[approver], decision="REJECTED", decision_at=rdt + timedelta(hours=3),
                reason="Số lượng vượt nhu cầu tháng, giảm 20%", created_at=rdt + timedelta(hours=3))
            add("procurement.purchase_order_events", id=uid(f"po:{key}:ev:rej"), po_id=pid, po_revision_id=rid,
                action="REJECTED", actor_id=U[approver], from_status="PENDING_APPROVAL", to_status="DRAFT",
                reason="Số lượng vượt nhu cầu tháng, giảm 20%", created_at=rdt + timedelta(hours=3))
    for ln in plines:
        add("procurement.purchase_order_lines", id=ln["id"], po_id=pid, po_revision_id=revs[-1] if revs else None,
            line_no=ln["no"], inventory_item_id=ITEM[ln["sku"]], uom=VAR[ln["sku"]]["uom"], ordered_qty=ln["qty"],
            unit_price=ln["price"], tax_rate=10, discount_rate=0, line_subtotal=ln["sub"], line_discount_amount=0,
            line_net=ln["sub"], line_tax=ln["tax"], line_total=ln["total"],
            status="CANCELLED" if status == "CANCELLED" else "OPEN", note=None, created_at=order_dt)
        ln["row"] = ROWS["procurement.purchase_order_lines"][-1]
    if lvl >= 2 and status != "CANCELLED":
        add("procurement.purchase_order_approvals", id=uid(f"po:{key}:appr:final"), po_revision_id=revs[-1],
            step_no=1, approver_id=U[approver], decision="APPROVED", decision_at=app_dt, reason=None,
            created_at=app_dt)
        add("procurement.purchase_order_events", id=uid(f"po:{key}:ev:app"), po_id=pid, po_revision_id=revs[-1],
            action="APPROVED", actor_id=U[approver], from_status="PENDING_APPROVAL", to_status="APPROVED",
            created_at=app_dt)
        add("procurement.po_delivery_decision", id=uid(f"po:{key}:deliv:0"), purchase_order_id=pid, generation=0,
            previous_expected_at=None, expected_at=d + timedelta(days=expected_days), reason=None, reconciled=False,
            acknowledge_past_due=False, channel="EMAIL" if sup != "KRAFTPACK" and sup != "INHN" else "API",
            recipient=f"sales@{sup.lower()}.example", payload_snapshot=f"PO {number} - tổng {total:,} VND",
            actor=submitter, requested_at=app_dt + timedelta(minutes=5))
        add("notification.po_delivery_control", purchase_order_id=pid, generation=0, suppressed=False,
            cancellation_requested=False)
        add("notification.delivery_log", id=uid(f"po:{key}:mail"), template_code="po.mail",
            channel="EMAIL", recipient=f"sales@{sup.lower()}.example", status="SENT", error=None,
            sent_at=app_dt + timedelta(minutes=6), external_reference=None, operation_reference=f"po:{pid}:0",
            terminal=True, delivery_generation=0, attempt_number=1, created_at=app_dt + timedelta(minutes=6))
    if lvl >= 3 and status != "CANCELLED":
        add("procurement.purchase_order_events", id=uid(f"po:{key}:ev:conf"), po_id=pid, po_revision_id=revs[-1],
            action="CONFIRMED", actor_id=U[submitter], from_status="APPROVED", to_status="CONFIRMED",
            created_at=conf_dt)
    if status == "CANCELLED":
        add("procurement.purchase_order_events", id=uid(f"po:{key}:ev:cancel"), po_id=pid, po_revision_id=revs[-1],
            action="CANCELLED", actor_id=U[submitter], from_status="PENDING_APPROVAL", to_status="CANCELLED",
            reason=cancel_reason, created_at=sub_dt + timedelta(days=1))
    add("procurement.purchase_order_attachments", id=uid(f"po:{key}:att:quote"), po_id=pid,
        file_name=f"bao-gia-{sup.lower()}-{d.strftime('%Y%m%d')}.pdf",
        file_url=f"https://files.stockflow.example/po/{number}/bao-gia.pdf", file_type="PDF", category="QUOTATION",
        file_size=184320 + len(PO) * 1024, uploaded_by=U[submitter], created_at=order_dt)
    if status == "CONFIRMED":
        staged("procurement.purchase_orders", row, 3, status="CONFIRMED", close_kind=None, close_reason=None,
               closed_at=None, closed_by=None)
        for ln in plines:
            staged("procurement.purchase_order_lines", ln["row"], 4, status="OPEN")
    po = dict(id=pid, key=key, number=number, p=p, sup=sup, d=d, status=status, lines=plines, rev=revs[-1] if revs else None,
              row=row, conf_dt=conf_dt, total=total, subtotal=subtotal, tax=tax, submitter=submitter, approver=approver)
    PO[key] = po
    return po


RECEIPTS = []


def receive(po, when, parts, status="CLOSED", qc=None, putaway="DONE"):
    """parts: [(line_no, qty, [bins] , lot, expiry)]; qc: {line_no: [(outcome, qty, reason)]}.
    Creates the receipt, its lines, the QC move and inspections, putaway tasks and the stock they made."""
    p = po["p"]
    staff, staff2, mgr, qcu = staff_of(p)
    rid = uid(f"gr:{po['key']}:{len(RECEIPTS)}")
    number = next_no("GR", when.date())
    confirmed = status != "DRAFT"
    grow = add("procurement.goods_receipts", id=rid, receipt_number=number, po_id=po["id"], po_revision_id=po["rev"],
               warehouse_id=WH[p], status=status, received_at=when, received_by=U[staff],
               confirmed_at=when + timedelta(hours=1) if confirmed else None, confirmed_by=U[mgr] if confirmed else None,
               delivery_note=f"PXK-{po['sup']}-{when.strftime('%m%d')}", note=None,
               closed_at=when + timedelta(days=1) if status == "CLOSED" else None, created_at=when)
    if confirmed:
        staged("procurement.goods_receipts", grow, 1, status="DRAFT", confirmed_at=None, confirmed_by=None,
               closed_at=None)
    RECEIPTS.append(rid)
    rcv = f"{p}-RCV01"
    qca = f"{p}-QCA01"
    out = []
    for n, (line_no, qty, bins, lot, expiry) in enumerate(parts):
        ln = po["lines"][line_no - 1]
        sku = ln["sku"]
        qc_req = "qc" in VAR[sku]["flags"]
        lid = uid(f"gr:{rid}:line:{n}")
        moved = qc_req and confirmed and status != "CONFIRMED"
        lrow = add("procurement.goods_receipt_lines", id=lid, receipt_id=rid, po_line_id=ln["id"],
                   inventory_item_id=ITEM[sku], location_id=LOC[rcv]["id"], received_qty=qty, lot_number=lot,
                   expiry_date=expiry, note=None, qc_required=qc_req,
                   qc_location_id=LOC[qca]["id"] if moved else None,
                   moved_to_qc_at=when + timedelta(hours=2) if moved else None,
                   moved_to_qc_by=U[staff] if moved else None, created_at=when)
        if moved:
            staged("procurement.goods_receipt_lines", lrow, 2, qc_location_id=None, moved_to_qc_at=None,
                   moved_to_qc_by=None)
        ln["received"] += qty
        out.append(lid)
        if not confirmed:
            continue
        move("RECEIPT", sku, qty, "GOODS_RECEIPT_LINE", lid, when + timedelta(hours=1), staff, to=rcv, lot=lot,
             ts_="INBOUND")
        src = rcv
        accepted = qty
        if moved:
            mt = uid(f"mt:qc:{lid}")
            add("warehouse.move_task", id=mt, task_number=next_no("MT", when.date()), warehouse_id=WH[p],
                origin="RECEIPT_QC", reason=None, sku=sku, lot_number=lot, from_location_id=LOC[rcv]["id"],
                to_location_id=LOC[qca]["id"], requested_qty=qty, picked_qty=qty, placed_qty=qty,
                status="COMPLETED", priority=5, assigned_user_id=U[staff], approved_by=None,
                started_at=when + timedelta(hours=1, minutes=30), completed_at=when + timedelta(hours=2),
                note="Chuyển hàng sang khu QC", receipt_line_id=lid, created_at=when + timedelta(hours=1))
            move("MOVE", sku, qty, "MOVE_TASK", mt, when + timedelta(hours=2), staff, frm=rcv, to=qca, lot=lot,
                 fs="INBOUND", ts_="INBOUND")
            src = qca
            inspections = (qc or {}).get(line_no, [("ACCEPTED", qty, None)])
            accepted = 0
            for k, (outcome, qn, reason) in enumerate(inspections):
                qi = uid(f"qc:{lid}:{k}")
                add("procurement.qc_inspections", id=qi, receipt_line_id=lid, outcome=outcome, quantity=qn,
                    target_location_id=LOC[f"{p}-QRT01"]["id"] if outcome == "QUARANTINE" else None, reason=reason,
                    inspected_by=U[qcu], inspected_at=when + timedelta(hours=4 + k), created_at=when + timedelta(hours=4))
                if outcome == "ACCEPTED":
                    accepted += qn
                elif outcome == "QUARANTINE":
                    Stock(sku, f"{p}-QRT01", qn, when + timedelta(hours=1), "QUARANTINE", lot, expiry)
                    move("MOVE", sku, qn, "QC_INSPECTION", qi, when + timedelta(hours=5), qcu, frm=qca,
                         to=f"{p}-QRT01", lot=lot, fs="INBOUND", ts_="QUARANTINE", reason=reason)
                else:  # REJECTED: back to the supplier from the quarantine area
                    Stock(sku, f"{p}-RTV01", qn, when + timedelta(hours=1), "BLOCKED", lot, expiry)
                    move("MOVE", sku, qn, "QC_INSPECTION", qi, when + timedelta(hours=5), qcu, frm=qca,
                         to=f"{p}-RTV01", lot=lot, fs="INBOUND", ts_="BLOCKED", reason=reason)
            if status == "IN_QC":
                # inspected part waits in QC for putaway, the rest is still being inspected
                Stock(sku, qca, qty - sum(x[1] for x in inspections if x[0] != "ACCEPTED"),
                      when + timedelta(hours=1), "INBOUND", lot, expiry)
                continue
        if status in ("CONFIRMED", "IN_QC"):
            Stock(sku, src, accepted, when + timedelta(hours=1), "INBOUND", lot, expiry)
            continue
        share = [accepted // len(bins)] * len(bins)
        share[0] += accepted - sum(share)
        for b, (bin_code, part) in enumerate(zip(bins, share)):
            pt = uid(f"pt:{lid}:{b}")
            done = putaway == "DONE" or (putaway == "MIXED" and b == 0)
            add("warehouse.putaway_task", id=pt, goods_receipt_id=rid, goods_receipt_line_id=lid, sku=sku,
                quantity=part, target_location_id=LOC[bin_code]["id"],
                status="DONE" if done else ("IN_PROGRESS" if putaway == "MIXED" else "PENDING"),
                created_at=when + timedelta(hours=6))
            if done:
                Stock(sku, bin_code, part, when + timedelta(hours=1), "AVAILABLE", lot, expiry)
                move("PUTAWAY", sku, part, "PUTAWAY_TASK", pt, when + timedelta(hours=7), staff2, frm=src,
                     to=bin_code, lot=lot, fs="INBOUND", ts_="AVAILABLE")
            else:
                Stock(sku, src, part, when + timedelta(hours=1), "INBOUND", lot, expiry, key=f"inbound:{pt}")
    return rid, out


def po_received(po, when, closed=False, short_reason=None):
    """Settles line and header statuses after receiving."""
    full = all(ln["received"] >= ln["qty"] for ln in po["lines"])
    for ln in po["lines"]:
        ln["row"]["status"] = ("CLOSED" if closed else "RECEIVED") if ln["received"] >= ln["qty"] else \
            ("CLOSED" if closed else ("PARTIALLY_RECEIVED" if ln["received"] else "OPEN"))
    st = "RECEIVED" if full else "PARTIALLY_RECEIVED"
    add("procurement.purchase_order_events", id=uid(f"po:{po['key']}:ev:{st}"), po_id=po["id"],
        po_revision_id=po["rev"], action=st, actor_id=U[staff_of(po["p"])[2]], from_status="CONFIRMED", to_status=st,
        created_at=when)
    po["row"]["status"] = st
    if closed:
        kind = "NORMAL" if full else "SHORT_CLOSE"
        po["row"].update(status="CLOSED", close_kind=kind, close_reason=short_reason, closed_at=when + timedelta(days=1),
                         closed_by=U[po["submitter"]])
        add("procurement.purchase_order_events", id=uid(f"po:{po['key']}:ev:closed"), po_id=po["id"],
            po_revision_id=po["rev"], action="CLOSED" if full else "SHORT_CLOSED", actor_id=U[po["submitter"]],
            from_status=st, to_status="CLOSED", reason=short_reason, created_at=when + timedelta(days=1))


def invoice(po, status, when, receipt_lines=None, number=None, note_pct=1.0):
    iid = uid(f"inv:{po['key']}")
    subtotal = sum(money(ln["received"] * ln["price"] * note_pct) for ln in po["lines"] if ln["received"])
    tax = sum(money(money(ln["received"] * ln["price"] * note_pct) * 0.1) for ln in po["lines"] if ln["received"])
    add("procurement.supplier_invoices", id=iid, invoice_number=number or f"HD{when.strftime('%y%m')}{len(PO):04d}",
        supplier_id=SUP[po["sup"]], po_id=po["id"], status=status, currency="VND", invoice_date=when,
        due_date=when + timedelta(days=30), subtotal=subtotal, tax_total=tax, total_amount=subtotal + tax,
        file_url=f"https://files.stockflow.example/invoices/{po['number']}.pdf", created_at=at(when, 10))
    for n, ln in enumerate(po["lines"]):
        if not ln["received"]:
            continue
        s = money(ln["received"] * ln["price"] * note_pct)
        add("procurement.supplier_invoice_lines", id=uid(f"inv:{po['key']}:{n}"), invoice_id=iid, po_line_id=ln["id"],
            receipt_line_id=(receipt_lines or {}).get(ln["no"]), invoiced_qty=ln["received"],
            unit_price=money(ln["price"] * note_pct), tax_rate=10, line_total=s + money(s * 0.1), created_at=at(when, 10))


def cost_of(sku):
    return VAR[sku].get("cost") or 1250000


# --- 7a. opening stock: August purchase orders, received, put away and closed --------------------
BY_SUP = {}
for v in STOCKED:
    if v["sku"] in DEFAULT_SUP:
        BY_SUP.setdefault(DEFAULT_SUP[v["sku"]], []).append(v)
HIST_DAY = {"HN": date(2026, 8, 6), "BD": date(2026, 8, 7), "DN": date(2026, 8, 10)}
DN_SKUS = {"LYG-1L-12OZ", "LYG-1L-16OZ", "LYG-2L-12OZ", "LYN-PET-16OZ", "LYN-PET-22OZ", "NAP-GIAY-D90-WHITE",
           "NAP-PET-D95", "HOP-COM-M", "OHG-WHITE", "TUI-KRAFT-M-KRAFT", "KHAY-LY-4CUP", "TAYCAM-12OZ"}
bin_cursor = {}


def next_bin(p, cls):
    pool = BINS[p][cls]
    k = (p, cls)
    bin_cursor[k] = bin_cursor.get(k, -1) + 1
    return pool[bin_cursor[k] % len(pool)]


for p in ("HN", "BD", "DN"):
    for s_i, (sup, items) in enumerate(sorted(BY_SUP.items())):
        items = [v for v in items if p != "DN" or v["sku"] in DN_SKUS]
        if not items:
            continue
        d = HIST_DAY[p] + timedelta(days=s_i % 4)
        lines, parts = [], []
        for v in items:
            lot = "lot" in v["flags"]
            if lot:
                q1, q2 = random.randint(12, 30), random.randint(10, 24)
                lines.append((v["sku"], q1 + q2, cost_of(v["sku"])))
            else:
                qty = random.randint(80, 260) if p != "DN" else random.randint(40, 90)
                lines.append((v["sku"], qty, cost_of(v["sku"])))
        po = make_po(f"hist:{p}:{sup}", p, sup, d, "CONFIRMED", lines,
                     submitter="muahang1" if s_i % 2 == 0 else "muahang2", note="Nhập hàng đầu kỳ")
        when = at(d + timedelta(days=7), 8, 30)
        for n, v in enumerate(items, 1):
            qty = lines[n - 1][1]
            if "lot" in v["flags"]:
                q1 = qty // 2 + 3
                exp1, exp2 = date(2027, 6, 30), date(2027, 12, 31)
                tag = v["sku"][-2:]
                parts.append((n, q1, [next_bin(p, "HAZMAT")], f"LOT-{p}-2608-{tag}A", exp1))
                parts.append((n, qty - q1, [next_bin(p, "HAZMAT")], f"LOT-{p}-2608-{tag}B", exp2))
            else:
                bins = [next_bin(p, v["cls"])]
                if qty > 150:
                    bins.append(next_bin(p, v["cls"]))
                parts.append((n, qty, bins, None, None))
        rid, rlines = receive(po, when, parts, "CLOSED")
        po_received(po, when + timedelta(hours=8), closed=True)
        line_to_receipt = {}
        for (n, *_), lid in zip(parts, rlines):
            line_to_receipt.setdefault(n, lid)
        invoice(po, "PAID" if p != "DN" else "MATCHED", (when + timedelta(days=2)).date(), line_to_receipt)

# --- 7b. purchase orders in every state ------------------------------------------------------------
def pick_items(sup, n, p="HN"):
    pool = [v for v in BY_SUP[sup] if p != "DN" or v["sku"] in DN_SKUS]
    random.shuffle(pool)
    return pool[:n]


def lines_for(sup, n, lo=40, hi=150):
    return [(v["sku"], random.randint(lo, hi) // 5 * 5, cost_of(v["sku"])) for v in pick_items(sup, n)]


make_po("s:draft", "HN", "GIAYVN", date(2026, 10, 10), "DRAFT", lines_for("GIAYVN", 3),
        note="Dự trù hàng Tết - chờ chốt số lượng")
make_po("s:draft2", "BD", "KRAFTPACK", date(2026, 10, 11), "DRAFT", lines_for("KRAFTPACK", 2), submitter="muahang2")
make_po("s:pending", "BD", "NHUAMN", date(2026, 10, 9), "PENDING_APPROVAL", lines_for("NHUAMN", 3),
        submitter="muahang2")
make_po("s:resubmit", "HN", "GIAYVN", date(2026, 10, 6), "PENDING_APPROVAL", lines_for("GIAYVN", 2, 100, 200),
        rejected_first=True, note="Đã giảm số lượng theo góp ý của quản lý kho")
make_po("s:approved", "HN", "KRAFTPACK", date(2026, 10, 8), "APPROVED", lines_for("KRAFTPACK", 3))
po_conf = make_po("s:confirmed", "BD", "GIAYVN", date(2026, 10, 5), "CONFIRMED", lines_for("GIAYVN", 4),
                  submitter="muahang2", expected_days=10)
po_ink = make_po("s:ink", "HN", "MUCIN", date(2026, 10, 2), "CONFIRMED",
                 [(v["sku"], 20, 1250000) for v in BY_SUP["MUCIN"]], expected_days=10)
receive(po_ink, at(date(2026, 10, 12), 8, 45),
        [(1, 20, [], "LOT-HN-2610-CY", date(2028, 3, 31)), (2, 20, [], "LOT-HN-2610-MG", date(2028, 3, 31))],
        status="DRAFT")
po_part = make_po("s:partial", "BD", "ONGHUT", date(2026, 9, 18), "CONFIRMED",
                  [(v["sku"], 120, cost_of(v["sku"])) for v in BY_SUP["ONGHUT"]], expected_days=7)
rid_p, rl_p = receive(po_part, at(date(2026, 9, 26), 9),
                      [(n, 60, [next_bin("BD", "NORMAL")], None, None) for n in range(1, len(po_part["lines"]) + 1)])
po_received(po_part, at(date(2026, 9, 26), 16))
invoice(po_part, "RECEIVED", date(2026, 9, 28), {n: lid for n, lid in enumerate(rl_p, 1)})

po_pet = make_po("s:pet-qc", "HN", "NHUAMN", date(2026, 10, 1), "CONFIRMED",
                 [("LYN-PET-16OZ", 100, cost_of("LYN-PET-16OZ")), ("LYN-PET-22OZ", 80, cost_of("LYN-PET-22OZ"))],
                 expected_days=7)
receive(po_pet, at(date(2026, 10, 9), 10), [(1, 100, [], None, None), (2, 40, [], None, None)], status="IN_QC",
        qc={1: [("ACCEPTED", 90, None), ("QUARANTINE", 6, "Ly bị móp miệng, chờ NCC xác nhận"),
                ("REJECTED", 4, "Ly nứt đáy, trả NCC")], 2: [("ACCEPTED", 20, None)]})
po_received(po_pet, at(date(2026, 10, 9), 17))

po_box = make_po("s:putaway", "BD", "HOPCAOCAP", date(2026, 9, 28), "CONFIRMED",
                 [(v["sku"], 60, cost_of(v["sku"])) for v in BY_SUP["HOPCAOCAP"] if v["vstatus"] == "ACTIVE"][:4],
                 submitter="muahang2", expected_days=12)
rid_b, rl_b = receive(po_box, at(date(2026, 10, 10), 9),
                      [(n, 60, [next_bin("BD", VAR[ln["sku"]]["cls"]), next_bin("BD", VAR[ln["sku"]]["cls"])],
                        None, None) for n, ln in enumerate(po_box["lines"], 1)],
                      status="IN_PUTAWAY", putaway="MIXED")
po_received(po_box, at(date(2026, 10, 10), 15))
invoice(po_box, "DISPUTED", date(2026, 10, 11), {n: lid for n, lid in enumerate(rl_b, 1)}, note_pct=1.04,
        number="HD-DISPUTE-0001")

po_short = make_po("s:short", "HN", "KRAFTPACK", date(2026, 9, 10), "CONFIRMED", lines_for("KRAFTPACK", 2, 100, 120))
rid_s, rl_s = receive(po_short, at(date(2026, 9, 20), 9),
                      [(n, ln["qty"] - 30, [next_bin("HN", "NORMAL")], None, None)
                       for n, ln in enumerate(po_short["lines"], 1)])
po_received(po_short, at(date(2026, 9, 20), 16), closed=True,
            short_reason="NCC hết giấy kraft, không giao phần còn lại")
invoice(po_short, "PAID", date(2026, 9, 22), {n: lid for n, lid in enumerate(rl_s, 1)})

make_po("s:cancelled", "BD", "KRAFTPACK", date(2026, 9, 15), "CANCELLED", lines_for("KRAFTPACK", 2),
        cancel_reason="Khách hủy đơn dự án, không cần nhập hàng", submitter="muahang2")
po_rej = make_po("s:supplier-rejected", "HN", "HOPCAOCAP", date(2026, 10, 7), "APPROVED",
                 [("HOP-QUA-M", 30, cost_of("HOP-QUA-M"))], confirm_status="REJECTED")
po_rej["row"]["supplier_response_note"] = "Không đáp ứng được hạn giao, đề nghị lùi 2 tuần"

# ============================================================================ 8. sales orders
ORDERS = []
CARRIERS = ["GHN", "GHTK", "Viettel Post", "J&T Express"]
# G4-2a (V20261021000400): the carrier catalogue the migration seeds, by the free-text names used before it.
CARRIER_CODE = {"GHN": "GHN", "GHTK": "GHTK", "Viettel Post": "VTP", "J&T Express": "JTE"}


def carrier_ref(name):
    return Raw(f"md5('carrier:{CARRIER_CODE[name]}')::uuid")


READY_SHIPMENTS = []   # waiting at dispatch: (id, warehouse prefix, carrier name or None, parcel ids, COD, ready at)
DESIGNS = {}


def design_for(cust_code, sku, d):
    """A confirmed design of a customer on a blank: draft, two artifacts, decisions, snapshot."""
    key = f"{cust_code}:{VAR[sku]['product']}"
    if key in DESIGNS:
        return DESIGNS[key]
    c = CUST[cust_code]
    did = uid("design:" + key)
    owner = c["user"] or U["sales1"]
    t0 = at(d - timedelta(days=12), 9)
    arts = []
    for role, ext, ctype in (("CUSTOMER_PREVIEW", "png", "image/png"), ("PRINT_READY", "pdf", "application/pdf")):
        aid = uid(f"art:{key}:{role}")
        stored = design_file(aid, ctype, t0 + timedelta(days=1), f"{c['company']} - {sku} - {role}")
        arts.append(dict(id=aid, role=role, key=stored["key"], name=f"{cust_code}-{role.lower()}.{ext}", ctype=ctype,
                         size=stored["size"], checksum=stored["checksum"]))
    current = {a["role"]: a["id"] for a in arts}
    add("design.design_draft", id=did, customer_id=c["id"], product_id=PROD[VAR[sku]["product"]]["id"],
        name=f"Logo {c['company']} - {PROD[VAR[sku]['product']]['name']}",
        spec=f"In 2 màu Pantone theo bộ nhận diện của {c['company']}; vị trí: giữa thân, cao 45 mm.",
        status="APPROVED", owner_user_id=owner, assigned_user_id=U["thietke"], current_artifact_id=arts[0]["id"],
        preflight_passed=True, reviewer_user_id=U["kiemduyet"], last_editor_user_id=U["thietke"],
        reviewed_by=U["kiemduyet"], review_notes="Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm",
        parent_snapshot_id=None, current_artifacts=current, version=4, created_at=t0)
    for a in arts:
        add("design.design_artifact", id=a["id"], draft_id=did, storage_key=a["key"], original_name=a["name"],
            content_type=a["ctype"], size_bytes=a["size"], stored_at=t0 + timedelta(days=1), checksum=a["checksum"],
            upload_key=None, artifact_role=a["role"], created_at=t0 + timedelta(days=1))
    sid = uid("snapshot:" + key)
    h = hashlib.sha256()
    for a in sorted(arts, key=lambda x: x["role"]):
        h.update(f"{a['role']}:{a['id']}:{a['checksum']}\n".encode())
    checksum = h.hexdigest()
    for k, (dec, notes, actor, dd) in enumerate([
            ("ASSIGNMENT_CHANGED", f"Designer={U['thietke']}; reviewer={U['kiemduyet']}", "sales1", 0),
            ("TECHNICAL_ACCEPTED", "Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm", "kiemduyet", 3),
            ("SENT_TO_CUSTOMER", "Customer confirmation requested for the current artifact and specification",
             "sales1", 4),
            ("CUSTOMER_CONFIRMED", sid, None, 5)]):
        add("design.design_decision", id=uid(f"dec:{key}:{k}"), draft_id=did,
            artifact_id=arts[0]["id"] if dec != "ASSIGNMENT_CHANGED" else None,
            actor_id=U[actor] if actor else owner, decision=dec, notes=notes, draft_version=k,
            created_at=t0 + timedelta(days=dd))
    add("design.design_snapshot", id=sid, draft_id=did, checksum=checksum, artifact_url=arts[0]["key"],
        confirmed_at=t0 + timedelta(days=5), artifact_id=arts[0]["id"], confirmed_by=owner,
        spec=f"In 2 màu Pantone theo bộ nhận diện của {c['company']}; vị trí: giữa thân, cao 45 mm.",
        reviewed_by=U["kiemduyet"], review_notes="Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm",
        artifact_manifest=[{"artifactId": a["id"], "role": a["role"], "storageKey": a["key"],
                            "originalName": a["name"], "contentType": a["ctype"], "sizeBytes": a["size"],
                            "checksum": a["checksum"]} for a in sorted(arts, key=lambda x: x["role"])],
        created_at=t0 + timedelta(days=5))
    DESIGNS[key] = dict(snapshot=sid, checksum=checksum, draft=did, cust=cust_code, sku=sku, at=t0 + timedelta(days=5))
    # sample round approved by the customer before the first print order (BR-PRD-08)
    srid = uid("sample:" + key)
    sd = t0 + timedelta(days=6)
    add("production.sample_request", id=srid, request_number=next_no("SMP", sd.date()), customer_id=c["id"],
        quote_id=None, previous_round_id=None, round_no=1, blank_sku=sku, design_snapshot_id=sid, quantity=1,
        due_date=sd.date() + timedelta(days=3), status="APPROVED", sent_at=sd + timedelta(days=2),
        responded_at=sd + timedelta(days=3), responded_by=owner, customer_feedback="Màu in đúng, duyệt mẫu",
        cancellation_reason=None, created_at=sd)
    add("ordering.approved_sample", sample_request_id=srid, customer_id=c["id"], design_snapshot_id=sid,
        blank_sku=sku, approved_at=sd + timedelta(days=3))
    production_order(f"sample:{key}", "SAMPLE", PRINT_WAREHOUSE[c["wh"]], sku, sid, checksum, 1, "COMPLETED",
                     sd, sample_request=srid)
    return DESIGNS[key]


PRODUCTION = []


def production_order(key, typ, p, sku, snap, checksum, qty, status, start, sample_request=None, order=None,
                     line=None, mode="IN_HOUSE", hold=None, end=None, subcontractor="INSG"):
    """A production order as the workshop leaves it (ProductionOrder): blanks issued only up to what is still needed
    (BR-PRD-03), so an in-house order issues and prints exactly its planned quantity; a QC round only once one was
    recorded. `end`: when an ORDER completed - before its sales order became READY_TO_FULFILL - its steps are spread
    from `start` to `end`; otherwise one step a day. The stock side of an ORDER (holds issued to the print shop,
    finished goods consumed) is place_order's, with the times kept in PRODUCTION[-1]["times"]."""
    pid = uid("prod:" + key)
    path = ["PENDING_PREPRESS", "READY", "MATERIAL_ISSUED", "PRINTING", "QC", "COMPLETED"]
    if mode == "SUBCONTRACTED":
        path = ["PENDING_PREPRESS", "READY", "SUBCONTRACTED", "QC", "COMPLETED"]
    target = hold[0] if status == "ON_HOLD" else status
    reached = path[:path.index(target) + 1]
    done = status == "COMPLETED"
    steps = reached + (["ON_HOLD"] if status == "ON_HOLD" else [])
    if end is not None:
        span = (end - start) / (len(steps) - 1)
        times = [start + span * k for k in range(len(steps))]
    else:
        times = [start + timedelta(days=k, hours=1) for k in range(len(steps))]
    when = dict(zip(steps, times))
    completed = (when["COMPLETED"] if end is not None else start + timedelta(days=len(path))) if done else None
    # A subcontracted order never counts issued blanks: ProductionOrder.issueBlanks refuses it, and what goes out with
    # it is production_material_issue rows only (SubcontractJob.send). Its printed and good units are what the
    # subcontractor delivered and QC passed - for a completed one, all of its plan.
    issued = qty if mode == "IN_HOUSE" and "MATERIAL_ISSUED" in reached else 0
    printed = good = qty if (done or status == "QC") else 0
    worker = "sanxuat.hn" if p == "HN" else "sanxuat.bd"
    inspector = "qc.hn" if p == "HN" else "qc.bd"
    row = add("production.production_order", id=pid, order_number=next_no("PRD", start.date()), type=typ, mode=mode,
              status=status, status_before_hold=hold[0] if status == "ON_HOLD" else None,
              hold_reason=hold[1] if status == "ON_HOLD" else None, hold_note=hold[2] if status == "ON_HOLD" else None,
              sample_request_id=sample_request, sales_order_id=order, sales_order_line_id=line, parent_id=None,
              approved_sample_id=None, warehouse_id=WH[p], blank_sku=sku, design_snapshot_id=snap,
              design_checksum=checksum, due_date=start.date() + timedelta(days=10), planned_quantity=qty,
              issued_quantity=issued, printed_quantity=printed, good_quantity=good, scrap_quantity=printed - good,
              subcontract_method="SUPPLIED_BLANKS" if mode == "SUBCONTRACTED" else None,
              subcontractor_id=SUP[subcontractor] if mode == "SUBCONTRACTED" else None,
              subcontract_unit_price=120000 if mode == "SUBCONTRACTED" else None,
              subcontract_currency="VND" if mode == "SUBCONTRACTED" else None, purchase_order_id=None,
              prepress_checked_by=U["kiemduyet"] if len(reached) > 1 else None,
              prepress_checked_at=min(start + timedelta(hours=4), when["READY"]) if len(reached) > 1 else None,
              completed_at=completed, cancelled_at=None, cancellation_reason=None, created_at=start,
              # SCRUM-412: a completed ORDER records the PACKING area its goods were handed to.
              packing_location_code=f"{p}-PACK01" if done and typ == "ORDER" else None)
    prev = None
    for k, s in enumerate(steps):
        actor = "kiemduyet" if k == 1 else inspector if s == "COMPLETED" else worker
        add("production.production_order_status_history", id=uid(f"prod:{key}:h:{k}"), production_order_id=pid,
            from_status=prev, to_status=s, reason=hold[2] if s == "ON_HOLD" else None,
            changed_at=when[s], changed_by=U[actor])
        prev = s
    if printed and mode == "IN_HOUSE":
        # The press run that took the order PRINTING -> QC (PrintRunService.recordOutput).
        add("production.production_print_run", id=uid(f"prod:{key}:run:1"), production_order_id=pid,
            printed_quantity=printed, scrap_quantity=printed - good, recorded_at=when["QC"], recorded_by=U[worker])
    if done:
        # The QC round that completed the order; an order still in QC has had none (recordQc is the only way out).
        inspected = completed if end is not None else start + timedelta(days=len(path) - 1)
        add("production.production_qc_result", id=uid(f"prod:{key}:qc"), production_order_id=pid, round_no=1,
            passed_quantity=good, failed_quantity=printed - good,
            note="Lệch màu nhẹ 1 thùng" if printed > good else "Đạt", inspected_at=inspected, inspected_by=U[inspector])
        if printed > good:
            add("production.production_scrap", id=uid(f"prod:{key}:scrap"), production_order_id=pid,
                quantity=printed - good, reason="QC_FAILED", note="Lệch màu so với mẫu duyệt",
                stock_adjustment_id=None, recorded_at=inspected, recorded_by=U[worker])
    PRODUCTION.append(dict(id=pid, row=row, key=key, done=done, times=when, completed=completed, p=p,
                           worker=worker, inspector=inspector))
    if done and line:
        add("ordering.order_line_production", production_order_id=pid, order_line_id=line, good_quantity=good,
            completed_at=completed)
    return pid


def status_path(custom, credit, final):
    path = ["PENDING_PAYMENT"] if not credit else []
    path += ["CONFIRMED"]
    if custom:
        path += ["IN_PRODUCTION"]
    # G3-2 (V20261021000100): IN_FULFILMENT became PICKING and PACKED; PACKED is inserted by place_order, without a
    # random draw, so the rest of the data set stays what it was.
    path += ["READY_TO_FULFILL", "PICKING", "SHIPPED", "DELIVERED", "COMPLETED"]
    if final == "PACKED":
        final = "PICKING"
    if final in path:
        return path[:path.index(final) + 1]
    return path


def allocate(p, sku, qty):
    """FIFO/FEFO over the free stock of a warehouse. Returns [(stock, qty)] or None."""
    rows = [s for s in STOCK if s.sku == sku and s.loc in LOC and LOC[s.loc]["wh"] == p and LOC[s.loc]["kind"] == "BIN"
            and s.free > 0]
    rows.sort(key=lambda s: (s.expiry or date(2100, 1, 1), s.received, s.id))
    if sum(s.free for s in rows) < qty:
        return None
    out, left = [], qty
    for s in rows:
        take = min(s.free, left)
        out.append((s, take))
        left -= take
        if not left:
            break
    return out


SHIPMENTS_COD = []
RECEIVABLES = []
ISSUES = []      # production.production_material_issue rows, written once their ledger lines exist


def print_shop(prod, rid, s, qn, done):
    """One checkout hold of a print line taken through the print shop, as the application moves it: issued whole with
    its goods to the warehouse's PRODUCTION area as IN_PRODUCTION (InventoryService.issueReserved: one MOVE, reference
    PRODUCTION_ORDER, "Hold <id>", and a production_material_issue row); when the order completed, handed whole to the
    PACKING area as AVAILABLE (finishHeld, MOVE) and consumed there (consumeFinishedGoods, CONSUMPTION, G4-Q2). Each
    step leaves the row it came from, empty when the hold was all of it - the application never deletes a stock row.
    Returns the stock row the hold is on now."""
    p, when, pid = prod["p"], prod["times"], prod["id"]
    clerk = staff_of(p)[0]
    shop, packing = f"{p}-PRD01", f"{p}-PACK01"
    issued_at = when["MATERIAL_ISSUED"]
    s.on_hand -= qn
    there = Stock(s.sku, shop, qn, s.received, status="IN_PRODUCTION", lot=s.lot, expiry=s.expiry)
    move("MOVE", s.sku, qn, "PRODUCTION_ORDER", pid, issued_at, clerk, frm=s.loc, to=shop, lot=s.lot, fs="AVAILABLE",
         ts_="IN_PRODUCTION", reason=f"Hold {rid}")
    ISSUES.append(dict(id=uid(f"issue:{rid}"), production_order_id=pid, request_id=uid(f"issue:request:{rid}"),
                       reservation_id=rid, sku=s.sku, lot_number=s.lot, from_location=s.loc, to_location=shop,
                       quantity=qn, stock_movement_id=MOVES[-1]["id"], issued_at=issued_at, issued_by=U[clerk]))
    if not done:
        return there
    there.on_hand -= qn
    finished = Stock(s.sku, packing, qn, s.received, status="AVAILABLE", lot=s.lot, expiry=s.expiry)
    move("MOVE", s.sku, qn, "PRODUCTION_ORDER", pid, prod["completed"], prod["inspector"], frm=shop, to=packing,
         lot=s.lot, fs="IN_PRODUCTION", ts_="AVAILABLE", reason=f"Hold {rid}")
    finished.on_hand -= qn
    move("CONSUMPTION", s.sku, qn, "PRODUCTION_ORDER", pid, prod["completed"], prod["inspector"], frm=packing,
         lot=s.lot, fs="AVAILABLE", reason=f"Hold {rid}")
    return finished


def converted_at(prod):
    """When a subcontracted production order was converted whole (BR-PRD-12, SubcontractService.convert): half an hour
    after it was created, while it waited for prepress - the warehouse manager sent the line out at once."""
    return prod["times"]["PENDING_PREPRESS"] + timedelta(minutes=30)


def raised_with(t):
    """make_po's times for the SUBCONTRACT order a conversion raises in its own transaction (a DRAFT the split
    creates): submitted by purchasing, approved by the warehouse manager, confirmed by the subcontractor - before the
    work may go out (sendOut wants it approved, BR-PRD-13) and before anything is received (a CONFIRMED order)."""
    return t, t + timedelta(minutes=5), t + timedelta(hours=2), t + timedelta(hours=5)


SUB_SITE = {}   # (warehouse prefix, supplier code) -> the subcontractor's location code
JOBS = {}       # production order id -> SubcontractJob


def subcontractor_location(p, sup, when):
    """SubcontractorSiteRepository.locationOf: one SUBCONTRACTOR location per sending warehouse and subcontractor,
    <warehouse>-SUB-<supplier code>, owned by its subcontractor_site and created the first time that warehouse sends
    the subcontractor blanks (decision Q7). Not on the map, not pickable, not a putaway target: allocate and
    find_stock never see it (they take BIN rows only)."""
    if (p, sup) not in SUB_SITE:
        code = f"{p}-SUB-{sup}"
        LOC[code] = dict(id=uid("loc:" + code), wh=p, kind="SUBCONTRACTOR", cls="NORMAL", status="ACTIVE")
        add("warehouse.storage_location", id=LOC[code]["id"], warehouse_id=WH[p], kind="SUBCONTRACTOR",
            location_code=code, storage_class="NORMAL", capacity_units=None, max_weight=None, is_pickable=False,
            is_putaway_target=False, status="ACTIVE", created_at=when)
        add("warehouse.subcontractor_site", id=uid(f"subsite:{p}:{sup}"), warehouse_id=WH[p], supplier_id=SUP[sup],
            location_id=LOC[code]["id"], created_at=when)
        SUB_SITE[(p, sup)] = code
    return SUB_SITE[(p, sup)]


def subcontract_job(prod):
    if prod["id"] not in JOBS:
        JOBS[prod["id"]] = SubcontractJob(prod)
    return JOBS[prod["id"]]


class SubcontractJob:
    """A SUPPLIED_BLANKS production order converted whole (SCRUM-433/441, 03 §16, 04c F-PROD G1-G10), taken through
    stock the way SubcontractService and WorkshopStock move it. production_order wrote its row and the status steps
    of its path; this adds what happened between them. `blanks` are the production holds sent to the subcontractor,
    in the order they were sent - the order WorkshopStock takes them back in."""

    def __init__(self, prod):
        self.prod, self.row, self.p = prod, prod["row"], prod["p"]
        self.id, self.number, self.sku = self.row["id"], self.row["order_number"], self.row["blank_sku"]
        self.sup = next(code for code, sid in SUP.items() if sid == self.row["subcontractor_id"])
        self.clerk, _, self.manager, self.inspector = staff_of(self.p)
        self.steps = {r["to_status"]: r for r in ROWS["production.production_order_status_history"]
                      if r["production_order_id"] == self.id}
        self.blanks = []     # dict(row: reservation, stock: where it is now, left: units it covers, frm: sent from)
        self.received = []   # (reservation row, stock row in the QC area, units) per receipt line
        self.receipts = []   # production_subcontract_receipt rows
        self.recalls = []    # production_subcontract_recall rows
        self.rounds = []     # (passed, failed) per QC round
        self.converted = converted_at(prod)
        assert self.converted < prod["times"]["READY"], self.number
        self.history("convert", "PENDING_PREPRESS", "PENDING_PREPRESS", self.converted, self.manager,
                     "Whole order converted to subcontracting (SUPPLIED_BLANKS)")
        # The dispatch is a warehouse step (production-material-issues CREATE): the clerk who scanned the blanks out.
        self.steps["SUBCONTRACTED"].update(changed_by=U[self.clerk], reason="Sent to the subcontractor")

    def history(self, tag, frm, to, when, by, reason):
        add("production.production_order_status_history", id=uid(f"prod:{self.prod['key']}:sub:{tag}"),
            production_order_id=self.id, from_status=frm, to_status=to, reason=reason, changed_at=when,
            changed_by=U[by] if by else None)

    def hold(self, s, qty, when, root):
        """InventoryService.reserveForProduction: a pinned hold of the production order itself (no order), its
        request id derived from the call's and the stock row's (perStockItemRequestId)."""
        s.reserved += qty
        row = add("inventory.stock_reservation", id=uid(f"res:{root}:{s.id}"), stock_item_id=s.id, order_id=None,
                  root_request_id=uid(root), request_id=uid(f"{root}:{s.id}"), quantity=qty, reserved_at=when,
                  expires_at=None, status="HELD", release_reason=None, closed_at=None, production_order_id=self.id)
        self.blanks.append(dict(row=row, stock=s, left=qty, frm=s.loc, sent=False))

    def take_over(self, s, qty):
        """One checkout hold of the line (place_order releases it, SUBCONTRACTED): SubcontractService.moveHolds held
        its units for the production order in the same transaction - where the checkout hold was, the warehouse's
        first free units that instant - and sendOut took them to the subcontractor with the work."""
        self.hold(s, qty, self.converted, f"subcontract-blanks:{self.id}")
        self.send(self.prod["times"]["SUBCONTRACTED"])

    def send(self, when):
        """G5, SubcontractService.sendOut: every hold not sent yet goes whole to the subcontractor's location for the
        warehouse, IN_PRODUCTION - issueReserved, one MOVE naming the production order ("Hold <id>") - and gets its
        production_material_issue row (WorkshopStock.sendToSubcontractor). The status step is the caller's."""
        loc = subcontractor_location(self.p, self.sup, when)
        for b in self.blanks:
            if b["sent"]:
                continue
            s, qty, rid = b["stock"], b["left"], b["row"]["id"]
            s.on_hand -= qty
            s.reserved -= qty
            there = Stock(s.sku, loc, qty, s.received, status="IN_PRODUCTION", lot=s.lot, expiry=s.expiry)
            there.reserved += qty
            move("MOVE", s.sku, qty, "PRODUCTION_ORDER", self.id, when, self.clerk, frm=s.loc, to=loc, lot=s.lot,
                 fs="AVAILABLE", ts_="IN_PRODUCTION", reason=f"Hold {rid}")
            ISSUES.append(dict(id=uid(f"issue:{rid}"), production_order_id=self.id,
                               request_id=uid(f"subcontract-send:{rid}"), reservation_id=rid, sku=s.sku,
                               lot_number=s.lot, from_location=s.loc, to_location=loc, quantity=qty,
                               stock_movement_id=MOVES[-1]["id"], issued_at=when, issued_by=U[self.clerk]))
            b.update(stock=there, sent=True)
            b["row"]["stock_item_id"] = there.id

    def take(self, qty, source, step):
        """WorkshopStock.takeFromSubcontractor: `qty` blanks out of the holds at the subcontractor, in the order they
        were sent. A hold covers what is left of it; the step that takes the rest closes it."""
        for b in self.blanks:
            if not qty:
                break
            if not b["sent"] or not b["left"]:
                continue
            n = min(b["left"], qty)
            step(b, n, n == b["left"])
            b["left"] -= n
            qty -= n
            if b["left"]:
                b["row"]["quantity"] = b["left"]
        assert not qty, (self.number, source, qty)

    def account(self, spoilt, returned, source, when, decided=None):
        """WorkshopStock.reconcile, for a receipt's reconciliation or a recall: `spoilt` blanks leave their holds and
        their SUBCONTRACT_LOSS write-off is asked for (writeOffHeld: still on hand, IN_PRODUCTION, until approved),
        `returned` go back free to where each was sent from (returnHeld: MOVE, "Unprinted, from hold <id>").
        `decided`: when the warehouse manager approved the write-off - posted in the same step, an ADJUSTMENT line."""
        loc = SUB_SITE[(self.p, self.sup)]

        def write_off(b, n, whole):
            s = b["stock"]
            s.reserved -= n
            if whole:
                b["row"].update(status="RELEASED", release_reason="SCRAPPED", closed_at=when)
            aid = uid(f"adj:subcontract:{source}:{b['row']['id']}")
            note = f"{self.number} spoilt at the subcontractor ({source})"
            add("inventory.stock_adjustment", id=aid, adjustment_number=next_no("ADJ", when.date()),
                location_code=loc, sku=s.sku, lot_number=s.lot, quantity_delta=-n, reason_code="SUBCONTRACT_LOSS",
                note=note, status="POSTED" if decided else "PENDING_APPROVAL", cycle_count_line_id=None,
                requested_by=U[self.clerk], decided_by=U[self.manager] if decided else None, decided_at=decided,
                rejection_reason=None, posted_at=decided, withdrawn_at=None, received_at=s.received, created_at=when)
            if decided:
                # StockOperationsServiceImpl.approve: decided and posted in one step (approveAndPost).
                s.on_hand -= n
                move("ADJUSTMENT", s.sku, n, "STOCK_ADJUSTMENT", aid, decided, self.manager, frm=loc, lot=s.lot,
                     fs="IN_PRODUCTION", reason=f"SUBCONTRACT_LOSS: {note}")

        def give_back(b, n, whole):
            s = b["stock"]
            s.on_hand -= n
            s.reserved -= n
            if whole:
                b["row"].update(status="RELEASED", release_reason="RETURNED_FROM_PRODUCTION", closed_at=when)
            Stock(s.sku, b["frm"], n, s.received, lot=s.lot, expiry=s.expiry)   # back onto the layer it was sent from
            move("MOVE", s.sku, n, "PRODUCTION_ORDER", self.id, when, self.clerk, frm=loc, to=b["frm"], lot=s.lot,
                 fs="IN_PRODUCTION", ts_="AVAILABLE", reason=f"Unprinted, from hold {b['row']['id']}")

        self.take(spoilt, source, write_off)
        self.take(returned, source, give_back)

    def recall(self, tag, returned, spoilt, when, note):
        """SubcontractService.recall: blanks brought back without a goods receipt (BR-PRD-15), each to where it was
        sent from (no return location named)."""
        request = uid(f"subcontract-recall:{self.id}:{tag}")
        self.account(spoilt, returned, f"recall {request}", when)
        self.recalls.append(add("production.production_subcontract_recall", id=uid(f"recall:{self.id}:{tag}"),
                                production_order_id=self.id, request_id=request, returned_blanks=returned,
                                spoilt_blanks=spoilt, return_location=None, note=note, recorded_at=when,
                                recorded_by=U[self.clerk]))

    def receive(self, po, qty, when):
        """A goods receipt of the job's SUBCONTRACT order confirmed at `when` (GoodsReceiptServiceImpl.confirm): CLOSED
        as it is confirmed - no receiving QC, no putaway (GoodsReceipt.confirm, handedToProduction) - its goods counted
        in INBOUND at the receiving area (RECEIPT). In the same transaction SubcontractService.onReceipt counts it once
        onto the job, consumes the blanks the goods were printed on at the subcontractor (CONSUMPTION, decision Q9),
        holds the line's goods for the job (holdReceived) and takes them to the QC area as IN_PRODUCTION
        (holdForProductionQc) to wait for the one production QC (Q8). The listener's steps name no user."""
        p, ln = self.p, po["lines"][0]
        rid = uid(f"gr:{po['key']}:{len(RECEIPTS)}")
        number = next_no("GR", when.date())
        grow = add("procurement.goods_receipts", id=rid, receipt_number=number, po_id=po["id"],
                   po_revision_id=po["rev"], warehouse_id=WH[p], status="CLOSED",
                   received_at=when - timedelta(minutes=30), received_by=U[self.clerk], confirmed_at=when,
                   confirmed_by=U[self.clerk], note=None,
                   delivery_note=f"PXK-{po['sup']}-{when.strftime('%m%d')}-{len(self.receipts) + 1}",
                   closed_at=when, created_at=when - timedelta(minutes=30))
        staged("procurement.goods_receipts", grow, 1, status="DRAFT", confirmed_at=None, confirmed_by=None,
               closed_at=None)
        RECEIPTS.append(rid)
        lid = uid(f"gr:{rid}:line:0")
        rcv, qca = f"{p}-RCV01", f"{p}-QCA01"
        add("procurement.goods_receipt_lines", id=lid, receipt_id=rid, po_line_id=ln["id"],
            inventory_item_id=ITEM[self.sku], location_id=LOC[rcv]["id"], received_qty=qty, lot_number=None,
            expiry_date=None, note=None, qc_required=False, qc_location_id=None, moved_to_qc_at=None,
            moved_to_qc_by=None, created_at=when - timedelta(minutes=30))
        inbound = Stock(self.sku, rcv, qty, when, status="INBOUND")
        move("RECEIPT", self.sku, qty, "GOODS_RECEIPT_LINE", lid, when, self.clerk, to=rcv, ts_="INBOUND")
        # The order's progress (ReceivingPurchaseOrderAdapter.recordProgress): one event per status it moves to.
        ln["received"] += qty
        was = po["row"]["status"]
        now = "RECEIVED" if ln["received"] >= ln["qty"] else "PARTIALLY_RECEIVED"
        ln["row"]["status"] = now
        po["row"]["status"] = now
        if now != was:
            add("procurement.purchase_order_events", id=uid(f"po:{po['key']}:ev:{now}"), po_id=po["id"],
                po_revision_id=po["rev"], action=now, actor_id=U[self.clerk], from_status=was, to_status=now,
                reason=f"Goods receipt {number}", created_at=when)
        self.receipts.append(add("production.production_subcontract_receipt", receipt_id=rid,
                                 production_order_id=self.id, quantity=qty, received_at=when, used_blanks=qty,
                                 spoilt_blanks=None, returned_blanks=None, reconciled_at=None, reconciled_by=None))

        def consume(b, n, whole):
            s = b["stock"]
            s.on_hand -= n
            s.reserved -= n
            if whole:
                b["row"].update(status="CONSUMED", closed_at=when)
            move("CONSUMPTION", s.sku, n, "PRODUCTION_ORDER", self.id, when, None, frm=s.loc, lot=s.lot,
                 fs="IN_PRODUCTION", reason=f"Printed into receipt {number}")

        self.take(qty, f"receipt {number}", consume)
        hid = uid(f"res:subcontract-receipt-line:{lid}")
        inbound.on_hand -= qty
        there = Stock(self.sku, qca, qty, when, status="IN_PRODUCTION")
        there.reserved += qty
        move("MOVE", self.sku, qty, "PRODUCTION_ORDER", self.id, when, None, frm=rcv, to=qca, fs="INBOUND",
             ts_="IN_PRODUCTION", reason=f"Hold {hid}")
        row = add("inventory.stock_reservation", id=hid, stock_item_id=there.id, order_id=None,
                  root_request_id=uid(f"subcontract-receipt-line:{lid}"),
                  request_id=uid(f"subcontract-receipt-line:{lid}"), quantity=qty, reserved_at=when, expires_at=None,
                  status="HELD", release_reason=None, closed_at=None, production_order_id=self.id)
        add("production.production_subcontract_hold", reservation_id=hid, receipt_id=rid, receipt_line_id=lid,
            production_order_id=self.id)
        self.received.append((row, there, qty))
        return self.receipts[-1]

    def reconcile(self, receipt, spoilt, returned, when, decided=None):
        """Decision Q9, SubcontractService.reconcile: once per receipt, before QC may decide on it (BR-PRD-16)."""
        self.account(spoilt, returned, f"receipt {receipt['receipt_id']}", when, decided)
        receipt.update(spoilt_blanks=spoilt, returned_blanks=returned, reconciled_at=when, reconciled_by=U[self.clerk])

    def complete(self, when):
        """ProductionCompletion.complete: every hold of the job's goods to the packing area production_order recorded
        (finishToPacking: MOVE, IN_PRODUCTION -> AVAILABLE), handed to the sales order's line (handHoldToOrder,
        addProductionHolds) and consumed there (consumeFinishedGoods: CONSUMPTION, G4-Q2)."""
        packing = self.row["packing_location_code"]
        for row, qc_row, qty in self.received:
            qc_row.on_hand -= qty
            qc_row.reserved -= qty
            finished = Stock(self.sku, packing, qty, qc_row.received)
            move("MOVE", self.sku, qty, "PRODUCTION_ORDER", self.id, when, self.inspector, frm=qc_row.loc,
                 to=packing, fs="IN_PRODUCTION", ts_="AVAILABLE", reason=f"Hold {row['id']}")
            finished.on_hand -= qty
            move("CONSUMPTION", self.sku, qty, "PRODUCTION_ORDER", self.id, when, self.inspector, frm=packing,
                 fs="AVAILABLE", reason=f"Hold {row['id']}")
            row.update(stock_item_id=finished.id, order_id=self.row["sales_order_id"], production_order_id=None,
                       status="CONSUMED", closed_at=when)
            add("ordering.order_line_reservation", order_line_id=self.row["sales_order_line_id"],
                reservation_id=row["id"])

    def settle(self, po, when, approved=None):
        """BR-PRD-17, SubcontractSettling.settle + SubcontractPurchasingImpl.settle, by whoever ended the job: QC-passed
        units paid at the PO line's price, failed ones not; blanks lost above the subcontractor's tolerance (the floor
        of its percentage of the blanks sent) wait for the warehouse manager. `approved`: (when, deduction per blank,
        note) of the approveLoss that made it FINAL - the store's UPDATE moves the version."""
        tolerance = next(r for r in ROWS["procurement.suppliers"] if r["id"] == SUP[self.sup])["loss_tolerance_percent"]
        sent = sum(i["quantity"] for i in ISSUES if i["production_order_id"] == self.id)
        lost = sum(r["spoilt_blanks"] or 0 for r in self.receipts) + sum(r["spoilt_blanks"] for r in self.recalls)
        tolerated = sent * tolerance // 100
        excess = max(0, lost - tolerated)
        passed = sum(r[0] for r in self.rounds)
        price = po["lines"][0]["price"]
        row = add("procurement.subcontract_settlement", po_id=po["id"], production_order_id=self.id,
                  status="AWAITING_APPROVAL" if excess else "FINAL", currency="VND", unit_price=price,
                  received_quantity=sum(r["quantity"] for r in self.receipts), passed_quantity=passed,
                  failed_quantity=sum(r[1] for r in self.rounds), payable_amount=passed * price, blanks_sent=sent,
                  blanks_used=sum(r["used_blanks"] for r in self.receipts),
                  blanks_returned=sum(r["returned_blanks"] or 0 for r in self.receipts)
                  + sum(r["returned_blanks"] for r in self.recalls), blanks_lost=lost, tolerance_percent=tolerance,
                  tolerance_blanks=tolerated, excess_loss=excess, deduction_per_blank=None, deduction_amount=0,
                  cancellation_fee=0, cancellation_fee_reason=None, net_payable=passed * price, settled_at=when,
                  settled_by=U[self.inspector], approved_at=None, approved_by=None, approval_note=None, version=0)
        if excess and approved:
            approved_at, per_blank, note = approved
            row.update(status="FINAL", deduction_per_blank=per_blank, deduction_amount=excess * per_blank,
                       net_payable=row["payable_amount"] - excess * per_blank, approval_note=note,
                       approved_by=U[self.manager], approved_at=approved_at, version=1)
        return row


# Order statuses a delivered order passes through because of a return: still shipped and delivered.
RETURN_STATUSES = ("RETURN_REQUESTED", "RETURNED", "REFUNDED", "PARTIALLY_REFUNDED")


def return_times(delivered):
    """When each step of a return happens: claimed the day of delivery, decided the same day (before the order would
    complete), and for the one that is resolved, the parcel, QC, Sales' proposal and the accountant's approval."""
    requested = delivered + timedelta(hours=6)
    return dict(requested=requested, decided=requested + timedelta(hours=5), received=requested + timedelta(days=2),
                inspected=requested + timedelta(days=3), proposed=requested + timedelta(days=3, hours=2),
                settled=requested + timedelta(days=4))


def place_order(key, cust_code, d, final, lines_spec=None, custom=False, term=None, hold=None, cancel=None,
                prod_status=None, pick_partial=False, return_spec=None, cancel_request=None, sub=False, fixed=None):
    c = CUST[cust_code]
    # A print order ships from the warehouse that prints it: its production order works in the order's warehouse
    # (OrderProductionService) on the blanks held there at checkout (kltn-docs 19 BR-20).
    p = PRINT_WAREHOUSE[c["wh"]] if custom else c["wh"]
    oid = uid("order:" + key)
    terms = c["terms"]
    term = term or (terms[0] if terms else "PREPAID")
    credit = term == "CREDIT"
    # fixed = (placed at, step between statuses): an order placed after the last random draw of the data set draws
    # nothing, so everything generated before it stays exactly what it was.
    placed = fixed[0] if fixed else at(d, random.randint(8, 16), random.choice([0, 15, 30, 45]))
    number = next_no("SO", d, 6)
    # lines
    if lines_spec is None:
        pool = [v for v in SELLABLE if (c["wh"] != "DN" or v["sku"] in DN_SKUS)]
        random.shuffle(pool)
        lines_spec = [(v["sku"], random.randint(1, 6) * 5) for v in pool[:random.randint(1, 4)]]
        if custom:
            cust_pool = [v for v in SELLABLE if v["kind"] == "CUSTOMIZABLE" and (c["wh"] != "DN" or v["sku"] in DN_SKUS)]
            lines_spec[0] = (random.choice(cust_pool)["sku"], random.randint(2, 6) * 5)
            seen = set()
            lines_spec = [x for x in lines_spec if not (x[0] in seen or seen.add(x[0]))]
    lines = []
    reserve_from = {}
    for n, (sku, qty) in enumerate(lines_spec):
        alloc = allocate(p, sku, qty) if final != "DRAFT" else []
        if alloc is None:
            continue
        lid = uid(f"order:{key}:line:{n}")
        des = design_for(cust_code, sku, d) if custom and n == 0 else None
        lines.append(dict(id=lid, sku=sku, qty=qty, price=unit_price(c, sku), design=des))
        reserve_from[lid] = alloc
    if not lines:
        return None
    total = sum(ln["qty"] * ln["price"] for ln in lines)
    path = status_path(custom, credit, cancel[0] if cancel else (hold[0] if hold else final))
    if final == "DRAFT":
        path = ["DRAFT"]
    # timeline: one step every 1-2 days
    times, t = [], placed
    for s in path:
        times.append(t)
        t = t + (fixed[1] if fixed else timedelta(days=random.choice([1, 1, 2]), hours=random.randint(1, 5)))
    when = dict(zip(path, times))
    if "PICKING" in path and ("SHIPPED" in path or final == "PACKED") and not cancel and not hold:
        # Packed four hours after picking started, as the pack row says (packed_at = t_pick + 4h).
        path.insert(path.index("PICKING") + 1, "PACKED")
        when["PACKED"] = when["PICKING"] + timedelta(hours=4)
        times = [when[s] for s in path]
    # A return (SCRUM-243) moves the order as ReturnRequestService does: opening it puts a DELIVERED order into
    # RETURN_REQUESTED, which keeps it from completing (BR-ORD-005); a refusal puts it back to DELIVERED (and it
    # completes later), money given back takes it through RETURNED to PARTIALLY_REFUNDED. The path's own steps and their
    # random draws are kept, so the rest of the data set does not move; a resolved return only drops the COMPLETED step.
    rt = return_times(when["DELIVERED"]) if return_spec else None
    ret_steps = {"REQUESTED": [("RETURN_REQUESTED", "requested")],
                 "AWAITING_RETURN": [("RETURN_REQUESTED", "requested")],
                 "REJECTED": [("RETURN_REQUESTED", "requested"), ("DELIVERED", "decided")],
                 "RESOLVED": [("RETURN_REQUESTED", "requested"), ("RETURNED", "settled"),
                              ("PARTIALLY_REFUNDED", "settled")]}.get(return_spec, [])
    cur = path[-1]
    if hold:
        cur = "ON_HOLD"
    if cancel:
        cur = "CANCELLED"
    if return_spec == "RESOLVED":
        cur = "PARTIALLY_REFUNDED"
    elif return_spec in ("REQUESTED", "AWAITING_RETURN"):
        cur = "RETURN_REQUESTED"
    released = "READY_TO_FULFILL" in path or "IN_PRODUCTION" in path
    rel_at = when.get("IN_PRODUCTION", when.get("READY_TO_FULFILL"))
    deposit = money(total * (terms[1] if terms and terms[0] == "DEPOSIT" else 30) / 100 / 1000) * 1000 \
        if term == "DEPOSIT" else None
    ship, bill = c["addr"], c["addr"]
    row = add("ordering.customer_order", id=oid, order_number=number, customer_id=c["id"], request_id=uid(f"req:{key}"),
              status=cur, total_amount=total, currency="VND", placed_at=placed, cancellation_reason=None,
              contact_name=c["contact"], contact_email=c["email"], contact_phone=c["phone"],
              shipping_recipient_name=ship["recipient_name"], shipping_phone=ship["phone"],
              shipping_line1=ship["line1"], shipping_line2=None, shipping_ward_code=ship["ward_code"],
              shipping_ward_name=ship["ward"], shipping_province_code=ship["province_code"],
              shipping_province_name=ship["province"], shipping_country_code="VN", shipping_postal_code=None,
              billing_recipient_name=c["company"], billing_phone=c["phone"], billing_line1=bill["line1"],
              billing_line2="Phòng kế toán", billing_ward_code=bill["ward_code"], billing_ward_name=bill["ward"],
              billing_province_code=bill["province_code"], billing_province_name=bill["province"],
              billing_country_code="VN", billing_postal_code=None, payment_term=term, deposit_required=deposit,
              deposit_received_at=None, warehouse_id=WH[p] if final != "DRAFT" else None,
              released_at=rel_at if released else None, released_by=U["dieuphoi"] if released else None,
              paid_amount=0, paid_in_full_at=None, cancellation_reason_code=None, cancellation_retained_amount=None,
              credit_term_days=terms[2] if credit else None, created_at=placed,
              # G2-3: the carrier collects the balance of a subcontracted deposit order on delivery (COD below);
              # every other deposit order pays it by transfer before the handover.
              balance_policy=("ON_DELIVERY" if sub else "BEFORE_DELIVERY") if term == "DEPOSIT" else None)
    if term == "DEPOSIT" and sub and c.get("profile") is not None:
        c["profile"]["deposit_balance_policy"] = "ON_DELIVERY"
    # G3-2: the hold on the order, and the milestones the lifecycle CHECKs ask for.
    row.update(hold_reason=hold[1] if hold else None,
               status_before_hold=(None if hold[1] == "CREDIT" else hold[0]) if hold else None,
               hold_note=hold[2] if hold else None,
               shipped_at=when.get("SHIPPED") if cur in ("SHIPPED", "DELIVERED", "COMPLETED") + RETURN_STATUSES
               else None,
               delivered_at=when.get("DELIVERED") if cur in ("DELIVERED", "COMPLETED") + RETURN_STATUSES else None,
               completed_at=when.get("COMPLETED") if cur == "COMPLETED" else None,
               refunded_at=rt["settled"] if cur == "PARTIALLY_REFUNDED" else None,
               packed_at=when.get("PACKED"), billed_amount=0, receivable_paid_amount=0,
               # Review of #150 (V20261022000400): when its fulfillment task was opened - set below with the pick,
               # as the migration backfills it from fulfillment.pick (the create-task list leaves the order out).
               fulfilment_started_at=None)
    for ln in lines:
        add("ordering.order_line", id=ln["id"], order_id=oid, sku=ln["sku"], quantity=ln["qty"], unit_price=ln["price"],
            currency="VND", design_snapshot_id=ln["design"]["snapshot"] if ln["design"] else None,
            design_checksum=ln["design"]["checksum"] if ln["design"] else None)
    # history
    prev = None
    hist = list(path)
    if hold:
        hist.append("ON_HOLD")
        when["ON_HOLD"] = times[-1] + timedelta(hours=3)
    if cancel:
        hist.append("CANCELLED")
        when["CANCELLED"] = times[-1] + timedelta(hours=random.randint(4, 30))
    actors = {"PENDING_PAYMENT": c["user"], "DRAFT": c["user"], "CONFIRMED": None, "IN_PRODUCTION": U["dieuphoi"],
              "READY_TO_FULFILL": U["dieuphoi"], "PICKING": U[staff_of(p)[0]], "PACKED": U[staff_of(p)[0]],
              "SHIPPED": U[staff_of(p)[0]],
              "DELIVERED": None, "COMPLETED": None, "ON_HOLD": U["dieuphoi"], "CANCELLED": U["dieuphoi"]}
    entries = [(s, when[s], actors.get(s), {"ON_HOLD": hold[2] if hold else None,
                                            "CANCELLED": cancel[2] if cancel else None}.get(s)) for s in hist]
    rma = None
    if ret_steps:
        # The reasons ReturnRequestService writes ("RETURN_REQUESTED RMA-..."), by whoever moved the return.
        rma = next_no("RMA", rt["requested"].date())
        cut = hist.index("DELIVERED") + 1
        by = {"RETURN_REQUESTED": c["user"] or U["sales1"], "DELIVERED": U["dieuphoi"], "RETURNED": U["ketoan"],
              "PARTIALLY_REFUNDED": U["ketoan"]}
        why = {"RETURN_REQUESTED": "RETURN_REQUESTED", "DELIVERED": "RETURN_REJECTED", "RETURNED": "RETURN_RESOLVED"}
        steps = [(s, rt[t], by[s], f"{why[s]} {rma}" if s in why else None) for s, t in ret_steps]
        rest = [] if return_spec == "RESOLVED" else entries[cut:]
        entries = entries[:cut] + steps + rest
    for k, (s, t, actor, reason) in enumerate(entries):
        add("ordering.order_status_history", id=uid(f"order:{key}:h:{k}"), order_id=oid, from_status=prev, to_status=s,
            reason=reason, actor_id=actor, occurred_at=t, created_at=t)
        prev = s
    o = dict(id=oid, key=key, cust=cust_code, p=p, lines=lines, total=total, row=row, when=when, status=cur,
             term=term, number=number, custom=custom, deposit=deposit)
    ORDERS.append(o)
    if final == "DRAFT":
        return o
    # payments --------------------------------------------------------------------------------
    # G2-3 (decision G3-Q3): a bank transfer is recorded once in the ledger - payment.customer_transfer, read from the
    # statement by the accountant - and allocated to the order (transfer_allocation without a receivable); the order
    # counts the allocation (ordering.order_payment). Money still awaited is not a row: the order says what it waits
    # for. Only the carrier's COD of a subcontracted deposit order stays a payment.payment row, as the COD remittances
    # (payment.cod_remittance_line.payment_id) recorded it.
    paid = 0
    pays = []    # (refund column, id): where a refund of this money is placed

    def pay(tag, amount, method, at_, status="CAPTURED"):
        nonlocal paid
        if status not in ("CAPTURED", "REFUNDED"):
            return None
        if method == "BANK_TRANSFER":
            tid = uid(f"transfer:{key}:{tag}")
            aid = uid(f"alloc:{key}:{tag}")
            n = len(ROWS.get("payment.customer_transfer", []))
            add("payment.customer_transfer", id=tid, customer_id=c["id"],
                reference=f"VCB{at_.strftime('%y%m%d')}{n:05d}", amount=amount, unallocated_amount=0,
                currency="VND", received_on=at_.date(), recorded_by=U["ketoan"], recorded_at=at_,
                note=f"Chuyển khoản {number} theo sao kê Vietcombank", created_at=at_)
            add("payment.transfer_allocation", id=aid, transfer_id=tid, order_id=oid, receivable_id=None,
                amount=amount, allocated_at=at_, allocated_by=U["ketoan"])
            pid_, col = aid, "allocation_id"
        else:
            pid_ = uid(f"pay:{key}:{tag}")
            add("payment.payment", id=pid_, order_id=oid, amount=amount, currency="VND", method=method,
                status=status, provider_ref=f"COD-{number}" if method == "COD" else None, captured_at=at_,
                created_at=at_ - timedelta(hours=2))
            col = "payment_id"
        add("ordering.order_payment", payment_id=pid_, order_id=oid, amount=amount, currency="VND", captured_at=at_)
        paid += amount
        pays.append((col, pid_))
        return pid_

    confirmed = "CONFIRMED" in when
    if term == "PREPAID":
        if confirmed:
            pay("full", total, "BANK_TRANSFER", when["CONFIRMED"])
            row["paid_in_full_at"] = when["CONFIRMED"]
        else:
            pay("full", total, "BANK_TRANSFER", placed + timedelta(minutes=5),
                status="CANCELLED" if cancel else "PENDING")
    elif term == "DEPOSIT":
        if confirmed:
            pay("deposit", deposit, "BANK_TRANSFER", when["CONFIRMED"])
            row["deposit_received_at"] = when["CONFIRMED"]
            if sub and "SHIPPED" in when:
                # ON_DELIVERY: the carrier collects the balance - fixed at the handover - and remits it.
                row["cod_amount"] = total - deposit
                if "DELIVERED" in when:
                    pid_ = pay("rest", total - deposit, "COD", when["DELIVERED"])
                    row["paid_in_full_at"] = when["DELIVERED"]
                    SHIPMENTS_COD.append((o, pid_, total - deposit))
            elif "SHIPPED" in when:
                # BEFORE_DELIVERY: the balance arrives before the handover (decision G3-Q4).
                rest_at = when["SHIPPED"] - timedelta(hours=3)
                pay("rest", total - deposit, "BANK_TRANSFER", rest_at)
                row["paid_in_full_at"] = rest_at
        else:
            pay("deposit", deposit, "BANK_TRANSFER", placed + timedelta(minutes=5),
                status="CANCELLED" if cancel else "PENDING")
    else:  # CREDIT
        limit = terms[1]
        exposure = sum(r["amount"] - r["paid"] for r in RECEIVABLES if r["cust"] == cust_code)
        over = hold and hold[1] == "CREDIT"
        add("ordering.order_credit_check", id=uid(f"cc:{key}"), order_id=oid, checked_at=placed,
            credit_limit=limit, exposure=exposure if not over else max(exposure, limit - total // 2),
            order_amount=total, outcome="OVER_LIMIT" if over else "WITHIN_LIMIT", decided_by=None, decided_at=None,
            note=None, created_at=placed)
        if "DELIVERED" in when:
            issued = when["DELIVERED"]
            due = issued.date() + timedelta(days=terms[2])
            r = dict(id=uid(f"recv:{key}"), order=oid, cust=cust_code, amount=total, paid=0, issued=issued, due=due,
                     order_row=row)
            row["billed_amount"] = total
            RECEIVABLES.append(r)
    row["paid_amount"] = paid
    # production -----------------------------------------------------------------------------------
    # Before the holds: how far a print line's production order got decides where its holds are now.
    made = {}
    for n, ln in enumerate(lines):
        if not ln["design"] or "IN_PRODUCTION" not in when:
            continue
        ps = "COMPLETED" if "READY_TO_FULFILL" in when else (prod_status or "PRINTING")
        hold_p = ("PRINTING", "MACHINE_DOWN", "Máy in flexo số 2 hỏng trục, chờ thay") if ps == "ON_HOLD" else None
        pid_ = production_order(f"order:{key}:{n}", "ORDER", p, ln["sku"], ln["design"]["snapshot"],
                                ln["design"]["checksum"], ln["qty"], ps, when["IN_PRODUCTION"], order=oid, line=ln["id"],
                                # G2-3: a `sub` order's print line is printed by a subcontractor - converted
                                # whole (BR-PRD-12) - and its balance collected on delivery.
                                mode="SUBCONTRACTED" if ps == "SUBCONTRACTED" or sub else "IN_HOUSE",
                                subcontractor="INHN" if sub else "INSG", hold=hold_p,
                                end=when["READY_TO_FULFILL"] - timedelta(hours=1) if ps == "COMPLETED" else None)
        ln["production"] = pid_
        made[ln["id"]] = PRODUCTION[-1]
    # reservations ------------------------------------------------------------------------------
    picked_state = None
    if "PICKING" in when and not (cancel and cancel[0] == "PICKING"):
        picked_state = "PICKING" if (cur == "PICKING" and pick_partial) else "PICKED"
    if cur == "ON_HOLD" and "PICKING" in when:
        picked_state = "PICKING"
    consumed = picked_state == "PICKED"
    res_rows = []
    for ln in lines:
        prod = made.get(ln["id"])
        # Where the print shop took this line's holds (MaterialIssueService.issue, ProductionCompletion.complete).
        stage = None
        if prod is not None and prod["row"]["mode"] == "IN_HOUSE" and "MATERIAL_ISSUED" in prod["times"]:
            stage = "DONE" if prod["done"] else "ISSUED"
        # A subcontracted line's checkout holds gave their units to its production order when it was converted
        # (reduceHold, SUBCONTRACTED), and those went to the subcontractor with the work (SubcontractJob.take_over).
        sent = prod is not None and prod["row"]["mode"] == "SUBCONTRACTED" and "SUBCONTRACTED" in prod["times"]
        for k, (s, qn) in enumerate(reserve_from[ln["id"]]):
            rid = uid(f"res:{key}:{ln['id']}:{k}")
            holder = s
            if cur == "CANCELLED":
                st, closed, reason, exp = ("EXPIRED", when["CANCELLED"], "RESERVATION_EXPIRED",
                                           placed + timedelta(days=2)) if cancel[1] == "PAYMENT_NOT_RECEIVED" else \
                    ("RELEASED", when["CANCELLED"], "ORDER_CANCELLED", None)
            elif stage == "DONE":
                # G4-Q2: the printed goods left stock when the production order completed - never picked.
                st, closed, reason, exp = "CONSUMED", prod["completed"], None, None
            elif sent:
                st, closed, reason, exp = "RELEASED", converted_at(prod), "SUBCONTRACTED", None
            elif ln["design"] is None and (consumed or (picked_state == "PICKING" and k == 0 and ln is lines[0]
                                                        and cur != "ON_HOLD")):
                st, closed, reason, exp = "CONSUMED", when["PICKING"] + timedelta(hours=2), None, None
            else:
                st, closed, reason = "HELD", None, None
                exp = placed + timedelta(days=7) if cur == "PENDING_PAYMENT" else None
            if stage:
                holder = print_shop(prod, rid, s, qn, done=stage == "DONE")
            if sent:
                subcontract_job(prod).take_over(s, qn)
            res = add("inventory.stock_reservation", id=rid, stock_item_id=holder.id, order_id=oid,
                      root_request_id=uid(f"req:{key}"), request_id=uid(f"req:{key}:{ln['id']}:{k}"), quantity=qn,
                      reserved_at=placed, expires_at=exp, status=st, release_reason=reason, closed_at=closed)
            add("ordering.order_line_reservation", order_line_id=ln["id"], reservation_id=rid)
            if st == "HELD":
                holder.reserved += qn
            elif st == "CONSUMED" and not stage:
                s.on_hand -= qn
            res_rows.append(dict(id=rid, line=ln, stock=s, qty=qn, status=st, row=res))
    # fulfilment ----------------------------------------------------------------------------------
    if picked_state:
        staff, staff2, mgr, qcu = staff_of(p)
        t_pick = when["PICKING"]
        wave_id = WAVES.get((p, t_pick.date()))
        if wave_id is None:
            wave_id = uid(f"wave:{p}:{t_pick.date()}")
            WAVES[(p, t_pick.date())] = wave_id
            add("fulfillment.wave", id=wave_id, code=f"WAVE-{p}-{t_pick.strftime('%Y%m%d')}",
                status="COMPLETED" if t_pick.date() < TODAY - timedelta(days=1) else "RELEASED",
                released_at=at(t_pick.date(), 7, 30), created_at=at(t_pick.date(), 7))
        pick_id = uid(f"pick:{key}")
        pick_done = picked_state == "PICKED"
        row["fulfilment_started_at"] = t_pick          # the task's creation, as the migration's backfill
        add("fulfillment.pick", id=pick_id, wave_id=wave_id, order_id=oid, order_number=number,
            warehouse_id=WH[p], status=picked_state, assigned_user_id=U[staff], assigned_at=t_pick, started_at=t_pick + timedelta(minutes=20),
            picked_at=t_pick + timedelta(hours=2) if pick_done else None, needs_put_back=False, created_at=t_pick)
        # SCRUM-267 / G4-Q2: a pick has lines for the stock lines only (PickListService.holdsOf); a print line's goods
        # left stock when its production order completed and are packed straight from the packing area.
        for k, r in enumerate(r for r in res_rows if r["line"]["design"] is None):
            done = r["status"] == "CONSUMED"
            plid = uid(f"pickline:{key}:{k}")
            add("fulfillment.pick_line", id=plid, pick_id=pick_id, order_line_id=r["line"]["id"], reservation_id=r["id"],
                sku=r["line"]["sku"], location_code=r["stock"].loc, lot_number=r["stock"].lot, sequence_no=k + 1,
                allocated_qty=r["qty"], picked_qty=r["qty"] if done else 0, short_qty=0,
                status="PICKED" if done else "OPEN", picked_by=U[staff] if done else None,
                picked_at=t_pick + timedelta(hours=2) if done else None,
                # SCRUM-267: a PICKED line has deducted its hold (ck_pick_line_picked).
                consumed_at=t_pick + timedelta(hours=2) if done else None, created_at=t_pick)
            if done:
                move("PICK", r["line"]["sku"], r["qty"], "PICK_LINE", plid, t_pick + timedelta(hours=2), staff,
                     frm=r["stock"].loc, lot=r["stock"].lot, fs="AVAILABLE")
        pack_id = uid(f"pack:{key}")
        if not pick_done:
            # The application opens a pick and its pack together (FulfillmentServiceImpl.createTask); the task
            # list reads the pack of every pick and answers 404 for the whole page if one is missing.
            add("fulfillment.pack", id=pack_id, pick_id=pick_id, status="PENDING", packed_at=None, created_at=t_pick)
        else:
            shipped = "SHIPPED" in when
            pack_status = "PACKED" if shipped or cur == "PACKED" else "PACKING"
            if cur == "ON_HOLD":
                pack_status = "ON_HOLD"
            packer = U[staff2]
            add("fulfillment.pack", id=pack_id, pick_id=pick_id, status=pack_status,
                packed_at=t_pick + timedelta(hours=4) if pack_status == "PACKED" else None,
                # G4-5: the packer who took the order at the packing table and completed it.
                started_at=t_pick + timedelta(hours=2, minutes=30), started_by=packer,
                packed_by=packer if pack_status == "PACKED" else None,
                created_at=t_pick + timedelta(hours=2))
            for n, ln in enumerate(lines):
                if ln["design"]:
                    add("fulfillment.design_verification", id=uid(f"dv:{key}:{n}"), pack_id=pack_id, order_id=oid,
                        order_line_id=ln["id"], snapshot_id=ln["design"]["snapshot"],
                        expected_checksum=ln["design"]["checksum"], actual_checksum=ln["design"]["checksum"],
                        result="MATCH", verified_by=U[staff2], verified_at=t_pick + timedelta(hours=3),
                        created_at=t_pick + timedelta(hours=3))
            # G4-5 (V20261021000300): a completed packing is one shipment READY_TO_DISPATCH (09 §5); the handover
            # makes it HANDED_OVER, the coordinator records DELIVERED. No carrier is called (09 §1): no tracking number,
            # no carrier feed - the timeline is the MANUAL steps, each naming who recorded it (BR-02).
            packed = pack_status == "PACKED"
            ship_id = uid(f"ship:{key}") if packed else None
            t_ready = t_pick + timedelta(hours=4)
            n_pk = 2 if sum(ln["qty"] for ln in lines) > 40 else 1
            if packed:
                sstatus = "DELIVERED" if "DELIVERED" in when else "HANDED_OVER" if shipped else "READY_TO_DISPATCH"
                carrier = CARRIERS[len(ORDERS) % 4] if shipped else None
                # G4-2a: every other shipment still waiting at dispatch already has its carrier chosen (09 §4.1
                # step 2), so a load opened for that carrier takes it.
                chosen = carrier if shipped else (CARRIERS[len(ORDERS) % 4] if len(ORDERS) % 2 else None)
                # 09 BR-04: what the carrier collects - the balance of an ON_DELIVERY deposit order.
                cod = row.get("cod_amount") or ((total - deposit) if term == "DEPOSIT" and sub else 0)
                if not shipped:
                    READY_SHIPMENTS.append((ship_id, p, chosen, [uid(f"pkg:{key}:{k}") for k in range(n_pk)],
                                            cod, t_ready))
                # Shipments handed over before loads existed are whole_order_handover (V20261021000400): no manifest
                # names them, and their order was shipped and delivered as a whole (no ordering.order_shipment).
                # Those handed over since LOADS_LIVE leave on signed dispatch loads (section 8, before the COD
                # remittances), and the order records them in ordering.order_shipment (DispatchService.handOver).
                add("fulfillment.shipment", id=ship_id, pack_id=pack_id, order_id=oid, carrier=carrier,
                    carrier_id=carrier_ref(chosen) if chosen else None, whole_order_handover=shipped,
                    tracking_number=None, status=sstatus, dispatched_at=when.get("SHIPPED") if shipped else None,
                    code=f"{number}-S1", warehouse_id=WH[p], delivery_mode="PARCEL", package_count=n_pk,
                    cod_amount=cod, label_cod_amount=cod, partial=False, ready_at=t_ready, ready_by=packer,
                    handed_over_at=when["SHIPPED"] if shipped else None,
                    handed_over_by=U[staff] if shipped else None,
                    outcome_at=when["DELIVERED"] if "DELIVERED" in when else None,
                    outcome_by=U["dieuphoi"] if "DELIVERED" in when else None, created_at=t_ready)
                evs = [("READY_TO_DISPATCH", t_ready, packer)]
                if shipped:
                    evs.append(("HANDED_OVER", when["SHIPPED"], U[staff]))
                if "DELIVERED" in when:
                    evs.append(("DELIVERED", when["DELIVERED"], U["dieuphoi"]))
                for k, (es, et, who) in enumerate(evs):
                    add("fulfillment.shipment_event", id=uid(f"sev:{key}:{k}"), shipment_id=ship_id, status=es,
                        occurred_at=et, source="MANUAL", carrier_event_id=None, location_text="Kho " + p,
                        note=None, raw_payload=None, recorded_by=who, created_at=et)
            if shipped:
                add("notification.delivery_log", id=uid(f"mail:ship:{key}"), template_code="shipment.dispatched",
                    channel="EMAIL", recipient=c["email"], status="SENT", error=None, sent_at=when["SHIPPED"],
                    external_reference=None, operation_reference=f"shipment:{ship_id}", terminal=True,
                    delivery_generation=0, attempt_number=1, created_at=when["SHIPPED"])
            o["shipment"] = ship_id if shipped else None
            for k in range(n_pk):
                pk = uid(f"pkg:{key}:{k}")
                inside = [ln for m, ln in enumerate(lines) if m % n_pk == k]
                add("fulfillment.package", id=pk, pack_id=pack_id, shipment_id=ship_id, package_no=k + 1,
                    label_code=f"PKG-{number}-{k + 1}", package_type="PALLET" if n_pk > 1 else "CARTON",
                    weight_kg=round(sum(VAR[ln["sku"]]["dims"][0] * ln["qty"] for ln in lines) / n_pk, 3),
                    length_cm=120, width_cm=100, height_cm=150, dim_weight_kg=300.0,
                    tracking_number=None, label_url=None, rotatable=True, rotatable_overridden=False,
                    fragile=False, fragile_overridden=False,
                    status="SEALED" if packed else "OPEN", sealed_at=t_ready if packed else None,
                    sealed_by=packer if packed else None, created_at=t_pick + timedelta(hours=3))
                for m, ln in enumerate(lines):
                    if m % n_pk == k:
                        add("fulfillment.package_line", id=uid(f"pkgl:{key}:{k}:{m}"), package_id=pk,
                            order_line_id=ln["id"], quantity=ln["qty"], created_at=t_pick + timedelta(hours=3))
                        # G4-5: the scan that put the goods in (append-only); package_line is its running total.
                        add("fulfillment.package_scan", id=uid(f"pscan:{key}:{k}:{m}"), package_id=pk,
                            order_line_id=ln["id"], scanned_code=ln["sku"], lot_number=None, serial_number=None,
                            quantity=ln["qty"], scanned_by=packer, scanned_at=t_pick + timedelta(hours=3),
                            created_at=t_pick + timedelta(hours=3))
    # hold, cancellation -----------------------------------------------------------------------
    if hold:
        add("ordering.order_hold", id=uid(f"hold:{key}"), order_id=oid, reason=hold[1], raised_at=when["ON_HOLD"],
            resolved_at=None, resolved_by=None, resolution_note=None, created_at=when["ON_HOLD"])
    if cancel:
        row.update(cancellation_reason=cancel[2], cancellation_reason_code=cancel[1])
        if cancel_request:
            pct = cancel_request
            add("ordering.order_cancellation_request", id=uid(f"cxl:{key}"), order_id=oid, requested_by=c["user"],
                reason_code="CUSTOMER_REQUEST", note="Khách đổi kế hoạch khai trương", requested_at=when["CANCELLED"]
                - timedelta(hours=3), status="APPROVED", decided_by=U["dieuphoi"], decided_at=when["CANCELLED"],
                decision_note=f"Đồng ý hủy, giữ {pct}% phí đã chuẩn bị hàng", retained_percent=pct,
                created_at=when["CANCELLED"] - timedelta(hours=3))
            retained = money(paid * pct / 100)
            row["cancellation_retained_amount"] = retained
            if paid:
                add("payment.refund", id=uid(f"refund:{key}"), **{pays[0][0]: pays[0][1]}, amount=paid - retained,
                    currency="VND", reason="Hoàn tiền hủy đơn (trừ phí)", status="COMPLETED",
                    refunded_at=when["CANCELLED"] + timedelta(days=1), order_id=oid, source="ORDER_CANCELLED",
                    created_at=when["CANCELLED"])
    if return_spec:
        # A return request (SCRUM-243, V20261101020000) at one of the points the flow stops, from the customer's
        # portal (or opened by Sales for a customer with no portal account): REQUESTED → AWAITING_RETURN (approved, goods to come back) → RECEIVED (counted into quarantine) →
        # INSPECTED → SETTLEMENT_PENDING (Sales proposes) → RESOLVED (the accountant approves: four eyes), or
        # REQUESTED → REJECTED. The resolved one is refunded in cash for the accepted units; QC sends them to scrap,
        # an adjustment still waiting for its second person, so the units stay in quarantine meanwhile.
        rstatus = return_spec
        rr = uid(f"rma:{key}")
        ln = lines[0]
        rqty = max(1, ln["qty"] // 5)
        rejected = rstatus == "REJECTED"
        approved = rstatus in ("AWAITING_RETURN", "RESOLVED")
        resolved = rstatus == "RESOLVED"
        qrt, receiver, inspector = f"{p}-QRT01", staff_of(p)[0], staff_of(p)[3]
        credit = rqty * ln["price"] if resolved else 0
        requester = c["user"] or U["sales1"]
        add("ordering.return_request", id=rr, rma_number=rma, order_id=oid, customer_id=c["id"], order_number=number,
            request_id=uid(f"rma:req:{key}"), channel="PORTAL" if c["user"] else "BACK_OFFICE", requested_by=requester,
            requested_resolution={"REQUESTED": "REPLACEMENT", "AWAITING_RETURN": "CREDIT_NOTE"}.get(rstatus, "REFUND"),
            status=rstatus, reason_code="CHANGED_MIND" if rejected else "DAMAGED_IN_TRANSIT",
            note="Khách muốn đổi size" if rejected else "Một số thùng bị móp, ly bên trong biến dạng",
            requested_at=rt["requested"], decided_by=U["dieuphoi"] if rstatus != "REQUESTED" else None,
            decided_at=rt["decided"] if rstatus != "REQUESTED" else None,
            decision_note="Ảnh khách gửi cho thấy thùng móp khi giao" if approved else None,
            return_required=True if approved else None,
            return_deadline=rt["decided"] + timedelta(days=14) if approved else None,
            rejection_reason="Đổi trả chỉ áp dụng cho hàng lỗi (BR-RMA-002)" if rejected else None,
            received_at=rt["received"] if resolved else None, received_by=U[receiver] if resolved else None,
            receiving_location_code=qrt if resolved else None, parcel_count=1 if resolved else 0,
            inspected_by=U[inspector] if resolved else None, inspected_at=rt["inspected"] if resolved else None,
            inspection_note="Móp miệng ly, không bán lại được" if resolved else None,
            money_mode="REFUND" if resolved else None, settlement_amount=credit,
            proposed_by=U["sales1"] if resolved else None, proposed_at=rt["proposed"] if resolved else None,
            resolution_note="Hoàn tiền phần hàng lỗi" if resolved else None,
            settled_by=U["ketoan"] if resolved else None, settled_at=rt["settled"] if resolved else None,
            refunded_amount=credit, vat_adjustment_required=resolved,
            closed_at=rt["settled"] if resolved else (rt["decided"] if rejected else None), created_at=rt["requested"])
        rl = uid(f"rmal:{key}")
        scrap = None
        if resolved:
            Stock(ln["sku"], qrt, rqty, rt["received"], "QUARANTINE", key=f"return:{key}")
            move("RETURN_IN", ln["sku"], rqty, "RETURN_REQUEST_LINE", rl, rt["received"], receiver, to=qrt,
                 ts_="QUARANTINE", reason=f"Hàng khách trả {rma}")
            scrap = uid(f"adj:rma:{key}")
            add("inventory.stock_adjustment", id=scrap, adjustment_number=next_no("ADJ", rt["inspected"].date()),
                location_code=qrt, sku=ln["sku"], lot_number=None, quantity_delta=-rqty, reason_code="SCRAP",
                note=f"{rma}: hàng trả lại hỏng, hủy", status="PENDING_APPROVAL", cycle_count_line_id=None,
                requested_by=U[inspector], decided_by=None, decided_at=None, rejection_reason=None, posted_at=None,
                withdrawn_at=None, created_at=rt["inspected"])
            add("payment.refund", id=uid(f"refund:rma:{key}"), **{pays[0][0]: pays[0][1]}, amount=credit,
                currency="VND", reason=f"Hoàn tiền hàng trả lại {rma}", status="COMPLETED",
                refunded_at=rt["settled"] + timedelta(days=1), order_id=oid, source="RETURN", return_request_id=rr,
                created_at=rt["settled"])
        add("ordering.return_request_line", id=rl, return_request_id=rr, order_line_id=ln["id"], quantity=rqty,
            sku=ln["sku"], custom_printed=ln["design"] is not None, unit_price=ln["price"],
            approved_quantity=rqty if approved else None, received_quantity=rqty if resolved else None,
            accepted_quantity=rqty if resolved else None, rejected_quantity=0 if resolved else None,
            item_condition="DEFECTIVE" if resolved else None, scrap_quantity=rqty if resolved else 0,
            scrap_adjustment_id=scrap, credit_quantity=rqty if resolved else 0, credit_amount=credit,
            created_at=rt["requested"])
        flow = {"REQUESTED": [], "REJECTED": [("REJECTED", "decided", "dieuphoi")],
                "AWAITING_RETURN": [("AWAITING_RETURN", "decided", "dieuphoi")],
                "RESOLVED": [("AWAITING_RETURN", "decided", "dieuphoi"), ("RECEIVED", "received", receiver),
                             ("INSPECTED", "inspected", inspector), ("SETTLEMENT_PENDING", "proposed", "sales1"),
                             ("RESOLVED", "settled", "ketoan")]}[rstatus]
        prev_rs = None
        for k, (s, t, actor) in enumerate([("REQUESTED", "requested", None)] + flow):
            add("ordering.return_request_status_history", id=uid(f"rma:{key}:h:{k}"), return_request_id=rr,
                from_status=prev_rs, to_status=s, reason=None, actor_id=U[actor] if actor else requester,
                occurred_at=rt[t], created_at=rt[t])
            prev_rs = s
    add("notification.delivery_log", id=uid(f"mail:placed:{key}"), template_code="order.placed", channel="EMAIL",
        recipient=c["email"], status="SENT" if len(ORDERS) % 9 else "FAILED",
        error=None if len(ORDERS) % 9 else "550 5.1.1 Mailbox unavailable",
        sent_at=placed + timedelta(minutes=1) if len(ORDERS) % 9 else None, external_reference=None,
        operation_reference=f"order:{oid}", terminal=True, delivery_generation=0,
        attempt_number=1 if len(ORDERS) % 9 else 3, created_at=placed + timedelta(minutes=1))
    return o


WAVES = {}
C = list(CUST)
ACTIVE = [k for k in C if CUST[k]["status"] == "ACTIVE"]
PREPAID = [k for k in ACTIVE if not CUST[k]["terms"]]
DEPOSIT = [k for k in ACTIVE if CUST[k]["terms"] and CUST[k]["terms"][0] == "DEPOSIT"]
CREDIT = [k for k in ACTIVE if CUST[k]["terms"] and CUST[k]["terms"][0] == "CREDIT"]


def day(n):
    return date(2026, 8, 12) + timedelta(days=n)


plan = []
# history: completed orders across August-September (enough for the reports)
for i in range(22):
    pool = [PREPAID, DEPOSIT, CREDIT][i % 3]
    plan.append(dict(cust=pool[i % len(pool)], d=day(i * 2), final="COMPLETED", custom=i % 4 == 1, sub=i % 6 == 4))
for i in range(5):
    pool = [PREPAID, CREDIT, DEPOSIT, CREDIT, PREPAID][i]
    plan.append(dict(cust=pool[(i + 3) % len(pool)], d=day(40 + i * 2), final="DELIVERED", custom=i == 2, sub=i == 2))
for i in range(5):
    pool = [PREPAID, CREDIT, DEPOSIT, PREPAID, CREDIT][i]
    plan.append(dict(cust=pool[(i + 1) % len(pool)], d=day(48 + i), final="SHIPPED", custom=i == 3))
for i in range(4):
    plan.append(dict(cust=[PREPAID, CREDIT, DEPOSIT, CREDIT][i][i % 3], d=day(52 + i),
                     final="PICKING" if i % 2 == 1 else "PACKED", pick_partial=i % 2 == 1))
for i in range(3):
    plan.append(dict(cust=[CREDIT, PREPAID, DEPOSIT][i][(i + 2) % 3], d=day(53 + i), final="READY_TO_FULFILL",
                     custom=i == 0))
for i, ps in enumerate(["PRINTING", "QC", "MATERIAL_ISSUED", "ON_HOLD", "SUBCONTRACTED", "READY",
                        "PENDING_PREPRESS"]):
    plan.append(dict(cust=[CREDIT, DEPOSIT][i % 2][i % 3], d=day(46 + i), final="IN_PRODUCTION", custom=True,
                     prod_status=ps))
for i in range(5):
    plan.append(dict(cust=[PREPAID, CREDIT, DEPOSIT, PREPAID, CREDIT][i][(i + 4) % 3], d=day(55 + i),
                     final="CONFIRMED"))
for i in range(5):
    plan.append(dict(cust=[PREPAID, DEPOSIT][i % 2][i % 4 if i % 2 == 0 else i % 3], d=day(57 + i % 3),
                     final="PENDING_PAYMENT"))
plan.append(dict(cust=PREPAID[1], d=day(59), final="DRAFT"))
plan.append(dict(cust=DEPOSIT[2], d=day(60), final="DRAFT"))
# exceptions
plan.append(dict(cust=CREDIT[0], d=day(56), final="CONFIRMED", hold=("CONFIRMED", "CREDIT",
                                                                      "Vượt hạn mức tín dụng, chờ kế toán duyệt")))
plan.append(dict(cust=PREPAID[2], d=day(50), final="PICKING",
                 hold=("PICKING", "ADDRESS", "Địa chỉ giao không liên lạc được, chờ khách xác nhận")))
plan.append(dict(cust=PREPAID[3], d=day(20), final="PENDING_PAYMENT",
                 cancel=("PENDING_PAYMENT", "PAYMENT_NOT_RECEIVED", "Quá hạn thanh toán, hệ thống tự hủy")))
plan.append(dict(cust=PREPAID[4], d=day(30), final="CONFIRMED", cancel_request=10,
                 cancel=("CONFIRMED", "CUSTOMER_REQUEST", "Khách yêu cầu hủy - lùi ngày khai trương")))
plan.append(dict(cust=DEPOSIT[0], d=day(34), final="PENDING_PAYMENT",
                 cancel=("PENDING_PAYMENT", "DUPLICATE_ORDER", "Khách đặt trùng đơn")))
# Returns (SCRUM-243): the resolved one's order never completes (its COMPLETED step is dropped), the refused one's does.
plan.append(dict(cust=PREPAID[0], d=day(14), final="COMPLETED", return_spec="RESOLVED"))
plan.append(dict(cust=PREPAID[5 % len(PREPAID)], d=day(38), final="COMPLETED", return_spec="REJECTED"))
plan.append(dict(cust=DEPOSIT[1], d=day(41), final="DELIVERED", return_spec="REQUESTED"))
plan.append(dict(cust=PREPAID[6 % len(PREPAID)], d=day(42), final="DELIVERED", return_spec="AWAITING_RETURN"))

plan.sort(key=lambda x: x["d"])
for i, spec in enumerate(plan):
    spec = dict(spec)
    final = spec.pop("final")
    place_order(f"o{i:03d}", spec.pop("cust"), spec.pop("d"), final, **spec)

# a confirmed order with a pending cancellation request, and an in-production one with a refused request
pend = next(o for o in ORDERS if o["status"] == "CONFIRMED" and o["term"] == "PREPAID")
add("ordering.order_cancellation_request", id=uid("cxl:pending"), order_id=pend["id"],
    requested_by=CUST[pend["cust"]]["user"], reason_code="OTHER", note="Muốn đổi sang mẫu ly 2 lớp",
    requested_at=pend["when"]["CONFIRMED"] + timedelta(hours=6), status="PENDING", decided_by=None, decided_at=None,
    decision_note=None, retained_percent=None, created_at=pend["when"]["CONFIRMED"] + timedelta(hours=6))
inprod = next(o for o in ORDERS if o["status"] == "IN_PRODUCTION")
add("ordering.order_cancellation_request", id=uid("cxl:rejected"), order_id=inprod["id"],
    requested_by=CUST[inprod["cust"]]["user"], reason_code="CUSTOMER_REQUEST", note="Khách muốn hủy vì đổi logo",
    requested_at=inprod["when"]["IN_PRODUCTION"] + timedelta(days=1), status="REJECTED", decided_by=U["dieuphoi"],
    decided_at=inprod["when"]["IN_PRODUCTION"] + timedelta(days=1, hours=2),
    decision_note="Đơn đã in xong 70%, không thể hủy", retained_percent=None,
    created_at=inprod["when"]["IN_PRODUCTION"] + timedelta(days=1))
# a resolved hold in the history of a completed order
done_o = [o for o in ORDERS if o["status"] == "COMPLETED"][3]
add("ordering.order_hold", id=uid("hold:resolved"), order_id=done_o["id"], reason="PAYMENT",
    raised_at=done_o["when"]["CONFIRMED"] + timedelta(hours=1), resolved_at=done_o["when"]["CONFIRMED"] + timedelta(hours=5),
    resolved_by=U["ketoan"], resolution_note="Đã đối soát sao kê, tiền về đủ",
    created_at=done_o["when"]["CONFIRMED"] + timedelta(hours=1))
# credit over the limit approved by the accountant
for o in ORDERS:
    if o["term"] == "CREDIT" and o["status"] == "COMPLETED":
        add("ordering.order_credit_check", id=uid(f"cc:approve:{o['key']}"), order_id=o["id"],
            checked_at=o["row"]["placed_at"] + timedelta(minutes=30), credit_limit=CUST[o["cust"]]["terms"][1],
            exposure=CUST[o["cust"]]["terms"][1], order_amount=o["total"], outcome="APPROVED", decided_by=U["ketoan"],
            decided_at=o["row"]["placed_at"] + timedelta(hours=2), note="Khách thanh toán đều, duyệt vượt hạn mức",
            created_at=o["row"]["placed_at"] + timedelta(minutes=30))
        break

# receivables and the customer transfers that paid them
for i, r in enumerate(RECEIVABLES):
    if r["due"] < date(2026, 9, 20) or i % 4 == 0:
        r["paid"] = r["amount"]
    elif i % 4 == 1:
        r["paid"] = money(r["amount"] * 0.4 / 1000) * 1000
    status = "PAID" if r["paid"] == r["amount"] else ("OVERDUE" if r["due"] < TODAY else
                                                       ("PARTIALLY_PAID" if r["paid"] else "OPEN"))
    paid_at = at(min(r["due"] - timedelta(days=3), TODAY - timedelta(days=1)), 10)
    add("payment.receivable", id=r["id"], order_id=r["order"], customer_id=CUST[r["cust"]]["id"], kind="CREDIT_ORDER",
        amount=r["amount"], paid_amount=r["paid"], currency="VND", issued_at=r["issued"], due_date=r["due"],
        status=status, settled_at=paid_at if status == "PAID" else None, created_at=r["issued"])
    r["order_row"]["paid_amount"] = r["paid"]
    r["order_row"]["receivable_paid_amount"] = r["paid"]
    if status == "PAID":
        r["order_row"]["paid_in_full_at"] = paid_at
    if r["paid"]:
        # Credit left over only from a transfer that paid its receivable in full: the application allocates a
        # transfer to the oldest unpaid receivable first, so a part-paid one never sits next to unallocated money of
        # the same transfer (that shape made the next delivery's allocation collide on uk_transfer_allocation).
        extra = 5000000 if i % 5 == 0 and r["paid"] == r["amount"] else 0
        t = uid(f"transfer:{r['id']}")
        add("payment.customer_transfer", id=t, customer_id=CUST[r["cust"]]["id"],
            reference=f"FT{paid_at.strftime('%y%m%d')}{i:06d}", amount=r["paid"] + extra, unallocated_amount=extra,
            currency="VND", received_on=paid_at.date(), recorded_by=U["ketoan"], recorded_at=paid_at + timedelta(hours=2),
            note="Thanh toán công nợ theo sao kê Vietcombank" + (" - dư chuyển kỳ sau" if extra else ""),
            created_at=paid_at + timedelta(hours=2))
        add("payment.transfer_allocation", id=uid(f"alloc:{r['id']}"), transfer_id=t, order_id=r["order"],
            receivable_id=r["id"],
            amount=r["paid"], allocated_at=paid_at + timedelta(hours=2), allocated_by=U["ketoan"])

# G4-2a: one parcel load open at the dispatch area (09 §4.1 step 3) - the driver of a carrier arrived, the dispatcher
# put that carrier's waiting shipment on the manifest and scanned its first parcel; the rest is left to scan and sign.
loading = next((r for r in READY_SHIPMENTS if r[2] is not None), None)
if loading:
    ship_id, p, carrier, parcels, label_cod, t_ready = loading
    load_id = uid("load:open")
    opened = t_ready + timedelta(hours=1)
    dispatcher = U[staff_of(p)[0]]
    add("fulfillment.dispatch_load", id=load_id, code=f"LD-{opened.strftime('%Y%m%d')}-0001",
        request_id=uid("load:open:request"), warehouse_id=WH[p], mode="PARCEL", carrier_id=carrier_ref(carrier),
        load_plan_vehicle_id=None, vehicle_plate="29C-456.78", driver_name="Lê Văn Tài", driver_phone="0912345678",
        status="LOADING", loading_started_at=opened, loading_started_by=dispatcher, shipment_count=1,
        package_count=1, cod_total=label_cod, created_at=opened)
    add("fulfillment.dispatch_load_shipment", id=uid("load:open:s"), load_id=load_id, shipment_id=ship_id,
        active=True, added_at=opened, added_by=dispatcher, label_cod_amount=label_cod, created_at=opened)
    add("fulfillment.dispatch_load_package", id=uid("load:open:p"), load_id=load_id, package_id=parcels[0],
        shipment_id=ship_id, scan_id=uid("load:open:scan"), loaded_at=opened + timedelta(minutes=5),
        loaded_by=dispatcher, active=True, created_at=opened + timedelta(minutes=5))

# G4-2a: shipments handed over through a dispatch load (DispatchService.handOver → OrderService.recordHandover). The
# order then records the shipment it was told of - ordering.order_shipment and the units its sealed parcels carried
# (package_line, DispatchService.linesOf) - and its outcomes go shipment by shipment (Order.recordShipmentDelivered).
# Only a shipment the order knows as the whole order (whole_order_handover: handed over before loads existed, or
# parcels with no recorded contents) has no such row, and its outcome is the order's own (ShipmentService.tellOrder).
# In this data set loads went live on LOADS_LIVE: every shipment handed over since left on a signed manifest - one
# load per shipment, opened when the carrier's vehicle arrived - and the order recorded it. Earlier ones stay
# whole-order handovers, so the receivables, COD remittances and orders of August-September are untouched.
LOADS_LIVE = at(date(2026, 10, 3), 0)
# The catalogue names V20261021000400 seeds; the order copies the carrier's name with the handover (Carriage).
CARRIER_NAME = {"GHN": "Giao Hàng Nhanh", "GHTK": "Giao Hàng Tiết Kiệm", "VTP": "Viettel Post", "JTE": "J&T Express"}
NO_AUDIT.add("ordering.order_shipment_line")    # (shipment_id, order_line_id, quantity) and nothing else
SHIP_ROW = {r["id"]: r for r in ROWS["fulfillment.shipment"]}
PKG_ROWS = ROWS["fulfillment.package"]
PKG_LINES = ROWS["fulfillment.package_line"]
SIGNED_LOADS = []   # numbered once all exist: LD-<yyyyMMdd>-<n> in the order they were opened that day


def history_of(o):
    return [h for h in ROWS["ordering.order_status_history"] if h["order_id"] == o["id"]]


def status_at(o, t):
    """The order's status at `t`, from its history (what a history row written then records as order_status)."""
    return max((h for h in history_of(o) if h["occurred_at"] <= t), key=lambda h: h["occurred_at"])["to_status"]


def units_of(parcels):
    """What the parcels carry per order line ({@code package_line}), as DispatchService.linesOf sums it."""
    out = {}
    for pl in PKG_LINES:
        if pl["package_id"] in parcels:
            out[pl["order_line_id"]] = out.get(pl["order_line_id"], 0) + pl["quantity"]
    return out


def dispatch(o, s, parcels, opened, by):
    """One parcel load signed for `s` (09 §4.1 step 3): opened LOADING when the driver arrived, the shipment put on the
    manifest and its parcels scanned on, then DISPATCHED when both sides signed - the shipment HANDED_OVER at that
    moment. The triggers only let a manifest take a READY_TO_DISPATCH shipment and an open load take parcels, so the
    rows go in as they were while loading and an UPDATE after the parcels moves them to the signed state."""
    load_id = uid(f"load:{s['id']}")
    signed = s["handed_over_at"]
    carrier = s["carrier_id"]
    k = len(ROWS.get("fulfillment.dispatch_load", []))
    # The carrier's driver signs the manifest for it (carrier_representative); the code is given below, once all exist.
    driver = ["Nguyễn Văn Hưng", "Trần Quốc Việt", "Phạm Minh Đức", "Lê Hoàng Phúc"][k % 4]
    SIGNED_LOADS.append(add(
        "fulfillment.dispatch_load", id=load_id, code=None, request_id=uid(f"load:{s['id']}:request"),
        warehouse_id=s["warehouse_id"], mode="PARCEL", carrier_id=carrier, load_plan_vehicle_id=None,
        vehicle_plate=["29C-318.42", "51D-702.15", "43C-115.08", "29H-660.91"][k % 4], driver_name=driver,
        driver_phone=f"09{k % 10}8{k % 7}2{k % 9}45{k % 5}", status="LOADING", loading_started_at=opened,
        loading_started_by=by, carrier_representative=driver, signature_ref=None, shipment_count=1,
        package_count=len(parcels), cod_total=s["cod_amount"], created_at=opened))
    POST.append(("fulfillment.dispatch_load_package",
                 f"UPDATE fulfillment.dispatch_load SET status = 'DISPATCHED', dispatched_at = {q(signed)}, "
                 f"dispatched_by = {q(by)} WHERE id = {q(load_id)};"))
    add("fulfillment.dispatch_load_shipment", id=uid(f"load:{s['id']}:s"), load_id=load_id, shipment_id=s["id"],
        active=True, added_at=opened, added_by=by, label_cod_amount=s["label_cod_amount"], cod_amount=s["cod_amount"],
        created_at=opened)
    for j, pk in enumerate(parcels):
        add("fulfillment.dispatch_load_package", id=uid(f"load:{s['id']}:p:{j}"), load_id=load_id, package_id=pk,
            shipment_id=s["id"], scan_id=uid(f"load:{s['id']}:scan:{j}"),
            loaded_at=opened + timedelta(minutes=5 + 3 * j), loaded_by=by, active=True,
            created_at=opened + timedelta(minutes=5 + 3 * j))
    final = dict(status=s["status"], handed_over_at=signed, handed_over_by=by, dispatched_at=signed,
                 outcome_at=s["outcome_at"], outcome_by=s["outcome_by"], load_id=load_id)
    s.update(status="READY_TO_DISPATCH", handed_over_at=None, handed_over_by=None, dispatched_at=None,
             outcome_at=None, outcome_by=None, whole_order_handover=False)
    POST.append(("fulfillment.dispatch_load_package", f"UPDATE fulfillment.shipment SET "
                 + ", ".join(f"{c} = {q(v)}" for c, v in final.items()) + f" WHERE id = {q(s['id'])};"))
    return final


def order_shipment(o, s, parcels, final, billed=0):
    """ordering.order_shipment as Order.recordHandover writes it: the units at the order's prices, what the carrier
    collects (only an ON_DELIVERY deposit order's balance - none here), the carriage the portal shows; the outcome
    as recordShipmentDelivered leaves it (a credit order bills the shipment)."""
    units = units_of(parcels)
    price = {ln["id"]: ln["price"] for ln in o["lines"]}
    add("ordering.order_shipment", id=s["id"], order_id=o["id"], status=final["status"],
        amount=sum(qn * price[ln] for ln, qn in units.items()), billed_amount=billed,
        handed_over_at=final["handed_over_at"], handed_over_by=final["handed_over_by"],
        outcome_at=final["outcome_at"], outcome_by=final["outcome_by"], failure_reason=None, failure_note=None,
        cod_amount=0, shipment_code=s["code"], package_count=len(parcels),
        carrier_name=CARRIER_NAME[CARRIER_CODE[s["carrier"]]], delivery_mode=s["delivery_mode"],
        created_at=final["handed_over_at"])
    for ln, qn in units.items():
        add("ordering.order_shipment_line", shipment_id=s["id"], order_line_id=ln, quantity=qn)
    return sum(qn * price[ln] for ln, qn in units.items())


LOADED = [o for o in ORDERS if o.get("shipment") and SHIP_ROW[o["shipment"]]["handed_over_at"] >= LOADS_LIVE]
# 15 BR-08: none of them has its balance collected on delivery (that would bill the carrier per shipment, G2-3).
assert all(o["row"]["balance_policy"] != "ON_DELIVERY" for o in LOADED)
# PACK-PARTIAL (lead's decision 2026-10-21): one credit order of two parcels shipped in two parts. Sales allowed it
# while it was picked; the packer completed the first parcel alone (a partial completion: shipment S1 with its note,
# the order records it packed), the first vehicle took it - PICKING → PARTIALLY_FULFILLED - and the coordinator
# recorded it delivered the next day, which bills it (one receivable per shipment). The second parcel, packed half an
# hour later (PARTIALLY_FULFILLED → PACKED), is the shipment the order already had: it left on its own day (→ SHIPPED).
split = next(o for o in LOADED if o["term"] == "CREDIT" and o["status"] == "SHIPPED" and len(o["lines"]) >= 2
             and SHIP_ROW[o["shipment"]]["package_count"] == 2)
for o in LOADED:
    s = SHIP_ROW[o["shipment"]]
    parcels = [pk["id"] for pk in PKG_ROWS if pk["shipment_id"] == s["id"]]
    dispatcher = s["handed_over_by"]
    if o is split:
        key = o["key"]
        t_pick = o["when"]["PICKING"]
        first, rest = parcels[0], parcels[1:]
        s1 = uid(f"ship:{key}:part")
        ready1, left1 = t_pick + timedelta(hours=3, minutes=15), t_pick + timedelta(hours=3, minutes=50)
        got1 = left1 + timedelta(days=1, hours=2)
        note = "Khách cần gấp phần hàng đã đóng cho ngày khai trương; phần còn lại giao chuyến sau"
        add("ordering.order_partial_shipment_decision", id=uid(f"psd:{key}"), order_id=o["id"], allowed=True,
            order_status="PICKING", note="Khách đồng ý nhận làm 2 đợt, đợt 1 giao ngay phần đã đóng",
            decided_by=U["sales1"], decided_at=t_pick + timedelta(hours=1), created_at=t_pick + timedelta(hours=1))
        o["row"]["allow_partial_shipment"] = True
        # The first completion records the order packed (Order.recordPacked keeps the earliest).
        o["row"]["packed_at"] = ready1
        pk1 = next(pk for pk in PKG_ROWS if pk["id"] == first)
        pk1.update(shipment_id=s1, sealed_at=ready1 - timedelta(minutes=5))
        # The shipment the order had becomes its second: the same row, numbered in the order it was made.
        s.update(code=f"{o['number']}-S2", package_count=len(rest))
        part = add("fulfillment.shipment", id=s1, pack_id=s["pack_id"], order_id=o["id"], carrier=s["carrier"],
                   carrier_id=s["carrier_id"], whole_order_handover=False, tracking_number=None, status="DELIVERED",
                   dispatched_at=left1, code=f"{o['number']}-S1", warehouse_id=s["warehouse_id"],
                   delivery_mode="PARCEL", package_count=1, cod_amount=0, label_cod_amount=0, partial=True,
                   partial_note=note, ready_at=ready1, ready_by=s["ready_by"], handed_over_at=left1,
                   handed_over_by=dispatcher, outcome_at=got1, outcome_by=U["dieuphoi"], created_at=ready1)
        for k, (es, et, who, nt) in enumerate([("READY_TO_DISPATCH", ready1, s["ready_by"], note),
                                               ("HANDED_OVER", left1, dispatcher, None),
                                               ("DELIVERED", got1, U["dieuphoi"], None)]):
            add("fulfillment.shipment_event", id=uid(f"sev:{key}:part:{k}"), shipment_id=s1, status=es, occurred_at=et,
                source="MANUAL", carrier_event_id=None, location_text="Kho " + o["p"], note=nt, raw_payload=None,
                recorded_by=who, created_at=et)
        add("notification.delivery_log", id=uid(f"mail:ship:{key}:part"), template_code="shipment.dispatched",
            channel="EMAIL", recipient=CUST[o["cust"]]["email"], status="SENT", error=None, sent_at=left1,
            external_reference=None, operation_reference=f"shipment:{s1}", terminal=True, delivery_generation=0,
            attempt_number=1, created_at=left1)
        final1 = dispatch(o, part, [first], left1 - timedelta(minutes=30), dispatcher)
        # Credit: billed what it carried - nothing was paid ahead (Order.billNow) - one receivable for the shipment.
        value1 = sum(pl["quantity"] * next(ln["price"] for ln in o["lines"] if ln["id"] == pl["order_line_id"])
                     for pl in PKG_LINES if pl["package_id"] == first)
        order_shipment(o, part, [first], final1, billed=value1)
        o["row"]["billed_amount"] = value1
        due1 = got1.date() + timedelta(days=CUST[o["cust"]]["terms"][2])
        add("payment.receivable", id=uid(f"recv:{key}:part"), order_id=o["id"], customer_id=CUST[o["cust"]]["id"],
            kind="CREDIT_ORDER", amount=value1, paid_amount=0, currency="VND", issued_at=got1, due_date=due1,
            status="OVERDUE" if due1 < TODAY else "OPEN", settled_at=None, shipment_id=s1, order_number=o["number"],
            created_at=got1)
        # The order's history: PICKING → PARTIALLY_FULFILLED (S1 left) → PACKED (the rest packed) → SHIPPED.
        hist = history_of(o)
        packed_h = next(h for h in hist if h["to_status"] == "PACKED")
        assert left1 < packed_h["occurred_at"] < s["handed_over_at"] and got1 <= at(TODAY, 18)
        packed_h["from_status"] = "PARTIALLY_FULFILLED"
        add("ordering.order_status_history", id=uid(f"order:{key}:h:part"), order_id=o["id"], from_status="PICKING",
            to_status="PARTIALLY_FULFILLED", reason=None, actor_id=dispatcher, occurred_at=left1, created_at=left1)
        parcels = rest
    final = dispatch(o, s, parcels, s["handed_over_at"] - timedelta(hours=1), dispatcher)
    order_shipment(o, s, parcels, final)

# The day's next load number (platform.document_sequence), the open load above keeping the one it has.
taken = {r["code"] for r in ROWS["fulfillment.dispatch_load"] if r["code"]}
for r in sorted(SIGNED_LOADS, key=lambda r: r["loading_started_at"]):
    day_ = r["loading_started_at"].strftime("%Y%m%d")
    n = 1 + sum(1 for c in taken if c.startswith(f"LD-{day_}-"))
    r.update(code=f"LD-{day_}-{n:04d}", signature_ref=f"BBGN-LD-{day_}-{n:04d}.pdf")
    taken.add(r["code"])
# Later loads take their number from next_no: it must count every code already given out that day.
for c in taken:
    k = ("LD", datetime.strptime(c[3:11], "%Y%m%d").date())
    SEQ[k] = max(SEQ.get(k, 0), int(c[12:]))
for r in ROWS["fulfillment.dispatch_load"]:
    same_day = [x for x in ROWS["fulfillment.dispatch_load"] if x["code"][:11] == r["code"][:11]]
    assert sorted(same_day, key=lambda x: x["loading_started_at"]) == sorted(same_day, key=lambda x: x["code"])

# LP-1: how an order travels, chosen by the Order Coordinator at release or since, until packing starts
# (OrderReleaseService.release → DeliveryModeHistory.apply, note null; PUT /orders/{id}/delivery-mode, with a note).
# The largest order shipped through a load was released as a full truckload and turned back to parcels before it
# was picked; the largest stock order waiting to be picked was released as one and still is.
FTL_REVERTED = max((o for o in LOADED if o is not split), key=lambda o: o["total"])
FTL_LIVE = max((o for o in ORDERS if o["status"] == "READY_TO_FULFILL" and not o["custom"]
                and o["row"]["balance_policy"] != "ON_DELIVERY"), key=lambda o: o["total"])
for o, steps in ((FTL_REVERTED, [("FULL_TRUCKLOAD", "PARCEL", 0, None),
                                 ("PARCEL", "FULL_TRUCKLOAD", 1, "Chỉ 2 pallet, gửi kiện qua hãng rẻ hơn thuê "
                                                                 "nguyên xe")]),
                 (FTL_LIVE, [("FULL_TRUCKLOAD", "PARCEL", 0, None)])):
    released = o["row"]["released_at"]
    for k, (mode, before, day_after, note) in enumerate(steps):
        # Released with the mode (no note); changed back on the next working morning, before packing started.
        t = released if not day_after else at(released.date() + timedelta(days=day_after), 9, 30)
        status_ = status_at(o, t)
        assert t < o["when"].get("PICKING", at(TODAY, 18)) and status_ in ("IN_PRODUCTION", "READY_TO_FULFILL")
        add("ordering.order_delivery_mode_change", id=uid(f"dmc:{o['key']}:{k}"), order_id=o["id"],
            delivery_mode=mode, previous_mode=before, order_status=status_, note=note, changed_by=U["dieuphoi"],
            changed_at=t, created_at=t)
        o["row"]["delivery_mode"] = mode

# PACK-PARTIAL: Sales allowed a confirmed order to ship in parts, then took it back at the customer's request - the
# history keeps both decisions (PartialShipmentService.decide writes one row per change), the order ships whole.
undecided = next(o for o in ORDERS if o["status"] == "CONFIRMED" and o is not pend and o["term"] == "CREDIT")
for k, (allowed, hours, who, note) in enumerate([
        (True, 2, "sales1", "Khách hỏi giao trước phần có sẵn"),
        (False, 26, "sales2", "Khách muốn nhận một lần cho tiện kiểm đếm")]):
    t = undecided["when"]["CONFIRMED"] + timedelta(hours=hours)
    assert t <= at(TODAY, 18)
    add("ordering.order_partial_shipment_decision", id=uid(f"psd:{undecided['key']}:{k}"), order_id=undecided["id"],
        allowed=allowed, order_status="CONFIRMED", note=note, decided_by=U[who], decided_at=t, created_at=t)
# COD remittances from the carriers
for i, chunk in enumerate([SHIPMENTS_COD[:len(SHIPMENTS_COD) // 2], SHIPMENTS_COD[len(SHIPMENTS_COD) // 2:]]):
    if not chunk:
        continue
    rid = uid(f"cod:{i}")
    first = min(o["when"]["DELIVERED"] for o, *_ in chunk).date()
    last = max(o["when"]["DELIVERED"] for o, *_ in chunk).date()
    expected = sum(a for *_, a in chunk)
    fee = 15000 * len(chunk)
    reconciled = i == 0
    add("payment.cod_remittance", id=rid, remittance_number=f"COD-{last.strftime('%Y%m%d')}-{i + 1:04d}",
        carrier="GHN", statement_ref=f"GHN-DS-{last.strftime('%Y%m%d')}", period_from=first, period_to=last,
        currency="VND", expected_amount=expected, fee_amount=fee,
        received_amount=expected - fee - (0 if reconciled else 50000), status="RECONCILED" if reconciled else "DISPUTED",
        received_at=at(last + timedelta(days=3), 10), reconciled_by=U["ketoan"] if reconciled else None,
        reconciled_at=at(last + timedelta(days=4), 15) if reconciled else None,
        note=None if reconciled else "Thiếu 50.000đ so với bảng kê, đã gửi khiếu nại GHN", created_at=at(last, 18))
    for k, (o, pay_id, amount) in enumerate(chunk):
        short = not reconciled and k == 0
        add("payment.cod_remittance_line", id=uid(f"codl:{i}:{k}"), remittance_id=rid, shipment_id=o["shipment"],
            payment_id=pay_id, cod_amount=amount, remitted_amount=amount - 15000 - (50000 if short else 0),
            fee_amount=15000, match_status="SHORT" if short else "MATCHED", created_at=at(last, 18))

# ============================================================================ 9. inventory operations
ADJ = []


def find_stock(p, sku=None, cls=None, min_free=10, skip=()):
    for s in STOCK:
        if LOC[s.loc]["wh"] == p and LOC[s.loc]["kind"] == "BIN" and s.free >= min_free and s.id not in skip \
                and (sku is None or s.sku == sku) and (cls is None or VAR[s.sku]["cls"] == cls) and s.lot is None:
            return s
    raise LookupError(p)


def adjustment(key, s, delta, reason, status, d, note, requester, decider, count_line=None, rejection=None):
    aid = uid("adj:" + key)
    t = at(d, 10)
    decided = status not in ("PENDING_APPROVAL", "WITHDRAWN")
    add("inventory.stock_adjustment", id=aid, adjustment_number=next_no("ADJ", d), location_code=s.loc, sku=s.sku,
        lot_number=s.lot, quantity_delta=delta, reason_code=reason, note=note, status=status,
        cycle_count_line_id=count_line, requested_by=U[requester], decided_by=U[decider] if decided else None,
        decided_at=t + timedelta(hours=3) if decided else None, rejection_reason=rejection,
        posted_at=t + timedelta(hours=4) if status == "POSTED" else None,
        withdrawn_at=t + timedelta(hours=1) if status == "WITHDRAWN" else None, created_at=t)
    if status == "POSTED":
        s.on_hand += delta
        kind = "COUNT_CORRECTION" if reason == "COUNT_VARIANCE" else ("SCRAP" if reason == "SCRAP" else "ADJUSTMENT")
        move(kind, s.sku, abs(delta), "STOCK_ADJUSTMENT", aid, t + timedelta(hours=4), decider,
             frm=s.loc if delta < 0 else None, to=s.loc if delta > 0 else None, lot=s.lot, reason=note)
    return aid


used = set()
s1 = find_stock("HN", min_free=20); used.add(s1.id)
adjustment("damaged", s1, -3, "DAMAGED", "POSTED", date(2026, 9, 5), "Xe nâng làm rách 3 thùng", "kho.hn1", "quanly.hn")
s2 = find_stock("BD", min_free=20); used.add(s2.id)
adjustment("found", s2, 2, "FOUND", "POSTED", date(2026, 9, 12), "Tìm thấy 2 thùng lẫn ở kệ bên cạnh", "kho.bd1",
           "quanly.bd")
s3 = find_stock("HN", min_free=20, skip=used); used.add(s3.id)
adjustment("lost", s3, -1, "LOST", "PENDING_APPROVAL", date(2026, 10, 11), "Thiếu 1 thùng khi soạn hàng", "kho.hn2",
           "quanly.hn")
s4 = find_stock("BD", min_free=20, skip=used); used.add(s4.id)
adjustment("rejected", s4, 5, "DATA_CORRECTION", "REJECTED", date(2026, 10, 3), "Nhập thiếu khi nhận hàng", "kho.bd2",
           "quanly.bd", rejection="Không có chứng từ nhận hàng bổ sung")
adjustment("withdrawn", s4, -2, "DAMAGED", "WITHDRAWN", date(2026, 10, 4), "Nhầm vị trí, rút lại", "kho.bd2",
           "quanly.bd")
s5 = find_stock("HN", min_free=20, skip=used); used.add(s5.id)
adjustment("sample", s5, -1, "SAMPLE", "POSTED", date(2026, 10, 10), "Xuất 1 thùng làm mẫu cho khách", "sales1",
           "quanly.hn")
s6 = find_stock("BD", min_free=20, skip=used); used.add(s6.id)
adjustment("scrap", s6, -2, "SCRAP", "POSTED", date(2026, 9, 25), "Hủy 2 thùng ẩm mốc", "kho.bd1", "quanly.bd")

# cycle counts
cc_done = uid("cc:hn")
add("inventory.cycle_count", id=cc_done, count_number="CC-20260920-0001", warehouse_id=WH["HN"], trigger_type="SCHEDULED",
    status="COMPLETED", is_blind=True, assigned_user_id=U["kho.hn2"], planned_date=date(2026, 9, 20),
    started_at=at(date(2026, 9, 20), 8), completed_at=at(date(2026, 9, 20), 16), note="Kiểm kê định kỳ tháng 9 khu A",
    created_at=at(date(2026, 9, 18), 9))
hn_a = [s for s in STOCK if s.loc.startswith("HN-A0") and s.status == "AVAILABLE" and s.free > 5][:8]
for k, s in enumerate(hn_a):
    ccl = uid(f"ccl:hn:{k}")
    variance = -2 if k == 3 else 0
    add("inventory.cycle_count_line", id=ccl, cycle_count_id=cc_done, location_code=s.loc, sku=s.sku, lot_number=s.lot,
        expected_qty=s.on_hand, counted_qty=s.on_hand + variance, counted_by=U["kho.hn2"],
        counted_at=at(date(2026, 9, 20), 9 + k // 2), recount_required=False, created_at=at(date(2026, 9, 20), 8))
    if variance:
        adjustment("count", s, variance, "DATA_CORRECTION", "POSTED", date(2026, 9, 20), "Chênh lệch kiểm kê định kỳ",
                   "kho.hn2", "quanly.hn", count_line=ccl)
cc_prog = uid("cc:bd")
add("inventory.cycle_count", id=cc_prog, count_number="CC-20261011-0001", warehouse_id=WH["BD"],
    trigger_type="SHORT_PICK", status="IN_PROGRESS", is_blind=False, assigned_user_id=U["kho.bd2"],
    planned_date=date(2026, 10, 11), started_at=at(date(2026, 10, 11), 14), completed_at=None,
    note="Đếm lại sau khi soạn hàng thiếu", created_at=at(date(2026, 10, 11), 13))
for k, s in enumerate([s for s in STOCK if s.loc.startswith("BD-B01") and s.status == "AVAILABLE"][:6]):
    counted = k < 3
    add("inventory.cycle_count_line", id=uid(f"ccl:bd:{k}"), cycle_count_id=cc_prog, location_code=s.loc, sku=s.sku,
        lot_number=s.lot, expected_qty=s.on_hand, counted_qty=s.on_hand if counted else None,
        counted_by=U["kho.bd2"] if counted else None, counted_at=at(date(2026, 10, 11), 15) if counted else None,
        recount_required=k == 2, created_at=at(date(2026, 10, 11), 14))
add("inventory.cycle_count", id=uid("cc:dn"), count_number="CC-20261015-0001", warehouse_id=WH["DN"],
    trigger_type="MANUAL", status="PLANNED", is_blind=True, assigned_user_id=U["kho.dn1"],
    planned_date=date(2026, 10, 15), started_at=None, completed_at=None, note="Kiểm kê toàn kho Đà Nẵng",
    created_at=at(date(2026, 10, 9), 9))
add("inventory.cycle_count", id=uid("cc:cancel"), count_number="CC-20260928-0001", warehouse_id=WH["BD"],
    trigger_type="MANUAL", status="CANCELLED", is_blind=True, assigned_user_id=U["kho.bd1"],
    planned_date=date(2026, 9, 28), started_at=None, completed_at=None, note="Hủy do trùng lịch nhận hàng",
    created_at=at(date(2026, 9, 25), 9))


# transfers
def transfer(key, frm, to, status, d, items, reason="REBALANCING", damaged=0, carrier=None, delivery_mode="PARCEL"):
    tid = uid("to:" + key)
    t = at(d, 9)
    number = next_no("TO", d)
    lvl = ["DRAFT", "PENDING_APPROVAL", "APPROVED", "PICKING", "IN_TRANSIT", "PARTIALLY_RECEIVED", "RECEIVED",
           "COMPLETED"].index(status) if status != "CANCELLED" else 1
    dispatched = lvl >= 4
    received = lvl >= 6
    add("inventory.transfer_order", id=tid, transfer_number=number, from_warehouse_id=WH[frm], to_warehouse_id=WH[to],
        status=status, reason=reason, expected_date=d + timedelta(days=3), carrier=carrier or "Xe tải nội bộ",
        tracking_number=f"XT-{number[-4:]}" if dispatched else None, shipping_cost=2500000 if dispatched else None,
        submitted_by=U["kehoach"] if lvl >= 1 else None, submitted_at=t + timedelta(hours=1) if lvl >= 1 else None,
        approved_by=U[staff_of(frm)[2]] if lvl >= 2 and status != "CANCELLED" else None,
        approved_at=t + timedelta(hours=3) if lvl >= 2 and status != "CANCELLED" else None,
        dispatched_by=U[staff_of(frm)[0]] if dispatched else None,
        dispatched_at=t + timedelta(days=1) if dispatched else None,
        received_at=t + timedelta(days=3) if received else None,
        closed_at=t + timedelta(days=4) if status == "COMPLETED" else None, close_reason=None,
        cancel_reason="Kho đích đã nhập đủ từ NCC" if status == "CANCELLED" else None,
        # SCRUM-287 PR T: transfers seeded as they were before load planning travel PARCEL (dispatched by hand), as
        # V20261023000400 backfills existing rows; only the one below is a FULL_TRUCKLOAD waiting to be packed.
        delivery_mode=delivery_mode, created_at=t)
    for n, (sku, qty) in enumerate(items, 1):
        lid = uid(f"to:{key}:{n}")
        dmg = damaged if n == 1 else 0
        add("inventory.transfer_order_line", id=lid, transfer_order_id=tid, line_no=n, sku=sku, lot_number=None,
            requested_qty=qty, shipped_qty=qty if dispatched else 0, received_qty=qty - dmg if received else 0,
            damaged_qty=dmg if received else 0, received_by=U[staff_of(to)[0]] if received else None,
            discrepancy_note="2 thùng bị ướt do mưa khi bốc xếp" if received and dmg else None, created_at=t)
        if dispatched:
            src = find_stock(frm, sku=sku, min_free=qty)
            src.on_hand -= qty
            move("TRANSFER_OUT", sku, qty, "TRANSFER_ORDER_LINE", lid, t + timedelta(days=1), staff_of(frm)[0],
                 frm=src.loc, fs="AVAILABLE")
        if received:
            move("TRANSFER_IN", sku, qty - dmg, "TRANSFER_ORDER_LINE", lid, t + timedelta(days=3), staff_of(to)[0],
                 to=f"{to}-RCV01", ts_="INBOUND")
            target = next_bin(to, VAR[sku]["cls"])
            pt = uid(f"pt:to:{lid}")
            done = status == "COMPLETED"
            add("warehouse.putaway_task", id=pt, goods_receipt_id=None, goods_receipt_line_id=None,
                transfer_order_line_id=lid, sku=sku, quantity=qty - dmg, target_location_id=LOC[target]["id"],
                status="DONE" if done else "PENDING", created_at=t + timedelta(days=3))
            if done:
                Stock(sku, target, qty - dmg, t + timedelta(days=3), key=f"to:{lid}")
                move("PUTAWAY", sku, qty - dmg, "PUTAWAY_TASK", pt, t + timedelta(days=3, hours=3), staff_of(to)[1],
                     frm=f"{to}-RCV01", to=target, fs="INBOUND", ts_="AVAILABLE")
            else:
                Stock(sku, f"{to}-RCV01", qty - dmg, t + timedelta(days=3), "INBOUND", key=f"to-in:{lid}")
            if dmg:
                Stock(sku, f"{to}-QRT01", dmg, t + timedelta(days=3), "DAMAGED", key=f"to-dmg:{lid}")
                move("TRANSFER_IN", sku, dmg, "TRANSFER_ORDER_LINE", lid, t + timedelta(days=3), staff_of(to)[0],
                     to=f"{to}-QRT01", ts_="DAMAGED", reason="Hư hỏng khi vận chuyển")


def bd_pick(n, min_free=40):
    out = []
    for s in STOCK:
        if LOC[s.loc]["wh"] == "BD" and LOC[s.loc]["kind"] == "BIN" and s.free >= min_free and s.lot is None \
                and s.sku not in [x for x, _ in out]:
            out.append((s.sku, 10 * (len(out) + 1)))
        if len(out) == n:
            return out
    return out


transfer("done", "BD", "HN", "COMPLETED", date(2026, 9, 8), bd_pick(3))
transfer("received", "BD", "HN", "RECEIVED", date(2026, 10, 6), bd_pick(2)[::-1], damaged=2, reason="DEMAND")
transfer("transit", "BD", "DN", "IN_TRANSIT", date(2026, 10, 10),
         [(s, q) for s, q in bd_pick(6) if s in DN_SKUS][:2] or bd_pick(1), reason="CAMPAIGN", carrier="Viettel Post")
transfer("pending", "HN", "DN", "PENDING_APPROVAL", date(2026, 10, 11), [("LYG-1L-12OZ", 20), ("NAP-GIAY-D90-WHITE", 15)])
transfer("approved", "HN", "BD", "APPROVED", date(2026, 10, 11), [("TUI-KRAFT-L-WHITE", 10)], reason="OVERFLOW")
transfer("draft", "HN", "BD", "DRAFT", date(2026, 10, 12), [("HOP-QUA-S", 5)], reason="OTHER")
transfer("cancelled", "BD", "DN", "CANCELLED", date(2026, 9, 20), [("OHG-WHITE", 30)])

# move tasks other than the QC ones
s_m = find_stock("HN", min_free=30, skip=used); used.add(s_m.id)
dest = next(c for c in BINS["HN"][VAR[s_m.sku]["cls"]] if c != s_m.loc)
mt1 = uid("mt:consolidate")
add("warehouse.move_task", id=mt1, task_number=next_no("MT", date(2026, 9, 15)), warehouse_id=WH["HN"], origin="MANUAL",
    reason="CONSOLIDATION", sku=s_m.sku, lot_number=None, from_location_id=LOC[s_m.loc]["id"],
    to_location_id=LOC[dest]["id"], requested_qty=10, picked_qty=10, placed_qty=10, status="COMPLETED", priority=1,
    assigned_user_id=U["kho.hn1"], approved_by=None, started_at=at(date(2026, 9, 15), 14),
    completed_at=at(date(2026, 9, 15), 15), note="Gom hàng lẻ về một vị trí", receipt_line_id=None,
    created_at=at(date(2026, 9, 15), 13))
s_m.on_hand -= 10
Stock(s_m.sku, dest, 10, s_m.received, key=f"moved:{mt1}")
move("MOVE", s_m.sku, 10, "MOVE_TASK", mt1, at(date(2026, 9, 15), 15), "kho.hn1", frm=s_m.loc, to=dest,
     fs="AVAILABLE", ts_="AVAILABLE")
for k, (st, origin, reason, p) in enumerate([("PENDING", "REPLENISHMENT", "REPLENISHMENT", "BD"),
                                              ("ASSIGNED", "MANUAL", "FIX", "HN"),
                                              ("IN_PROGRESS", "MANUAL", "CONSOLIDATION", "BD"),
                                              ("SUGGESTED", "RESLOTTING", "RESLOTTING", "HN"),
                                              ("ON_HOLD", "MANUAL", "UNBLOCK", "BD"),
                                              ("CANCELLED", "MANUAL", "OTHER", "HN")]):
    s = find_stock(p, min_free=15, skip=used)
    used.add(s.id)
    dest = next(c for c in BINS[p][VAR[s.sku]["cls"]] if c != s.loc)
    d = date(2026, 10, 8) + timedelta(days=k % 4)
    add("warehouse.move_task", id=uid(f"mt:{k}"), task_number=next_no("MT", d), warehouse_id=WH[p], origin=origin,
        reason=reason, sku=s.sku, lot_number=None, from_location_id=LOC[s.loc]["id"], to_location_id=LOC[dest]["id"],
        requested_qty=12, picked_qty=12 if st == "IN_PROGRESS" else None, placed_qty=None, status=st, priority=k % 3,
        assigned_user_id=U[staff_of(p)[0]] if st in ("ASSIGNED", "IN_PROGRESS", "ON_HOLD") else None,
        approved_by=None, started_at=at(d, 10) if st in ("IN_PROGRESS", "ON_HOLD") else None, completed_at=None,
        note={"ON_HOLD": "Chờ mở khóa vị trí đích", "CANCELLED": "Không cần chuyển nữa"}.get(st),
        receipt_line_id=None, created_at=at(d, 9))

# replenishment proposals
rp1 = uid("rp:converted")
add("procurement.replenishment_proposals", id=rp1, code="RP-20261004-0001", warehouse_id=WH["BD"], status="CONVERTED",
    origin="MRP", run_date=date(2026, 10, 4), reviewed_by=U["kehoach"], reviewed_at=at(date(2026, 10, 4), 14),
    converted_by=U["muahang2"], converted_at=at(date(2026, 10, 5), 9), note="Đề xuất từ MRP tuần 40",
    created_at=at(date(2026, 10, 4), 6))
po_conf["row"]["source_proposal_id"] = rp1
for n, ln in enumerate(po_conf["lines"]):
    add("procurement.replenishment_proposal_lines", id=uid(f"rpl:conv:{n}"), proposal_id=rp1,
        inventory_item_id=ITEM[ln["sku"]], on_hand_qty=30 + n * 5, on_order_qty=0, reserved_qty=10, reorder_point=40,
        suggested_qty=ln["qty"], suggested_supplier_id=SUP["GIAYVN"], converted=True, converted_po_id=po_conf["id"],
        converted_po_line_id=ln["id"], created_at=at(date(2026, 10, 4), 6))
for code, st, p, d in (("RP-20261011-0001", "DRAFT_PROPOSAL", "HN", date(2026, 10, 11)),
                       ("RP-20260927-0001", "REJECTED", "HN", date(2026, 9, 27)),
                       ("RP-20261010-0001", "REVIEWED", "BD", date(2026, 10, 10))):
    rp = uid("rp:" + code)
    add("procurement.replenishment_proposals", id=rp, code=code, warehouse_id=WH[p], status=st, origin="MRP" if st !=
        "REVIEWED" else "MANUAL", run_date=d, reviewed_by=U["kehoach"] if st != "DRAFT_PROPOSAL" else None,
        reviewed_at=at(d, 15) if st != "DRAFT_PROPOSAL" else None, converted_by=None, converted_at=None,
        note="Tồn đủ cho 3 tuần, chưa cần nhập" if st == "REJECTED" else None, created_at=at(d, 6))
    n0 = d.day % 7
    for n, v in enumerate([v for v in STOCKED if v["sku"] in DEFAULT_SUP][n0:n0 + 4]):
        add("procurement.replenishment_proposal_lines", id=uid(f"rpl:{code}:{n}"), proposal_id=rp,
            inventory_item_id=ITEM[v["sku"]], on_hand_qty=20 + n * 3, on_order_qty=0, reserved_qty=5,
            reorder_point=40, suggested_qty=100, suggested_supplier_id=SUP[DEFAULT_SUP[v["sku"]]], converted=False,
            converted_po_id=None, converted_po_line_id=None, created_at=at(d, 6))

# a design still in progress, a sample being reworked, a submitted draft
k = "trasuamay:LYN-PET"
d_draft = uid("design:draft:" + k)
add("design.design_draft", id=d_draft, customer_id=CUST["trasuamay"]["id"], product_id=PROD["LYN-PET"]["id"],
    name="Logo Trà sữa Mây - ly PET mùa đông", spec="In 1 màu trắng, logo cỡ 50 mm", status="SUBMITTED",
    owner_user_id=CUST["trasuamay"]["user"], assigned_user_id=U["thietke"], current_artifact_id=uid("art:draft:" + k),
    preflight_passed=False, reviewer_user_id=U["kiemduyet"], last_editor_user_id=U["thietke"], reviewed_by=None,
    review_notes=None, parent_snapshot_id=None, current_artifacts={"CUSTOMER_PREVIEW": uid("art:draft:" + k)}, version=2,
    created_at=at(date(2026, 10, 9), 10))
draft_file = design_file(uid("art:draft:" + k), "image/png", at(date(2026, 10, 9), 11), "logo-may-winter")
add("design.design_artifact", id=uid("art:draft:" + k), draft_id=d_draft,
    storage_key=draft_file["key"], original_name="logo-may-winter.png",
    content_type="image/png", size_bytes=draft_file["size"], stored_at=at(date(2026, 10, 9), 11),
    checksum=draft_file["checksum"], upload_key=None, artifact_role="CUSTOMER_PREVIEW",
    created_at=at(date(2026, 10, 9), 11))
add("design.design_decision", id=uid("dec:draft:" + k), draft_id=d_draft, artifact_id=None, actor_id=U["sales1"],
    decision="ASSIGNMENT_CHANGED", notes=f"Designer={U['thietke']}; reviewer={U['kiemduyet']}", draft_version=0,
    created_at=at(date(2026, 10, 9), 10))
d_rej = uid("design:rejected:hoatuoi")
add("design.design_draft", id=d_rej, customer_id=CUST["hoatuoi"]["id"], product_id=PROD["TUI-KRAFT"]["id"],
    name="Túi hoa tươi - bản 1", spec="In logo + hoa văn full mặt trước", status="REJECTED",
    owner_user_id=CUST["hoatuoi"]["user"], assigned_user_id=U["thietke"], current_artifact_id=None,
    preflight_passed=False, reviewer_user_id=U["kiemduyet"], last_editor_user_id=U["thietke"],
    reviewed_by=U["kiemduyet"], review_notes="File 72 DPI, không đủ độ phân giải in", parent_snapshot_id=None,
    current_artifacts={}, version=3, created_at=at(date(2026, 10, 1), 10))
add("design.design_decision", id=uid("dec:rej:hoatuoi"), draft_id=d_rej, artifact_id=None, actor_id=U["kiemduyet"],
    decision="TECHNICAL_REJECTED", notes="File 72 DPI, không đủ độ phân giải in", draft_version=2,
    created_at=at(date(2026, 10, 2), 10))
dz = design_for("bubbletea", "LYG-2L-12OZ", date(2026, 10, 2))
r1 = uid("sample:rework:1")
add("production.sample_request", id=r1, request_number=next_no("SMP", date(2026, 9, 28)), customer_id=CUST["bubbletea"]["id"],
    quote_id=None, previous_round_id=None, round_no=1, blank_sku="LYG-2L-16OZ", design_snapshot_id=dz["snapshot"],
    quantity=2, due_date=date(2026, 10, 1), status="CHANGES_REQUESTED", sent_at=at(date(2026, 9, 30), 10),
    responded_at=at(date(2026, 10, 1), 15), responded_by=CUST["bubbletea"]["user"] or U["sales2"],
    customer_feedback="Màu đỏ bị ngả cam, đề nghị chỉnh lại", cancellation_reason=None, created_at=at(date(2026, 9, 28), 9))
production_order("sample:rework:1", "SAMPLE", "HN", "LYG-2L-16OZ", dz["snapshot"], dz["checksum"], 2, "COMPLETED",
                 at(date(2026, 9, 28), 10), sample_request=r1)
r2 = uid("sample:rework:2")
add("production.sample_request", id=r2, request_number=next_no("SMP", date(2026, 10, 2)), customer_id=CUST["bubbletea"]["id"],
    quote_id=None, previous_round_id=r1, round_no=2, blank_sku="LYG-2L-16OZ", design_snapshot_id=dz["snapshot"],
    quantity=2, due_date=date(2026, 10, 14), status="SENT_TO_CUSTOMER", sent_at=at(date(2026, 10, 10), 10),
    responded_at=None, responded_by=None, customer_feedback=None, cancellation_reason=None,
    created_at=at(date(2026, 10, 2), 9))
production_order("sample:rework:2", "SAMPLE", "HN", "LYG-2L-16OZ", dz["snapshot"], dz["checksum"], 2, "COMPLETED",
                 at(date(2026, 10, 3), 10), sample_request=r2)
add("production.sample_request", id=uid("sample:submitted"), request_number=next_no("SMP", date(2026, 10, 11)),
    customer_id=CUST["bubbletea"]["id"], quote_id=None, previous_round_id=None, round_no=1, blank_sku="TAYCAM-16OZ",
    design_snapshot_id=dz["snapshot"], quantity=1, due_date=date(2026, 10, 18), status="SUBMITTED", sent_at=None,
    responded_at=None, responded_by=None, customer_feedback=None, cancellation_reason=None,
    created_at=at(date(2026, 10, 11), 9))
add("production.sample_request", id=uid("sample:cancelled"), request_number=next_no("SMP", date(2026, 9, 5)),
    customer_id=CUST["bubbletea"]["id"], quote_id=None, previous_round_id=None, round_no=1, blank_sku="LYG-1L-16OZ",
    design_snapshot_id=dz["snapshot"], quantity=1, due_date=date(2026, 9, 10), status="CANCELLED", sent_at=None,
    responded_at=None, responded_by=None, customer_feedback=None, cancellation_reason="Khách chuyển sang ly 2 lớp",
    created_at=at(date(2026, 9, 5), 9))

# the subcontract PO of the subcontracted production order
sub_prod = next((x for x in PRODUCTION if x["row"]["status"] == "SUBCONTRACTED"), None)
if sub_prod:
    pr = sub_prod["row"]
    d = pr["created_at"].date()
    spo = make_po("subcontract", "HN" if pr["warehouse_id"] == WH["HN"] else "BD", "INSG", d, "CONFIRMED",
                  [(pr["blank_sku"], pr["planned_quantity"], 120000)], submitter="muahang1", approver="quanly.hn",
                  po_type="SUBCONTRACT", production_order_id=pr["id"], number=next_no("SPO", d),
                  note=f"Gia công in {pr['order_number']}", times=raised_with(converted_at(sub_prod)))
    POST.append(("procurement.purchase_order_attachments",
                 f"UPDATE production.production_order SET purchase_order_id = {q(spo['id'])} WHERE id = {q(pr['id'])};"))

# ============================================================================ 10. engagement
CONV = [("caphemoc", "ASSIGNED", "Hỏi lịch giao đơn tháng 10", ["Cho mình hỏi đơn ly 12oz bao giờ giao?",
                                                               "Dạ đơn đang soạn hàng, dự kiến giao thứ Năm ạ.",
                                                               "Ok cảm ơn shop."]),
        ("trasuamay", "OPEN", "Báo giá ly PET in 1 màu", ["Mình cần báo giá 50 thùng ly PET 16oz in logo 1 màu.",
                                                          "Dạ em gửi báo giá trong hôm nay ạ."]),
        ("bepnhalam", "CLOSED", "Hóa đơn VAT", ["Shop xuất giúp hóa đơn VAT đơn tuần trước.",
                                                "Dạ đã gửi hóa đơn điện tử qua email ạ.", "Đã nhận, cảm ơn."]),
        ("hoatuoi", "OPEN", "File thiết kế bị từ chối", ["File của mình bị từ chối vì sao vậy?",
                                                          "Dạ file 72 DPI, chị gửi lại file vector giúp em ạ."]),
        ("pizza4u", "CLOSED", "Đổi địa chỉ giao", ["Đổi giúp địa chỉ giao sang chi nhánh 2.", "Đã cập nhật ạ."]),
        ("banhmisaigon", "OPEN", "Hỏi chính sách công nợ", ["Bên mình muốn mua công nợ 30 ngày được không?"])]
for i, (cc, st, subj, msgs) in enumerate(CONV):
    cv = uid(f"conv:{i}")
    o = next((o for o in ORDERS if o["cust"] == cc and o["status"] not in ("DRAFT",)), None)
    t0 = at(TODAY - timedelta(days=10 - i), 9)
    add("chat.conversation", id=cv, customer_id=CUST[cc]["id"], order_id=o["id"] if o and i % 2 == 0 else None,
        subject=subj, status=st, assigned_agent_id=U["sales1" if i % 2 else "sales2"] if st != "OPEN" else None,
        created_at=t0)
    for k, body in enumerate(msgs):
        agent = k % 2 == 1
        add("chat.message", id=uid(f"msg:{i}:{k}"), conversation_id=cv, sender_type="AGENT" if agent else "CUSTOMER",
            sender_id=U["sales1" if i % 2 else "sales2"] if agent else CUST[cc]["user"], body=body,
            sent_at=t0 + timedelta(minutes=12 * k), created_at=t0 + timedelta(minutes=12 * k))
    if st == "CLOSED":
        add("chat.message", id=uid(f"msg:{i}:end"), conversation_id=cv, sender_type="SYSTEM", sender_id=None,
            body="Cuộc trò chuyện đã được đóng.", sent_at=t0 + timedelta(hours=2), created_at=t0 + timedelta(hours=2))

for code, ch, subject, body in [
    ("order.placed", "EMAIL", "Xác nhận đơn hàng {{orderNumber}}",
     "Chào {{customerName}}, cảm ơn bạn đã đặt hàng. Đơn {{orderNumber}} trị giá {{total}} đã được ghi nhận."),
    ("payment.failed", "EMAIL", "Thanh toán đơn {{orderNumber}} không thành công",
     "Thanh toán cho đơn {{orderNumber}} chưa thành công. Vui lòng thử lại hoặc liên hệ CSKH."),
    # 09 §4.1 step 3: the customer is told which carrier has the goods - no tracking number exists (09 §1).
    ("shipment.dispatched", "EMAIL", "Đơn {{orderNumber}} đã được giao cho đơn vị vận chuyển",
     "Lô {{shipmentCode}} của đơn {{orderNumber}} ({{packageCount}} kiện) đã được bàn giao cho {{carrierName}}."),
    ("shipment.dispatched", "SMS", None, "SF Pack: don {{orderNumber}} da giao cho {{carrierName}}."),
    ("shipment.delivered", "EMAIL", "Đơn {{orderNumber}} đã giao thành công",
     "Lô {{shipmentCode}} của đơn {{orderNumber}} đã được giao thành công."),
    ("po.mail", "EMAIL", "Đơn đặt hàng {{poNumber}}", "Kính gửi {{supplierName}}, đính kèm đơn đặt hàng {{poNumber}}."),
    ("design.sent", "EMAIL", "Mời duyệt thiết kế {{designName}}", "Thiết kế {{designName}} đã sẵn sàng để bạn duyệt."),
    ("receivable.overdue", "EMAIL", "Nhắc thanh toán công nợ", "Công nợ đơn {{orderNumber}} đã quá hạn {{days}} ngày."),
]:
    for loc in (("vi", "en") if ch == "EMAIL" and code == "order.placed" else ("vi",)):
        add("notification.template", id=uid(f"tpl:{code}:{ch}:{loc}"), code=code if ch == "EMAIL" else code + ".sms",
            channel=ch, subject=subject if loc == "vi" or not subject else "Order {{orderNumber}} confirmed",
            body=body if loc == "vi" else "Hi {{customerName}}, thank you. Order {{orderNumber}} ({{total}}) is placed.",
            locale=loc, active=True, created_at=at(date(2026, 8, 1), 8))

for i, (action, rtype, actor, res, outcome) in enumerate([
    ("LOGIN", "SESSION", "quanly.hn", None, "SUCCESS"), ("LOGIN", "SESSION", "nv.bikhoa", None, "FAILURE"),
    ("CREATE", "PURCHASE_ORDER", "muahang1", PO["s:draft"]["id"], "SUCCESS"),
    ("APPROVE", "PURCHASE_ORDER", "quanly.hn", PO["s:approved"]["id"], "SUCCESS"),
    ("REJECT", "PURCHASE_ORDER", "quanly.hn", PO["s:resubmit"]["id"], "SUCCESS"),
    ("UPDATE", "PRODUCT", "ecom", PROD["LYG-1L"]["id"], "SUCCESS"),
    ("APPROVE", "PRODUCT", "ecom2", PROD["HOP-TRUNGTHU"]["id"], "SUCCESS"), ("TRANSITION", "ORDER", "dieuphoi", ORDERS[-1]["id"], "SUCCESS"),
    ("EXPORT", "REPORT", "ketoan", None, "SUCCESS"), ("LOGOUT", "SESSION", "sales1", None, "SUCCESS"),
    ("APPROVE", "STOCK_ADJUSTMENT", "quanly.hn", uid("adj:damaged"), "SUCCESS"),
    ("DELETE", "CART_LINE", "kh.caphemoc", None, "SUCCESS"), ("REVOKE", "SESSION", "quanly.bd", None, "SUCCESS"),
]):
    t = at(TODAY - timedelta(days=14 - i), 8 + i % 9, 5 * i % 60)
    add("platform.audit_log", id=uid(f"audit:{i}"), actor_id=U[actor], actor_name=actor, action=action,
        resource_type=rtype, resource_id=res, outcome=outcome, correlation_id=hashlib.md5(str(i).encode()).hexdigest()[:16],
        client_address=f"10.0.0.{10 + i}", details=None if outcome == "SUCCESS" else "Bad credentials",
        occurred_at=t, security_relevant=action in ("LOGIN", "LOGOUT", "GRANT", "REVOKE"))

# carts
cart_custs = [k for k in ACTIVE if CUST[k]["user"]][:6]
for i, cc in enumerate(cart_custs):
    cid = uid(f"cart:{cc}")
    add("ordering.cart", id=cid, customer_id=CUST[cc]["id"], guest_token=None, status="ACTIVE", currency="VND",
        converted_order_id=None, merged_into_cart_id=None, last_activity_at=at(TODAY - timedelta(days=i), 20),
        expires_at=at(TODAY + timedelta(days=30), 20), created_at=at(TODAY - timedelta(days=i + 2), 19))
    for k, v in enumerate(SELLABLE[i * 3:i * 3 + 1 + i % 3]):
        add("ordering.cart_line", id=uid(f"cartl:{cc}:{k}"), cart_id=cid, sku=v["sku"], quantity=5 + 5 * k,
            design_snapshot_id=None, unit_price_snapshot=unit_price(CUST[cc], v["sku"]),
            added_at=at(TODAY - timedelta(days=i), 20), created_at=at(TODAY - timedelta(days=i), 20))
add("ordering.cart", id=uid("cart:guest"), customer_id=None, guest_token="g-7f3a9c2e5b1d4e6f8a0b", status="ACTIVE",
    currency="VND", converted_order_id=None, merged_into_cart_id=None, last_activity_at=at(TODAY, 11),
    expires_at=at(TODAY + timedelta(days=7), 11), created_at=at(TODAY, 10))
add("ordering.cart_line", id=uid("cartl:guest"), cart_id=uid("cart:guest"), sku="OHG-PINK", quantity=10,
    design_snapshot_id=None, unit_price_snapshot=VAR["OHG-PINK"]["price"], added_at=at(TODAY, 10), created_at=at(TODAY, 10))
add("ordering.cart", id=uid("cart:merged"), customer_id=None, guest_token="g-merged-0001", status="MERGED",
    currency="VND", converted_order_id=None, merged_into_cart_id=uid(f"cart:{cart_custs[0]}"),
    last_activity_at=at(TODAY - timedelta(days=1), 9), expires_at=None, created_at=at(TODAY - timedelta(days=1), 8))
add("ordering.cart", id=uid("cart:abandoned"), customer_id=CUST["kemtuoi"]["id"], guest_token=None, status="ABANDONED",
    currency="VND", converted_order_id=None, merged_into_cart_id=None, last_activity_at=at(date(2026, 9, 1), 21),
    expires_at=at(date(2026, 10, 1), 21), created_at=at(date(2026, 9, 1), 20))
add("ordering.cart_line", id=uid("cartl:abandoned"), cart_id=uid("cart:abandoned"), sku="KHAY-LY-2CUP", quantity=5,
    design_snapshot_id=None, unit_price_snapshot=VAR["KHAY-LY-2CUP"]["price"], added_at=at(date(2026, 9, 1), 20),
    created_at=at(date(2026, 9, 1), 20))
conv_o = next(o for o in ORDERS if o["status"] == "COMPLETED" and CUST[o["cust"]]["user"] and o["cust"] not in cart_custs)
add("ordering.cart", id=uid("cart:converted"), customer_id=CUST[conv_o["cust"]]["id"], guest_token=None,
    status="CONVERTED", currency="VND", converted_order_id=conv_o["id"], merged_into_cart_id=None,
    last_activity_at=conv_o["row"]["placed_at"], expires_at=None, created_at=conv_o["row"]["placed_at"] - timedelta(hours=1))

# SCRUM-287 PR T (LP2-8): a full-truckload transfer waiting to be packed, its goods in two unopened cartons of 20 at
# HN - repacked from loose stock by a completed move task - so the "ship the carton as it is" flow can be shown.
s_c = find_stock("HN", min_free=40, skip=used); used.add(s_c.id)
carton_bin = next(c for c in BINS["HN"][VAR[s_c.sku]["cls"]] if c != s_c.loc)
carton_unit = uid("uom:" + s_c.sku + ":CTN20")
POST.append(("procurement.supplier_items",
             "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, "
             "weight_kg, length_cm, width_cm, height_cm, default_purchase, active, default_shipping, created_by) "
             f"SELECT {q(carton_unit)}, i.id, 'CTN20', 20, FALSE, 12.5, 60, 40, 40, FALSE, TRUE, FALSE, {q(BY)} "
             f"FROM inventory.inventory_items i WHERE i.sku = {q(s_c.sku)};"))
mt_carton = uid("mt:carton")
add("warehouse.move_task", id=mt_carton, task_number=next_no("MT", date(2026, 10, 9)), warehouse_id=WH["HN"],
    origin="MANUAL", reason="CONSOLIDATION", sku=s_c.sku, lot_number=None, from_location_id=LOC[s_c.loc]["id"],
    to_location_id=LOC[carton_bin]["id"], requested_qty=40, picked_qty=40, placed_qty=40, status="COMPLETED",
    priority=1, assigned_user_id=U["kho.hn1"], approved_by=None, started_at=at(date(2026, 10, 9), 9),
    completed_at=at(date(2026, 10, 9), 10), note="Đóng 40 đơn vị vào 2 thùng 20 (LPN) cho lệnh chuyển kho",
    receipt_line_id=None, created_at=at(date(2026, 10, 9), 8))
s_c.on_hand -= 40
for k in (1, 2):
    lpn = f"LPN-SEED-CTN-{k:04d}"
    carton = uid(f"spkg:{lpn}")
    POST.append(("procurement.supplier_items",
                 "INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, "
                 "received_qty, location_code, status, unopened, goods_receipt_line_id, created_at, created_by) "
                 f"SELECT {q(carton)}, {q(lpn)}, i.id, {q(carton_unit)}, 20, 20, {q(carton_bin)}, 'IN_STOCK', TRUE, "
                 f"NULL, {q(at(date(2026, 10, 9), 10))}, {q(BY)} FROM inventory.inventory_items i "
                 f"WHERE i.sku = {q(s_c.sku)};"))
    row = Stock(s_c.sku, carton_bin, 20, s_c.received + timedelta(seconds=k), key=f"carton:{lpn}")
    row.pkg = carton
    move("MOVE", s_c.sku, 20, "MOVE_TASK", mt_carton, at(date(2026, 10, 9), 10), "kho.hn1", frm=s_c.loc,
         to=carton_bin, fs="AVAILABLE", ts_="AVAILABLE")
transfer("ftl", "HN", "BD", "APPROVED", date(2026, 10, 12), [(s_c.sku, 40)], reason="DEMAND",
         delivery_mode="FULL_TRUCKLOAD")

# ============================================================================ 10b. subcontracted printing
# What happened to the two subcontracted print jobs after their blanks went out (SubcontractJob, place_order). Written
# last: none of it changes stock anything generated before reads, and it draws no random number.
#   * The IN_PRODUCTION order's line, at Xưởng in Sài Gòn (SUBCONTRACTED; its PO is section 9's "subcontract"): two
#     blanks brought back by a recall - one still good, back on its bin; one dented, its SUBCONTRACT_LOSS write-off
#     waiting for the warehouse manager. Nothing received yet, so nothing settled.
#   * The `sub` order's line, done by In Hà Nội (COMPLETED): delivered in two goods receipts - the subcontractor spoilt a
#     blank, so the first QC round left the job one unit short and one more blank went out - each receipt reconciled,
#     the goods held for the job in QC, handed to packing and consumed there (G4-Q2); settled, the spoilt blank above
#     the 3 % tolerance approved with the deduction agreed with the subcontractor (BR-PRD-17).
for job in JOBS.values():
    if job.row["status"] == "SUBCONTRACTED":
        job.recall("dented", 1, 1, at(date(2026, 10, 9), 10),
                   "Xưởng in gửi trả 2 thùng phôi bị móp khi vận chuyển: 1 thùng còn dùng được nhập lại kho, "
                   "1 thùng hỏng đề nghị hủy")
        continue
    t, planned, key = job.prod["times"], job.row["planned_quantity"], job.prod["key"]
    first = t["QC"]
    assert job.row["status"] == "COMPLETED" and first + timedelta(hours=4, minutes=30) < t["COMPLETED"], job.number
    # The SUBCONTRACT order the conversion raised (SubcontractPurchasing.create: one line, the blank SKU, the job's
    # quantity and price), linked from the production order once both exist, as section 9 links its own.
    po = make_po(f"subcontract:{key}", job.p, job.sup, job.converted.date(), "CONFIRMED",
                 [(job.sku, planned, int(job.row["subcontract_unit_price"]))], submitter="muahang1",
                 approver=job.manager, po_type="SUBCONTRACT", production_order_id=job.id,
                 number=next_no("SPO", job.converted.date()), note=f"Gia công in {job.number}",
                 times=raised_with(job.converted))
    POST.append(("procurement.purchase_order_attachments",
                 f"UPDATE production.production_order SET purchase_order_id = {q(po['id'])} WHERE id = {q(job.id)};"))
    # 1st delivery, one unit short: the subcontractor spoilt a blank - reconciled as written off, approved at once.
    r1 = job.receive(po, planned - 1, first)
    job.steps["QC"].update(changed_by=None, reason=f"Received {planned - 1} from the subcontractor")
    job.reconcile(r1, 1, 0, first + timedelta(minutes=20), decided=first + timedelta(minutes=50))
    # QC round 1 passes all of it and leaves the job one unit short: READY. A subcontractor's shortfall is not reprinted
    # in house (PrintRunService.reprint) but sent again - sendBlanks holds the blank still missing (planned - good - at
    # the subcontractor - unsent) under "subcontract-blanks:<id>:<issues so far>"; the PO has the unit open still.
    job.rounds.append((planned - 1, 0))
    add("production.production_qc_result", id=uid(f"prod:{key}:qc:sub:1"), production_order_id=job.id, round_no=1,
        passed_quantity=planned - 1, failed_quantity=0, note="Đạt", inspected_at=first + timedelta(hours=1),
        inspected_by=U[job.inspector])
    job.history("qc1", "QC", "READY", first + timedelta(hours=1), job.inspector,
                f"QC passed {planned - 1}, failed 0; 1 more good unit(s) needed")
    resend = first + timedelta(hours=1, minutes=30)
    spare = next(s for s in STOCK if s.sku == job.sku and LOC[s.loc]["wh"] == job.p and LOC[s.loc]["kind"] == "BIN"
                 and s.status == "AVAILABLE" and s.free >= 1 and s.received <= resend and s.lot is None)
    job.hold(spare, 1, resend, f"subcontract-blanks:{job.id}:{sum(1 for b in job.blanks if b['sent'])}")
    job.history("resend", "READY", "SUBCONTRACTED", resend, job.clerk, "Sent to the subcontractor")
    job.send(resend)
    # 2nd delivery: the last unit, nothing spoilt.
    r2 = job.receive(po, 1, first + timedelta(hours=4))
    job.history("receipt2", "SUBCONTRACTED", "QC", first + timedelta(hours=4), None, "Received 1 from the subcontractor")
    job.reconcile(r2, 0, 0, first + timedelta(hours=4, minutes=20))
    # QC round 2 completes the job: production_order's QC result and COMPLETED step are this round's.
    job.rounds.append((1, 0))
    next(r for r in ROWS["production.production_qc_result"] if r["id"] == uid(f"prod:{key}:qc")).update(
        round_no=2, passed_quantity=1, failed_quantity=0)
    job.steps["COMPLETED"].update(reason=f"QC passed 1, failed 0; {planned} good")
    job.complete(t["COMPLETED"])
    job.settle(po, t["COMPLETED"], approved=(t["COMPLETED"] + timedelta(hours=17), 4000,
                                             "Đã thống nhất với In Hà Nội: trừ 4.000đ/phôi hỏng vượt định mức hao hụt"))

# ============================================================================ 10b. quotes
# B2B quotes (SCRUM-448), written as QuoteService leaves them: one history row per transition, the SENT row carrying
# the offer as sent (QuoteService.offerJson), lines replaced on a revision, the accepted quantity and price fixed on
# each line at acceptance. Only designs the customer confirmed are quoted (DesignService.verifySnapshotForSku: a
# snapshot of the customer's own draft, for the product the blank belongs to), and a quote is accepted only on blanks
# with a sample the customer approved (BR-ORD-008, QuoteService.gate). No random draws: the rest of the set stays put.
NO_AUDIT.update({"ordering.quote_line", "ordering.quote_price_tier", "ordering.quote_status_history"})
QUOTE_TERMS = ("Đơn giá theo thùng, đã gồm VAT 8% và phí in. Sản xuất 7-10 ngày sau khi khách duyệt mẫu; "
               "giao tận kho của khách trong nội thành.")


def conversion_request_id(quote_id):
    """QuoteService.conversionRequestId: UUID.nameUUIDFromBytes("quote-conversion:" + quoteId), a version-3 UUID,
    so the order a conversion placed is found again by its request id."""
    h = bytearray(hashlib.md5(f"quote-conversion:{quote_id}".encode()).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    s = h.hex()
    return f"{s[:8]}-{s[8:12]}-{s[12:16]}-{s[16:20]}-{s[20:]}"


def iso_instant(t):
    """Instant.toString, as Jackson writes the validity into the SENT offer."""
    return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def tier_price(tiers, units):
    """QuoteLine.priceFor: the break with the highest start not above `units`; never below the MOQ."""
    assert units >= tiers[0][0], (units, tiers)
    return [p for m, p in tiers if m <= units][-1]


def quote(key, cust_code, sales, term, drafted, valid_until, lines, events=(), draft_reason=None, drafted_by=None):
    """One quote taken through `events` the way QuoteService moves it. lines: (design key, blank SKU, quantity,
    [(min quantity, unit price)], print spec). events: ("send", t) | ("changes", t, feedback) | ("reopen", t) |
    ("revise", t, lines) | ("accept", t, {line no: units}) | ("convert", t, order) | ("expire", t) | ("cancel", t, why).
    """
    c = CUST[cust_code]
    assert c["status"] == "ACTIVE", cust_code        # BR-ORD-007: quotes go to ACTIVE customers only
    assert term == "PREPAID" or (c["terms"] and (term == "CREDIT" and c["profile"]["allow_credit"]
                                                 or term == "DEPOSIT" and c["profile"]["allow_deposit"])), (key, term)
    qid = uid("quote:" + key)
    deposit = c["profile"]["deposit_percent"] if term == "DEPOSIT" else None   # the customer's default, copied in
    assert valid_until > drafted
    state = dict(status="DRAFT", revision=1, sent_at=None, sent_by=None, responded_at=None, responded_by=None,
                 customer_feedback=None, cancellation_reason=None, converted_order_id=None, converted_at=None)
    history = [(1, None, "DRAFT", draft_reason, None, drafted, drafted_by or U[sales])]

    def build(spec, rev):
        out, units = [], {}
        for _, sku, qty, _, _ in spec:
            units[sku] = units.get(sku, 0) + qty
        for n, (dkey, sku, qty, tiers, print_spec) in enumerate(spec, 1):
            des = DESIGNS[dkey]
            assert des["cust"] == cust_code and dkey.split(":")[1] == VAR[sku]["product"], (key, dkey, sku)
            assert VAR[sku]["vstatus"] == "ACTIVE" and VAR[sku]["pstatus"] == "PUBLISHED", sku
            assert all(p > 0 and p == int(p) for _, p in tiers) and [m for m, _ in tiers] == sorted({m for m, _ in tiers})
            assert not tiers or units[sku] >= tiers[0][0], (key, sku)          # Quote.Offer: the blank's total vs MOQ
            out.append(dict(id=uid(f"quote:{key}:r{rev}:line:{n}"), line_no=n, sku=sku, snapshot=des["snapshot"],
                            qty=qty, tiers=tiers, spec=print_spec, acc_qty=None, acc_price=None))
        return out

    cur = build(lines, 1)
    edits, last = 0, drafted

    def offer_json():
        return {"revision": state["revision"], "paymentTerm": term, "depositPercent": deposit,
                "validUntil": iso_instant(valid_until), "terms": QUOTE_TERMS,
                "lines": [{"lineNo": ln["line_no"], "blankSku": ln["sku"], "designSnapshotId": ln["snapshot"],
                           "printSpec": ln["spec"], "quantity": ln["qty"],
                           "tiers": [{"minQuantity": m, "unitPrice": p} for m, p in ln["tiers"]]} for ln in cur]}

    for ev in events:
        kind, t = ev[0], ev[1]
        assert t > last, (key, kind)
        last = t
        frm = state["status"]
        reason, offer, by = None, None, U[sales]
        if kind == "revise":                      # Quote.revise: a draft only; an edit replaces the lines (new ids)
            assert frm == "DRAFT"
            cur = build(ev[2], state["revision"])
            edits += 1
            continue
        if kind == "send":                        # DRAFT -> SENT, every line priced, validity still ahead
            assert frm == "DRAFT" and all(ln["tiers"] for ln in cur) and valid_until > t
            to, offer = "SENT", offer_json()
            state.update(sent_at=t, sent_by=U[sales])
        elif kind == "changes":                   # SENT -> CHANGES_REQUESTED, by the customer within the validity
            assert frm == "SENT" and t < valid_until and c["user"]
            to, reason, by = "CHANGES_REQUESTED", ev[2], c["user"]
            state.update(responded_at=t, responded_by=by, customer_feedback=ev[2])
        elif kind == "reopen":                    # CHANGES_REQUESTED -> DRAFT, the next revision, answer cleared
            assert frm == "CHANGES_REQUESTED"
            to = "DRAFT"
            state.update(revision=state["revision"] + 1, responded_at=None, responded_by=None, customer_feedback=None)
        elif kind == "accept":                    # SENT -> ACCEPTED: each line at its blank's accepted total (P5)
            assert frm == "SENT" and t < valid_until and c["user"]
            asked = ev[2] if len(ev) > 2 else {}
            chosen = {ln["id"]: asked.get(ln["line_no"], ln["qty"]) for ln in cur}
            blank = {}
            for ln in cur:
                blank[ln["sku"]] = blank.get(ln["sku"], 0) + chosen[ln["id"]]
            for ln in cur:
                # BR-ORD-008: a sample this customer approved for that design on that blank, before the answer.
                assert any(a["customer_id"] == c["id"] and a["design_snapshot_id"] == ln["snapshot"]
                           and a["blank_sku"] == ln["sku"] and a["approved_at"] < t
                           for a in ROWS["ordering.approved_sample"]), (key, ln["sku"])
                ln["acc_qty"], ln["acc_price"] = chosen[ln["id"]], tier_price(ln["tiers"], blank[ln["sku"]])
            to, by = "ACCEPTED", c["user"]
            state.update(responded_at=t, responded_by=by)
        elif kind == "convert":                   # ACCEPTED -> CONVERTED: the one order, at the accepted terms
            o = ev[2]
            assert frm == "ACCEPTED" and o["cust"] == cust_code and o["term"] == term and t == o["row"]["placed_at"]
            assert [(ln["sku"], ln["acc_qty"], ln["acc_price"], ln["snapshot"]) for ln in cur] \
                == [(ol["sku"], ol["qty"], ol["price"], ol["design"]["snapshot"] if ol["design"] else None)
                    for ol in o["lines"]], key
            # The order was placed under the request id derived from the quote, as QuotedOrders is called.
            o["row"]["request_id"] = conversion_request_id(qid)
            to, reason = "CONVERTED", o["number"]
            state.update(converted_order_id=o["id"], converted_at=t)
        elif kind == "expire":                    # SENT -> EXPIRED by QuoteExpirySweeper, after the validity, no actor
            assert frm == "SENT" and t >= valid_until
            to, by = "EXPIRED", None
        elif kind == "cancel":                    # DRAFT -> CANCELLED (or ACCEPTED, only for a stopped SKU)
            assert frm == "DRAFT"
            to, reason = "CANCELLED", ev[2]
            state.update(cancellation_reason=ev[2])
        else:
            raise ValueError(kind)
        state["status"] = to
        history.append((state["revision"], frm, to, reason, offer, t, by))

    row = add("ordering.quote", id=qid, quote_number=next_no("QT", drafted.date()), request_id=uid(f"quote:{key}:request"),
              customer_id=c["id"], status=state["status"], revision=state["revision"], payment_term=term,
              deposit_percent=deposit, valid_until=valid_until, terms=QUOTE_TERMS, sent_at=state["sent_at"],
              sent_by=state["sent_by"], responded_at=state["responded_at"], responded_by=state["responded_by"],
              customer_feedback=state["customer_feedback"], cancellation_reason=state["cancellation_reason"],
              converted_order_id=state["converted_order_id"], converted_at=state["converted_at"],
              # every save after the insert moves the @Version: each transition and each draft edit
              version=len(history) - 1 + edits, created_at=drafted,
              last_modified_at=last if last > drafted else None, last_modified_by=BY if last > drafted else None)
    for ln in cur:
        add("ordering.quote_line", id=ln["id"], quote_id=qid, line_no=ln["line_no"], blank_sku=ln["sku"],
            design_snapshot_id=ln["snapshot"], print_spec=ln["spec"], quantity=ln["qty"],
            accepted_quantity=ln["acc_qty"], accepted_unit_price=ln["acc_price"])
        for m, p in ln["tiers"]:
            add("ordering.quote_price_tier", quote_line_id=ln["id"], min_quantity=m, unit_price=p)
    for k, (rev, frm, to, reason, offer, t, by) in enumerate(history):
        add("ordering.quote_status_history", id=uid(f"quote:{key}:h:{k}"), quote_id=qid, revision=rev,
            from_status=frm, to_status=to, reason=reason, offer=offer, changed_at=t, changed_by=by)
    QUOTES.append(dict(id=qid, key=key, row=row, lines=cur, status=state["status"]))
    return QUOTES[-1]


def link_sample(design_key, q_):
    """A design's first sample round was requested for the quote under negotiation (SampleRequestService:
    an open quote of that customer that quotes this design on this blank), so it names the quote."""
    sample = next(r for r in ROWS["production.sample_request"] if r["id"] == uid("sample:" + design_key))
    hist = ROWS["ordering.quote_status_history"]
    opened = [h for h in hist if h["quote_id"] == q_["id"] and h["changed_at"] <= sample["created_at"]]
    assert opened and opened[-1]["to_status"] in ("DRAFT", "SENT", "CHANGES_REQUESTED"), design_key
    assert any((ln["snapshot"], ln["sku"]) == (sample["design_snapshot_id"], sample["blank_sku"]) for ln in q_["lines"])
    assert sample["quote_id"] is None and sample["customer_id"] == q_["row"]["customer_id"]
    sample["quote_id"] = q_["id"]


def tiers3(price, lo, mid, hi):
    """Three breaks around the price agreed at `mid` units: 4% more below it, 4% less from `hi` up."""
    return [(lo, money(price * 1.04 / 1000) * 1000), (mid, price), (hi, money(price * 0.96 / 1000) * 1000)]


QUOTES = []
ORDER_BY_KEY = {o["key"]: o for o in ORDERS}

# Converted: the single-line print orders whose customer can answer on the portal. A design line is placed only at a
# quote's price (OrderServiceImpl.place, SCRUM-298), so these orders are what their accepted quotes became; each
# design's first sample round was asked for under the quote (design_for: sample a day after the snapshot, approved
# three days before the order), and the customer accepted the day after that.
for qkey, okey, dkey, sales, spec, lo, hi, rounds in [
        ("conv:tiembanhngot", "o028", "tiembanhngot:LYG-KRAFT", "sales1",
         "In lụa 1 màu nâu, logo tiệm bánh giữa thân, cao 40 mm", 5, 30, None),
        ("conv:pizza4u", "o037", "pizza4u:HOP-QUA", "sales2",
         "In offset 4 màu nắp hộp, cán màng mờ; mặt trong không in", 10, 50,
         ("Đề nghị giảm còn 2.400.000đ/thùng cho 20 thùng như đơn khai trương trước", 2600000)),
        ("conv:caphemoc", "o047", "caphemoc:LYG-2L", "sales1",
         "In 2 màu Pantone (nâu 4625C, kem 7500C), logo giữa thân, cao 45 mm", 10, 50, None)]:
    o = ORDER_BY_KEY[okey]
    assert o["custom"] and len(o["lines"]) == 1 and o["lines"][0]["design"] is DESIGNS[dkey], okey
    ol = o["lines"][0]
    t0 = DESIGNS[dkey]["at"]                       # snapshot confirmed: the day before its sample was requested
    tiers = tiers3(ol["price"], lo, ol["qty"] // 10 * 10 or ol["qty"], hi)
    first = [(dkey, ol["sku"], ol["qty"], tiers, spec)]
    events = [("send", t0 + timedelta(hours=6))]
    if rounds:
        # Revision 1 was sent a break dearer; the customer asked for the agreed price, Sales revised and sent again.
        feedback, asked_from = rounds
        dear = [(m, p + asked_from - ol["price"]) for m, p in tiers]
        first = [(dkey, ol["sku"], ol["qty"], dear, spec)]
        events += [("changes", t0 + timedelta(hours=23, minutes=30), feedback),
                   ("reopen", t0 + timedelta(days=1, hours=1)),
                   ("revise", t0 + timedelta(days=1, hours=2), [(dkey, ol["sku"], ol["qty"], tiers, spec)]),
                   ("send", t0 + timedelta(days=1, hours=5))]
    events += [("accept", t0 + timedelta(days=5, hours=1)), ("convert", o["row"]["placed_at"], o)]
    cq = quote(qkey, o["cust"], sales, o["term"], t0 + timedelta(hours=1), at(t0.date() + timedelta(days=30), 23, 59),
               first, events)
    link_sample(dkey, cq)

# Accepted, waiting to be converted: a reprint of two approved designs; the customer took 50 of line 2 instead of the
# 30 proposed, which reached its next break.
quote("accepted:trasuamay", "trasuamay", "sales2", "CREDIT", at(date(2026, 10, 6), 9, 30), at(date(2026, 11, 5), 23, 59),
      [("trasuamay:LYN-PET", "LYN-PET-22OZ", 40, [(20, 650000), (40, 624000), (80, 599000)],
        "In 1 màu trắng, logo giữa thân, cao 50 mm"),
       ("trasuamay:LYG-1L", "LYG-1L-12OZ", 30, [(20, 520000), (30, 499000), (50, 480000)],
        "In 2 màu Pantone theo bộ nhận diện, logo giữa thân, cao 45 mm")],
      [("send", at(date(2026, 10, 6), 14)), ("accept", at(date(2026, 10, 9), 16, 20), {2: 50})])

# Sent and open: line 2 puts the approved design on a larger cup, whose sample is still a draft linked to the quote -
# the customer cannot accept until that sample is approved.
sent_q = quote("sent:khachsanbien", "khachsanbien", "sales2", "DEPOSIT", at(date(2026, 10, 8), 9, 30),
               at(date(2026, 12, 31), 23, 59),
               [("khachsanbien:LYG-1L", "LYG-1L-12OZ", 30, [(20, 540000), (30, 520000), (60, 499000)],
                 "In 1 màu xanh navy, logo khách sạn giữa thân, cao 40 mm"),
                ("khachsanbien:LYG-1L", "LYG-1L-16OZ", 20, [(20, 610000), (40, 589000)],
                 "In 1 màu xanh navy, logo khách sạn giữa thân, cao 50 mm")],
               [("send", at(date(2026, 10, 8), 14))])
ks = DESIGNS["khachsanbien:LYG-1L"]
add("production.sample_request", id=uid("sample:quote:khachsanbien:16oz"),
    request_number=next_no("SMP", date(2026, 10, 9)), customer_id=CUST["khachsanbien"]["id"], quote_id=sent_q["id"],
    previous_round_id=None, round_no=1, blank_sku="LYG-1L-16OZ", design_snapshot_id=ks["snapshot"], quantity=1,
    due_date=date(2026, 10, 16), status="DRAFT", sent_at=None, responded_at=None, responded_by=None,
    customer_feedback=None, cancellation_reason=None, created_at=at(date(2026, 10, 9), 9, 30))

# The customer asked for changes; Sales has not reopened it yet.
quote("changes:bepnhalam", "bepnhalam", "sales1", "DEPOSIT", at(date(2026, 10, 9), 9), at(date(2026, 11, 8), 23, 59),
      [("bepnhalam:LYG-2L", "LYG-2L-12OZ", 30, [(15, 820000), (30, 790000), (60, 760000)],
        "In 1 màu xanh lá, logo nhà hàng giữa thân, cao 40 mm"),
       ("bepnhalam:TUI-KRAFT", "TUI-KRAFT-L-KRAFT", 20, [(10, 620000), (20, 600000)],
        "In 1 màu trắng mặt trước, logo cao 60 mm")],
      [("send", at(date(2026, 10, 9), 11)),
       ("changes", at(date(2026, 10, 11), 15, 30),
        "Đề nghị ly 2 lớp 760.000đ/thùng cho 30 thùng và giao trước ngày 25/10 để kịp sự kiện cuối tháng.")])

# Drafts: one Sales is still pricing, and a reorder the customer asked for on the portal (QuoteService.reorder: the
# converted quote's lines at their accepted quantities, priced again from today's list - the CHAIN segment price,
# one tier from 1 unit - with the source's validity window counted from now).
quote("draft:daily-longbien", "daily-longbien", "sales2", "DEPOSIT", at(date(2026, 10, 12), 10, 15),
      at(date(2026, 11, 11), 23, 59),
      [("daily-longbien:LYN-PET", "LYN-PET-12OZ", 100, [(50, 460000), (100, 442000), (200, 425000)],
        "In 1 màu đỏ, logo đại lý giữa thân, cao 45 mm"),
       ("daily-longbien:LYG-KRAFT", "LYG-KRAFT-16OZ", 50, [(30, 760000), (60, 735000)],
        "In lụa 1 màu đen trên nền kraft, logo cao 40 mm")])
src = next(x for x in QUOTES if x["key"] == "conv:caphemoc")
src_line, src_order = src["lines"][0], ORDER_BY_KEY["o047"]
reordered = at(date(2026, 10, 11), 20, 15)
quote("reorder:caphemoc", "caphemoc", "sales1", "CREDIT", reordered,
      reordered + (src["row"]["valid_until"] - src["row"]["sent_at"]),
      [("caphemoc:LYG-2L", src_line["sku"], src_line["acc_qty"], [(1, unit_price(CUST["caphemoc"], src_line["sku"]))],
        src_line["spec"])],
      draft_reason="Reorder of order " + src_order["number"], drafted_by=CUST["caphemoc"]["user"])

# Expired: sent, never answered; the sweeper (every 5 minutes) expired it once the validity passed.
quote("expired:banhmisaigon", "banhmisaigon", "sales1", "PREPAID", at(date(2026, 9, 10), 9),
      at(date(2026, 9, 25), 23, 59),
      [("banhmisaigon:LYG-1L", "LYG-1L-22OZ", 30, [(20, 800000), (30, 780000), (60, 760000)],
        "In 2 màu đỏ - vàng, logo bánh mì giữa thân, cao 45 mm")],
      [("send", at(date(2026, 9, 10), 10, 30)), ("expire", at(date(2026, 9, 26), 0, 3))])

# Cancelled before it was sent: the customer called off the promotion it was for.
quote("cancelled:caphemoc", "caphemoc", "sales1", "CREDIT", at(date(2026, 10, 5), 10), at(date(2026, 11, 4), 23, 59),
      [("caphemoc:LYN-PET", "LYN-PET-16OZ", 50, [(30, 560000), (50, unit_price(CUST["caphemoc"], "LYN-PET-16OZ"))],
        "In 1 màu nâu, logo giữa thân, cao 50 mm")],
      [("cancel", at(date(2026, 10, 6), 11), "Khách hoãn chương trình khuyến mãi mùa đông, chưa đặt ly 16oz")])

# ============================================================================ 10b. damage, scrap, return evidence
# Everything below runs after every other stock change, adjustment number and ledger line above, so none of those
# moves: ledger ids are numbered by position and document numbers per day. Nothing here draws a random number.

# Photos, evidence and the rows that carry no audit columns (created_at only).
NO_AUDIT.update({"inventory.stock_damage_photo", "inventory.stock_damage_disposition",
                 "inventory.stock_damage_supplier_shipment"})


def evidence_file(category, file_id, stored_at):
    """A photo as FileTransfers stores it: key <category>/<UTC yyyy/MM/dd>/<id>.png (StorageKeys.generate), size and
    SHA-256 of the real bytes (BufferedUpload). A real PNG, since the category is checked by the file's first bytes,
    so the files/ folder can be mirrored into the bucket with the design files."""
    key = f"{category}/{stored_at.astimezone(timezone.utc):%Y/%m/%d}/{file_id}.png"
    data = png_bytes(file_id, 320, 240)
    SEEDED_FILES[key] = data
    return dict(key=key, size=len(data), checksum=hashlib.sha256(data).hexdigest())


def insert_sql(table, row):
    return f"INSERT INTO {table} ({', '.join(row)}) VALUES ({', '.join(q(v) for v in row.values())});"


def lot_of(s):
    # tg_stock_item_lot gives every stock row a lot when it is inserted; the report snapshots the source row's.
    return Raw(f"(SELECT lot_id FROM inventory.stock_item WHERE id = {q(s.id)})")


DAMAGE = {}  # key -> report state, for the decisions below


def damage_report(key, origin, s, qty, reason, note, reporter, when, photos=1, pick=None, from_pick=False):
    """A damage report as StockDamageService writes it (SCRUM-475 / SCRUM-277): the units go, DAMAGED, to the first
    QUARANTINE area of the warehouse by code (<wh>-QRT01) into their own receipt layer, with one ledger line naming the
    report - MOVE AVAILABLE -> DAMAGED from the shelf, or for units a pick already took (PACKING) a PUT_BACK from
    nowhere, since the pick deducted them. A shelf report takes free units only; a picker's takes them out of the hold
    (`pick`: order, pick line, hold row) - the caller makes the order whole. `photos` are stored first (QC_EVIDENCE)."""
    rid = uid("dmg:" + key)
    p = LOC[s.loc]["wh"]
    qrt = f"{p}-QRT01"
    if origin == "STOCK_CHECK":
        assert s.status == "AVAILABLE" and s.free >= qty and s.received < when, (key, s.sku, s.loc, s.free)
    if not from_pick:
        s.on_hand -= qty
    damaged = Stock(s.sku, qrt, qty, s.received, "DAMAGED", lot=s.lot, expiry=s.expiry, key=f"dmg:{key}")
    text = f"{reason}: {note}" if note else reason
    if from_pick:
        move("PUT_BACK", s.sku, qty, "DAMAGE_REPORT", rid, when, reporter, to=qrt, lot=s.lot, fs="DAMAGED",
             ts_="DAMAGED", reason=text)
    else:
        move("MOVE", s.sku, qty, "DAMAGE_REPORT", rid, when, reporter, frm=s.loc, to=qrt, lot=s.lot, fs="AVAILABLE",
             ts_="DAMAGED", reason=text)
    if pick:
        MOVES[-1]["order_id"] = pick["order"]     # forOrder: tg_stock_movement_order_number adds the number
    number = next_no("DMG", when.date())
    add("inventory.stock_damage_report", id=rid, report_number=number, request_id=uid("dmg:request:" + key),
        origin=origin, warehouse_id=WH[p], sku=s.sku, lot_number=s.lot, serial_number=None, received_at=s.received,
        lot_id=lot_of(s), source_stock_item_id=s.id, source_location_code=s.loc, source_stock_package_id=None,
        damaged_stock_item_id=damaged.id, damage_location_code=qrt, quantity=qty, reason_code=reason, note=note,
        order_id=pick["order"] if pick else None, pick_line_id=pick["line"] if pick else None,
        reservation_id=pick["hold"] if pick else None, impaired_reservation_id=None, reported_by=U[reporter],
        reported_at=when, created_at=when)
    for n in range(1, photos + 1):
        f = evidence_file("qc-evidence", uid(f"dmg:{key}:file:{n}"), when - timedelta(minutes=3))
        add("inventory.stock_damage_photo", id=uid(f"dmg:{key}:photo:{n}"), damage_report_id=rid,
            storage_key=f["key"], original_name=f"hang-loi-{number[-4:]}-{n}.png", content_type="image/png",
            size_bytes=f["size"], checksum=f["checksum"], sort_order=n, created_at=when)
    DAMAGE[key] = dict(key=key, id=rid, number=number, s=s, damaged=damaged, reporter=reporter, when=when, qty=qty)
    return DAMAGE[key]


def disposition_row(r, kind, qty, note, decider, when, **extra):
    row = dict(id=uid(f"dmg:{r['number']}:{kind}:{when.isoformat()}"), damage_report_id=r["id"],
               request_id=uid(f"dmg:{r['number']}:{kind}:{when.isoformat()}:request"), disposition_type=kind,
               quantity=qty, note=note, record_number=None, stock_adjustment_id=None, restore_location_code=None,
               restored_stock_item_id=None, supplier_id=None, supplier_name=None, decided_by=U[decider],
               decided_at=when, created_at=when)
    row.update(extra)
    return row


def write_off(r, qty, decider, when, note=None, after_shipments=False):
    """StockDamageService.writeOff: a DAMAGED adjustment of exactly these units, asked in the reporter's name and
    approved and posted by the decider at once (the report is the four eyes), the destruction record numbered on the
    decision; ADJUSTMENT ledger line out of quarantine. `after_shipments`: the decision follows a supplier's refusal,
    and the table's trigger counts refused units back as undecided only once the refusal row exists."""
    d = r["damaged"]
    assert d.on_hand - d.reserved >= qty
    record = next_no("WO", when.date())
    aid = uid(f"adj:dmg:{r['number']}:{record}")
    add("inventory.stock_adjustment", id=aid, adjustment_number=next_no("ADJ", when.date()), location_code=d.loc,
        sku=d.sku, lot_number=d.lot, quantity_delta=-qty, reason_code="DAMAGED",
        note=f"Damage report {r['number']}, destruction record {record}" + (f": {note}" if note else ""),
        status="POSTED", cycle_count_line_id=None, requested_by=U[r["reporter"]], decided_by=U[decider],
        decided_at=when, rejection_reason=None, posted_at=when, withdrawn_at=None, received_at=d.received,
        created_at=when)
    d.on_hand -= qty
    move("ADJUSTMENT", d.sku, qty, "STOCK_ADJUSTMENT", aid, when, decider, frm=d.loc, lot=d.lot, fs="DAMAGED",
         ts_="DAMAGED", reason=f"DAMAGED: {r['number']} {record}")
    row = disposition_row(r, "WRITE_OFF", qty, note, decider, when, record_number=record, stock_adjustment_id=aid)
    if after_shipments:
        POST.append(("inventory.stock_damage_supplier_shipment", insert_sql("inventory.stock_damage_disposition", row)))
    else:
        add("inventory.stock_damage_disposition", **row)


def restore(r, qty, decider, when, note):
    """StockDamageService.restore: re-inspected and fine - back onto a bin of the warehouse as AVAILABLE, into the
    layer already there (here the bin they came from, so they join their own row); MOVE DAMAGED -> AVAILABLE."""
    d, s = r["damaged"], r["s"]
    d.on_hand -= qty
    back = Stock(s.sku, s.loc, qty, s.received, "AVAILABLE", lot=s.lot, expiry=s.expiry)
    assert back is s
    move("MOVE", d.sku, qty, "DAMAGE_REPORT", r["id"], when, decider, frm=d.loc, to=s.loc, lot=d.lot, fs="DAMAGED",
         ts_="AVAILABLE", reason=f"RESTORE: {note}")
    add("inventory.stock_damage_disposition", **disposition_row(r, "RESTORE", qty, note, decider, when,
                                                                 restore_location_code=s.loc,
                                                                 restored_stock_item_id=s.id))


def return_to_supplier(r, qty, decider, when, note):
    """Intent only: the units stay DAMAGED in quarantine until handed over. The supplier is the lot's, and every seeded
    lot is UNTRACED (tg_stock_item_lot made it), so it is not known - as the application would record it."""
    row = disposition_row(r, "RETURN_TO_SUPPLIER", qty, note, decider, when)
    add("inventory.stock_damage_disposition", **row)
    return row["id"]


def supplier_shipment(r, decision, outcome, qty, by, when, delivery_note=None, note=None):
    """SHIPPED: off the books at once, SUPPLIER_RETURN from quarantine naming the report; REFUSED: nothing moves, the
    units wait for a decision again."""
    sid = uid(f"dmg:{r['number']}:ship:{outcome}:{when.isoformat()}")
    add("inventory.stock_damage_supplier_shipment", id=sid, disposition_id=decision, damage_report_id=r["id"],
        request_id=uid(f"dmg:{r['number']}:ship:{outcome}:{when.isoformat()}:request"), outcome=outcome,
        quantity=qty, delivery_note=delivery_note, note=note, shipped_by=U[by], shipped_at=when, created_at=when)
    if outcome == "SHIPPED":
        d = r["damaged"]
        d.on_hand -= qty
        move("SUPPLIER_RETURN", d.sku, qty, "DAMAGE_REPORT", r["id"], when, by, frm=d.loc, lot=d.lot, fs="DAMAGED",
             ts_="DAMAGED", reason=f"RETURN_TO_SUPPLIER (supplier not on the lot): delivery note {delivery_note}")


CARTONED = {s.id for s in STOCK if getattr(s, "pkg", None)}


def shelf_stock(p, min_free):
    # Loose stock only: a carton's units are reported through its own row, and the seeded cartons wait for a transfer.
    s = find_stock(p, min_free=min_free, skip=used | CARTONED)
    used.add(s.id)
    return s


# Shelf reports (origin STOCK_CHECK), one per way a report ends.
r = damage_report("hn:forklift", "STOCK_CHECK", shelf_stock("HN", 20), 4, "BROKEN",
                  "Thùng rơi khi hạ tầng kệ, ly bên trong vỡ", "kho.hn1", at(date(2026, 10, 5), 9, 40), photos=2)
write_off(r, 4, "quanly.hn", at(date(2026, 10, 5), 14, 10), note="Tiêu hủy cùng rác thải công nghiệp")
r = damage_report("bd:leak", "STOCK_CHECK", shelf_stock("BD", 20), 6, "WET_OR_MOULDY",
                  "Mái kho dột, 6 thùng ướt và ẩm mốc", "kho.bd2", at(date(2026, 10, 2), 10, 15), photos=2)
decision = return_to_supplier(r, 6, "quanly.bd", at(date(2026, 10, 3), 9, 0),
                              "Bao bì ngoài kém chống ẩm, NCC đồng ý xem xét nhận lại")
supplier_shipment(r, decision, "SHIPPED", 4, "kho.bd1", at(date(2026, 10, 7), 15, 30), delivery_note="PXT-BD-20261007-01")
supplier_shipment(r, decision, "REFUSED", 2, "kho.bd1", at(date(2026, 10, 7), 15, 45),
                  note="NCC chỉ nhận 4 thùng còn nguyên tem niêm phong")
write_off(r, 2, "quanly.bd", at(date(2026, 10, 8), 10, 0), note="NCC từ chối nhận lại", after_shipments=True)
r = damage_report("hn:dented", "STOCK_CHECK", shelf_stock("HN", 20), 3, "DEFORMED", "3 thùng móp góc khi kiểm kê",
                  "qc.hn", at(date(2026, 10, 8), 15, 20))
restore(r, 2, "quanly.hn", at(date(2026, 10, 9), 8, 30), "Kiểm tra lại: chỉ móp vỏ thùng, ly bên trong nguyên vẹn")
damage_report("dn:rodent", "STOCK_CHECK", shelf_stock("DN", 10), 2, "OTHER", "Thùng bị chuột cắn thủng, chờ quản lý xử lý",
              "kho.dn1", at(date(2026, 10, 11), 16, 5))


def pick_rows(o):
    pid = uid(f"pick:{o['key']}")
    return [x for x in ROWS["fulfillment.pick_line"] if x["pick_id"] == pid]


def holds_by_id():
    return {x["id"]: x for x in ROWS["inventory.stock_reservation"]}


def replacement(o, line, hold, k, report, when):
    """The order made whole (planReplacement): the same units allocated again FEFO on pickable stock of the warehouse -
    the bin included - each on a new hold `allocated from` the order's original one (Reservation.allocate, HELD, no
    deadline), keyed by the report's request id; the order line holds it next to its old hold (replaceHold), and the
    pick list gets one OPEN line per new hold after its last stop, naming the report (PickList.replaceDamaged)."""
    plan = allocate(o["p"], line["sku"], k)
    assert plan, (o["number"], line["sku"], k)
    seq = max(x["sequence_no"] for x in pick_rows(o))
    for n, (row, qn) in enumerate(plan):
        rid = uid(f"res:dmg:{report['number']}:{n}")
        add("inventory.stock_reservation", id=rid, stock_item_id=row.id, order_id=o["id"],
            root_request_id=uid("dmg:request:" + report["key"]), request_id=uid(f"dmg:replacement:{report['number']}:{row.id}"),
            quantity=qn, reserved_at=when, expires_at=None, status="HELD", release_reason=None, closed_at=None,
            allocated_from_id=hold["id"])
        row.reserved += qn
        add("ordering.order_line_reservation", order_line_id=line["order_line_id"], reservation_id=rid)
        seq += 1
        add("fulfillment.pick_line", id=uid(f"pickline:dmg:{report['number']}:{n}"), pick_id=line["pick_id"],
            order_line_id=line["order_line_id"], reservation_id=rid, sku=line["sku"], location_code=row.loc,
            lot_number=row.lot, sequence_no=seq, allocated_qty=qn, picked_qty=0, short_qty=0, status="OPEN",
            picked_by=None, picked_at=None, consumed_at=None, damage_report_id=report["id"], created_at=when)


# Damage found by the picker at the bin (origin PICKING, SCRUM-277): an order still being picked, one of its OPEN lines.
# Replacement stock exists, so the order is not put ON_HOLD: the line is CANCELLED with its damaged units, the good ones
# stay to be picked against the same (smaller) hold on a new OPEN line, the replacement gets lines of its own.
o_pick = next(o for o in ORDERS if o["status"] == "PICKING" and o["p"] == "HN")
line = next(x for x in pick_rows(o_pick) if x["status"] == "OPEN" and allocate("HN", x["sku"], 3))
hold = holds_by_id()[line["reservation_id"]]
src = next(s for s in STOCK if s.id == hold["stock_item_id"])
t_dmg = o_pick["when"]["PICKING"] + timedelta(hours=1, minutes=10)
src.reserved -= 3                         # giveUpDamagedHeld: out of the hold, then off the row like free units
hold["quantity"] -= 3                     # reduceHold: the hold keeps its id over fewer units
r = damage_report("pick:" + o_pick["key"], "PICKING", src, 3, "BROKEN", "Ly vỡ trong thùng khi lấy khỏi kệ",
                  staff_of("HN")[0], t_dmg, pick=dict(order=o_pick["id"], line=line["id"], hold=hold["id"]))
seq = max(x["sequence_no"] for x in pick_rows(o_pick))
line.update(status="CANCELLED", damaged_qty=3, damage_report_id=r["id"])
add("fulfillment.pick_line", id=uid(f"pickline:dmg:{r['number']}:kept"), pick_id=line["pick_id"],
    order_line_id=line["order_line_id"], reservation_id=hold["id"], sku=line["sku"], location_code=line["location_code"],
    lot_number=line["lot_number"], sequence_no=seq + 1, allocated_qty=line["allocated_qty"] - 3, picked_qty=0,
    short_qty=0, status="OPEN", picked_by=None, picked_at=None, consumed_at=None, damage_report_id=r["id"],
    created_at=t_dmg)
replacement(o_pick, line, hold, 3, r, t_dmg)

# Damage found in the tote after the pick (origin PACKING, R8): the other order being picked, its PICKED line. The units
# were deducted by the pick, so they come back into quarantine from nowhere; the line keeps them as packing_damaged_qty
# and the replacement is picked again; the task is still PICKING, so nothing reopens.
o_pack = next(o for o in ORDERS if o["status"] == "PICKING" and o["p"] == "BD")
line = next(x for x in pick_rows(o_pack) if x["status"] == "PICKED" and allocate("BD", x["sku"], 2))
hold = holds_by_id()[line["reservation_id"]]
src = next(s for s in STOCK if s.id == hold["stock_item_id"])
t_dmg = line["picked_at"] + timedelta(minutes=35)
r = damage_report("pack:" + o_pack["key"], "PACKING", src, 2, "BROKEN", "Khay giấy rách khi đặt vào thùng soạn hàng",
                  staff_of("BD")[0], t_dmg, pick=dict(order=o_pack["id"], line=line["id"], hold=hold["id"]),
                  from_pick=True)
line["packing_damaged_qty"] = 2
replacement(o_pack, line, hold, 2, r, t_dmg)
write_off(r, 2, "quanly.bd", at(t_dmg.date() + timedelta(days=1), 8, 15))

# Production scrap (BR-PRD-04): the in-house job waiting in QC spoilt 3 of the 30 it printed - 2 smudged, 1 on the
# set-up sheet. recordOutput: good 27, scrap 3, still QC (27 good units await inspection; a reprint follows QC).
# WorkshopStock.writeOff: per scrap line, the units leave the print-shop hold at once (takeFromHold - still on hand,
# IN_PRODUCTION, not sellable) and a SCRAP adjustment is asked for in the worker's name (decision Q4: production does
# not wait for the approval); production_scrap records the line with it. The manager approved the first one next day.
job = next(x for x in PRODUCTION if x["row"]["status"] == "QC" and x["row"]["mode"] == "IN_HOUSE"
           and x["row"]["type"] == "ORDER")
jr = job["row"]
spoilt = [("PRINT_DEFECT", 2, "Lem mực logo, lệch màu so với mẫu"), ("SETUP", 1, "Ly canh chỉnh bản in đầu ca")]
jr["good_quantity"] -= sum(n for _, n, _ in spoilt)
jr["scrap_quantity"] += sum(n for _, n, _ in spoilt)
run_at = job["times"]["QC"]
for x in ROWS["production.production_print_run"]:
    if x["production_order_id"] == job["id"]:
        x["scrap_quantity"] = jr["scrap_quantity"]
for x in ROWS["production.production_order_status_history"]:
    if x["production_order_id"] == job["id"] and x["to_status"] == "QC":
        x["reason"] = f"Printed {jr['printed_quantity']}, scrap {jr['scrap_quantity']}"
issued = [i for i in ISSUES if i["production_order_id"] == job["id"]]
holds = holds_by_id()
for n, (why, qty, note) in enumerate(spoilt):
    issue = next(i for i in issued if holds[i["reservation_id"]]["quantity"] >= qty)
    hold = holds[issue["reservation_id"]]
    shop = next(s for s in STOCK if s.id == hold["stock_item_id"])
    assert shop.status == "IN_PRODUCTION" and hold["status"] == "HELD"
    hold["quantity"] -= qty
    shop.reserved -= qty
    approved = n == 0
    aid = uid(f"adj:scrap:{job['key']}:{n}")
    text = f"{jr['order_number']} {why}: {note}"
    decided = run_at + timedelta(days=1, hours=1)
    add("inventory.stock_adjustment", id=aid, adjustment_number=next_no("ADJ", run_at.date()), location_code=shop.loc,
        sku=shop.sku, lot_number=shop.lot, quantity_delta=-qty, reason_code="SCRAP", note=text,
        status="POSTED" if approved else "PENDING_APPROVAL", cycle_count_line_id=None, requested_by=U[job["worker"]],
        decided_by=U["quanly." + job["p"].lower()] if approved else None, decided_at=decided if approved else None,
        rejection_reason=None, posted_at=decided if approved else None, withdrawn_at=None, received_at=shop.received,
        created_at=run_at)
    if approved:
        shop.on_hand -= qty
        move("ADJUSTMENT", shop.sku, qty, "STOCK_ADJUSTMENT", aid, decided, "quanly." + job["p"].lower(), frm=shop.loc,
             lot=shop.lot, fs="IN_PRODUCTION", ts_="IN_PRODUCTION", reason=f"SCRAP: {text}")
    add("production.production_scrap", id=uid(f"prod:{job['key']}:scrap:{n}"), production_order_id=job["id"],
        quantity=qty, reason=why, note=note, stock_adjustment_id=aid, recorded_at=run_at, recorded_by=U[job["worker"]])

# Return evidence (SCRUM-243): the claim's photos - the customer's on the portal, Sales' for a claim opened in the back
# office - taken before the decision (a claim is approved only with a photo, ReturnPolicy.evidenceRequired); the
# warehouse's at receipt and QC's at inspection on the resolved one, while the return was at that stage
# (ReturnRequest.requireEvidenceAllowed). FileCategory.RETURN_EVIDENCE, no checksum column.
CLAIM_PHOTOS = {"REQUESTED": 2, "AWAITING_RETURN": 1, "RESOLVED": 2, "REJECTED": 0}
for rr in ROWS["ordering.return_request"]:
    shots = [("CLAIM", rr["requested_by"], rr["requested_at"] + timedelta(minutes=5 + 2 * k), f"khieu-nai-{k + 1}.png")
             for k in range(CLAIM_PHOTOS[rr["status"]])]
    if rr["status"] == "RESOLVED":
        shots += [("RECEIPT", rr["received_by"], rr["received_at"] + timedelta(minutes=20), "kien-hang-tra-ve.png"),
                  ("INSPECTION", rr["inspected_by"], rr["inspected_at"] + timedelta(minutes=10), "kiem-tra-qc.png")]
    for k, (stage, who, when, name) in enumerate(shots):
        f = evidence_file("return-evidence", uid(f"rma-file:{rr['rma_number']}:{k}"), when)
        add("ordering.return_request_evidence", id=uid(f"rma-evidence:{rr['rma_number']}:{k}"),
            return_request_id=rr["id"], stage=stage, file_key=f["key"], original_name=name, content_type="image/png",
            size_bytes=f["size"], uploaded_by=who, uploaded_at=when, created_at=when)
    if rr["status"] == "RESOLVED":
        # The parcel held what was approved (no SHORT, EXCESS or WRONG_ITEM) plus something of no line: recorded at
        # receipt, never stock (ReturnRequest.receive, UNEXPECTED_ITEM).
        add("ordering.return_request_discrepancy", id=uid(f"rma-discrepancy:{rr['rma_number']}"),
            return_request_id=rr["id"], return_request_line_id=None, kind="UNEXPECTED_ITEM", sku=None, quantity=1,
            note="1 túi nắp nhựa không nhãn, không thuộc đơn hàng - giữ lại chờ Sales liên hệ khách",
            recorded_by=rr["received_by"], recorded_at=rr["received_at"], created_at=rr["received_at"])

# ============================================================================ 10b. full-truckload load planning
# SCRUM-287 (V20261022001200 estimates, V20261022001300 plans, V20261022001400 per-vehicle dispatch): every order above
# travels as PARCEL, and a load plan holds only sealed parcels of FULL_TRUCKLOAD shipments waiting for dispatch
# (tg_load_plan_package_check), so seven full-truckload orders are placed here, at HN, after every random draw of the
# data set: their timelines are fixed (place_order's `fixed`), nothing above moves. The coordinator chose the mode at
# release (order_delivery_mode_change) after estimating the order in the release dialog (load_estimate).
#
#   LP A  DISPATCHED  FTL-1 + FTL-2 on one TRUCK-2T5; its load signed and gone, both shipments handed over with it
#   LP B  CONFIRMED   FTL-3 + FTL-4 + FTL-5 on two TRUCK-1T25 (FTL-4 spans both, LP-3): vehicle 1 is being loaded,
#                     vehicle 2 booked again after its first booking fell through
#   LP D  CANCELLED   a DRAFT with FTL-6 alone, called off to combine it with FTL-7 (PLAN_CANCELLED)
#   LP C  PLANNED     FTL-6 + FTL-7: calculated on TRUCK-1T25 (two vehicles), then switched to FEWEST_VEHICLES with
#                     TRUCK-2T5 too - the first vehicles retired (active = FALSE), one TRUCK-2T5 now, not confirmed
#   LP E  DRAFT       created this morning with its vehicle types only
#
# Geometry as the planner reports it (LoadPlanner, PlacementValidator): whole mm from the inner floor corner at the
# front wall, x across, y up, z toward the door; placed sizes are the parcel's measured ones, ceil(cm x 10)
# (LoadPlanParcels.mm), turned by Orientation. Every vehicle is checked below as PlacementValidator would check it.
FTL_WH = "HN"
FTL_STEP = timedelta(days=1)


def vehicle_type_ref(code):
    """The id V20261022000700 gives a preset vehicle type: md5('vehicle-type:<code>')::uuid."""
    h = hashlib.md5(("vehicle-type:" + code).encode()).hexdigest()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


# The presets as V20261022000700 seeds them (trucks: no gusset, no door limit): code -> kind, L, W, H mm, payload kg.
VEHICLE_TYPE = {"TRUCK-1T25": ("TRUCK", 3100, 1600, 1600, 1250), "TRUCK-2T5": ("TRUCK", 4300, 1800, 1800, 2500),
                "TRUCK-8T": ("TRUCK", 7400, 2350, 2300, 8000), "TRUCK-15T": ("TRUCK", 9300, 2350, 2400, 15000)}
# Orientation: name -> which measured (width, height, length) lies along x, y, z; the list order is the index.
ORIENTATION = [("LWH", (0, 1, 2)), ("HWL", (0, 2, 1)), ("LHW", (1, 0, 2)), ("WHL", (1, 2, 0)), ("HLW", (2, 0, 1)),
               ("WLH", (2, 1, 0))]


def extents(dims_lwh, orientation):
    """Placed (x, y, z) sizes of a carton measured (length, width, height) lying `orientation` (Orientation.extents)."""
    length, width, height = dims_lwh
    measured = (width, height, length)
    return tuple(measured[k] for k in dict(ORIENTATION)[orientation])


def orientation_of(dims_lwh, w, h, d):
    """The first orientation in index order with these placed sizes (Orientation.of): a cube is always LWH."""
    return next(i for i, (name, _) in enumerate(ORIENTATION) if extents(dims_lwh, name) == (w, h, d))


def pct(part, whole):
    return float((Decimal(part) * 100 / Decimal(whole)).quantize(Decimal("0.01"), ROUND_HALF_UP))


def check_vehicle(code, boxes):
    """PlacementValidator.validate for one vehicle: inside the box, no overlap, 98 % support, payload."""
    _, length, width, height, payload = VEHICLE_TYPE[code]
    for i, b in enumerate(boxes):
        assert b["x"] >= 0 and b["y"] >= 0 and b["z"] >= 0 and b["x"] + b["w"] <= width \
            and b["y"] + b["h"] <= height and b["z"] + b["d"] <= length, (code, b)
        assert sorted((b["w"], b["h"], b["d"])) == sorted(b["dims"]), (code, b)
        covered = 0
        for j, o in enumerate(boxes):
            if i == j:
                continue
            assert not (b["x"] < o["x"] + o["w"] and o["x"] < b["x"] + b["w"] and b["y"] < o["y"] + o["h"]
                        and o["y"] < b["y"] + b["h"] and b["z"] < o["z"] + o["d"] and o["z"] < b["z"] + b["d"]), (b, o)
            if o["y"] + o["h"] == b["y"]:
                ox = min(b["x"] + b["w"], o["x"] + o["w"]) - max(b["x"], o["x"])
                oz = min(b["z"] + b["d"], o["z"] + o["d"]) - max(b["z"], o["z"])
                covered += ox * oz if ox > 0 and oz > 0 else 0
        assert b["y"] == 0 or 100 * covered >= 98 * b["w"] * b["d"], (code, b)
    assert sum(b["kg"] for b in boxes) <= payload, code


def load_sequence(boxes):
    """LoadPlanner.loadSequence: a carton after every carton it rests on; then nearest the front, lowest, leftmost."""
    n = len(boxes)
    above, below = [[] for _ in range(n)], [0] * n
    for i, a in enumerate(boxes):
        for j, b in enumerate(boxes):
            if i != j and b["y"] + b["h"] == a["y"] \
                    and min(a["x"] + a["w"], b["x"] + b["w"]) > max(a["x"], b["x"]) \
                    and min(a["z"] + a["d"], b["z"] + b["d"]) > max(a["z"], b["z"]):
                above[j].append(i)
                below[i] += 1
    ready = sorted(i for i in range(n) if below[i] == 0)
    seq, nxt = [0] * n, 1
    while ready:
        ready.sort(key=lambda i: (boxes[i]["z"], boxes[i]["y"], boxes[i]["x"], i))
        i = ready.pop(0)
        seq[i] = nxt
        nxt += 1
        for k in above[i]:
            below[k] -= 1
            if below[k] == 0:
                ready.append(k)
    return seq


def pack(code, groups):
    """A plan for cartons of one vehicle type, the way the block filler lays same-size cartons: each group (cartons of
    one size) in rows across the width, stacked in whole columns (full support), one z-slab after the other; a vehicle
    that is full hands the rest of the group to the next one. Each group takes the turn that needs the shortest slab.
    Returns the vehicles as lists of boxes."""
    _, length, width, height, _ = VEHICLE_TYPE[code]
    vehicles, current, z0 = [], [], 0
    for cartons in groups:
        dims = cartons[0]["dims"]
        best = None
        for name, _ in ORIENTATION:
            w, h, d = extents(dims, name)
            if w <= width and h <= height and d <= length:
                per_row = (width // w) * (height // h)
                slab = -(-len(cartons) // per_row) * d
                if best is None or slab < best[0]:
                    best = (slab, name, w, h, d, width // w, height // h)
        _, name, w, h, d, nx, ny = best
        left = list(cartons)
        while left:
            if z0 + d > length:
                vehicles.append(current)
                current, z0 = [], 0
            row, left = left[:nx * ny], left[nx * ny:]
            for k, c in enumerate(row):
                iy, ix = divmod(k, nx)
                current.append(dict(c, x=ix * w, y=iy * h, z=z0, w=w, h=h, d=d, orientation=name))
            z0 += d
    vehicles.append(current)
    for v in vehicles:
        check_vehicle(code, v)
    return vehicles


def usage(code, boxes):
    _, length, width, height, payload = VEHICLE_TYPE[code]
    volume = sum(b["w"] * b["h"] * b["d"] for b in boxes)
    weight = sum(Decimal(str(b["kg"])) for b in boxes)
    return volume, float(weight), pct(volume, length * width * height), pct(weight, payload)


# --- the orders ---------------------------------------------------------------------------------------------------
# key, customer, placed at, final status, lines (SKU, cartons); each line fills one pallet-sized parcel (place_order
# packs an order of more than 40 units in two). A prepaid order takes one step more (PENDING_PAYMENT) than a credit
# one, so FTL-1 and FTL-2 reach picking - and the dispatch - together.
FTL_ORDERS = [
    ("ftl1", "hoatuoi", at(date(2026, 10, 2), 9), "SHIPPED", [("LYG-1L-16OZ", 24), ("LYG-1L-22OZ", 24)]),
    ("ftl2", "trasuamay", at(date(2026, 10, 3), 9), "SHIPPED", [("LYG-1L-8OZ", 24), ("LYG-KRAFT-16OZ", 24)]),
    ("ftl3", "bakeryhouse", at(date(2026, 10, 8), 8), "PACKED", [("TUI-QUAI-M", 24), ("TUI-QUAI-L", 24)]),
    ("ftl4", "trasuamay", at(date(2026, 10, 8), 8), "PACKED", [("LYN-PET-22OZ", 24), ("LYN-PET-24OZ", 24)]),
    ("ftl5", "kemtuoi", at(date(2026, 10, 7), 8), "PACKED", [("HOP-PIZZA-8IN", 24), ("HOP-PIZZA-10IN", 24)]),
    ("ftl6", "hotelhanoi", at(date(2026, 10, 8), 8), "PACKED", [("NAP-GIAY-D90-WHITE", 24), ("NAP-GIAY-D90-BLACK", 24)]),
    ("ftl7", "nhahangsen", at(date(2026, 10, 8), 8), "PACKED", [("OHG-BLACK", 24), ("TAYCAM-16OZ", 24)]),
]
NO_AUDIT.add("ordering.order_shipment_line")   # a link table without audit columns


def row_of(table, id_):
    return next(r for r in ROWS[table] if r["id"] == id_)


def mm_up(cm):
    """cm with two decimals to whole mm, rounded up (LoadPlanParcels.mm, tg_load_placement_check)."""
    return int((Decimal(str(cm)) * 10).to_integral_value(rounding=ROUND_CEILING))


FTL = {}
for key, cust_code, placed_at, final, spec in FTL_ORDERS:
    # LP-2: a full truckload carries no COD, so no deposit customer whose balance the carrier could collect.
    assert CUST[cust_code]["wh"] == FTL_WH and (CUST[cust_code]["terms"] or ("PREPAID",))[0] != "DEPOSIT"
    o = place_order(key, cust_code, placed_at.date(), final, lines_spec=spec, fixed=(placed_at, FTL_STEP))
    assert o and len(o["lines"]) == len(spec), (key, "not enough free stock at " + FTL_WH)
    ship_id = uid(f"ship:{key}")
    ship = row_of("fulfillment.shipment", ship_id)
    t_pick = o["when"]["PICKING"]
    # LP-1: packing copies the order's mode onto its shipment; a full truckload gets its carrier with the vehicle's
    # booking (DispatchLoad.book), so the shipment names none while it waits.
    o["row"].update(delivery_mode="FULL_TRUCKLOAD", packing_started_at=t_pick + timedelta(hours=3))
    ship.update(delivery_mode="FULL_TRUCKLOAD", carrier=None, carrier_id=None)
    add("ordering.order_delivery_mode_change", id=uid(f"dmc:{key}"), order_id=o["id"], delivery_mode="FULL_TRUCKLOAD",
        previous_mode="PARCEL", order_status="CONFIRMED", note="Khách nhận nguyên chuyến tại kho, không qua hãng",
        changed_by=U["dieuphoi"], changed_at=o["when"]["READY_TO_FULFILL"],
        created_at=o["when"]["READY_TO_FULFILL"])
    parcels = []
    for k in range(2):
        p = row_of("fulfillment.package", uid(f"pkg:{key}:{k}"))
        dims = (mm_up(p["length_cm"]), mm_up(p["width_cm"]), mm_up(p["height_cm"]))
        parcels.append(dict(id=p["id"], ship=ship_id, dims=dims, kg=p["weight_kg"], row=p))
    FTL[key] = dict(o=o, ship=ship, parcels=parcels, ready=ship["ready_at"])

DISPATCHER, MANAGER = U[staff_of(FTL_WH)[0]], U[staff_of(FTL_WH)[2]]


def finish_later(table, row, **final):
    """The row goes in as it was when the plan took it and reaches `final` by an UPDATE once the plan and its loads
    exist, as the application got there: a parcel is planned and loaded only while its shipment waits for dispatch on
    an open load (tg_load_plan_package_check, tg_dispatch_load_shipment_check)."""
    POST.append(("fulfillment.dispatch_load_package", f"UPDATE {table} SET "
                 + ", ".join(f"{k} = {q(v)}" for k, v in final.items()) + f" WHERE id = {q(row['id'])};"))


def plan_header(key, d, created, status, mode, types, note=None, **extra):
    pid = uid(f"lp:{key}")
    row = add("fulfillment.load_plan", id=pid, code=next_no("LP", d), request_id=uid(f"lp:{key}:request"),
              warehouse_id=WH[FTL_WH], source_type="ORDER", selection_mode=mode, status=status, note=note,
              calculated_at=None, calculated_by=None, solver_meta=None, unplaced_acknowledged_at=None,
              unplaced_acknowledged_by=None, unplaced_ack_note=None, confirmed_at=None, confirmed_by=None,
              reopened_at=None, reopened_by=None, reopen_reason=None, dispatched_at=None, cancelled_at=None,
              cancelled_by=None, cancel_reason=None, revised_at=created, version=0, created_at=created,
              last_modified_at=None, last_modified_by=None)
    row.update(extra)
    for n, code in enumerate(types):
        add("fulfillment.load_plan_vehicle_type", id=uid(f"lp:{key}:type:{code}"), load_plan_id=pid,
            vehicle_type_id=vehicle_type_ref(code), sort_order=n, created_at=created)
    return row


def solver_meta(algorithm, code, parcels, elapsed):
    """LoadPlanService.meta: what the planner reported, trimmed. minVehicles is its volume floor for the type."""
    _, length, width, height, _ = VEHICLE_TYPE[code]
    volume = sum(p["dims"][0] * p["dims"][1] * p["dims"][2] for p in parcels)
    return {"algorithm": algorithm, "minVehicles": -(-volume // (length * width * height)), "setsTotal": 0,
            "budgetExhausted": False, "elapsedMillis": elapsed, "bestFoundMillis": elapsed // 3, "kitWarnings": []}


def plan_members(plan, parcels, added, by, **released):
    for p in parcels:
        add("fulfillment.load_plan_package", id=uid(f"lpp:{plan['id']}:{p['id']}"), load_plan_id=plan["id"],
            package_id=p["id"], shipment_id=p["ship"], transfer_order_id=None, active=not released,
            unplaced_reason=None, added_at=added, added_by=by, released_at=released.get("at"),
            release_reason=released.get("reason"), created_at=added,
            last_modified_at=released.get("at"), last_modified_by=BY if released else None)


def plan_vehicle(plan, seq, code, boxes, created, active=True, placed=True, needs_recalculation=False,
                 changed=None):
    """One vehicle of a plan with its type copied (LoadPlanVehicleJpaEntity) and its usage as LoadPlan.withUsage
    computes it; `placed` writes its placements (a retired vehicle's were deleted, LoadPlanStore step 3)."""
    kind, length, width, height, payload = VEHICLE_TYPE[code]
    vid = uid(f"lpv:{plan['id']}:{seq}:{code}")
    volume, weight, vpct, wpct = usage(code, boxes)
    add("fulfillment.load_plan_vehicle", id=vid, load_plan_id=plan["id"], seq=seq, active=active,
        vehicle_type_id=vehicle_type_ref(code), vehicle_code=code, vehicle_kind=kind, inner_length_mm=length,
        inner_width_mm=width, inner_height_mm=height, max_payload_kg=payload, gusset_mm=0, door_width_mm=None,
        door_height_mm=None, used_volume_mm3=volume, used_weight_kg=weight, volume_fill_pct=vpct, weight_fill_pct=wpct,
        needs_recalculation=needs_recalculation, created_at=created, last_modified_at=changed,
        last_modified_by=BY if changed else None)
    if placed:
        for b in boxes:
            add("fulfillment.load_placement", id=uid(f"lpl:{plan['id']}:{b['id']}"), load_plan_id=plan["id"],
                load_plan_vehicle_id=vid, package_id=b["id"], x_mm=b["x"], y_mm=b["y"], z_mm=b["z"], w_mm=b["w"],
                h_mm=b["h"], d_mm=b["d"], orientation=orientation_of(b["dims"], b["w"], b["h"], b["d"]),
                created_at=created)
    return vid


def vehicle_load(key, d, plan_vehicle_id, status, members, loaded=(), booked=None, loading=None, **extra):
    """A PLANNED dispatch load per vehicle of a confirmed plan (LoadPlanService.openVehicleLoads), booked and loaded
    as DispatchLoad.book / startLoading / scan leave it. members: (shipment id, added at, removed at or None);
    loaded: (package id, shipment id, loaded at, removed at or None). The counts are the active ones."""
    lid = uid(f"ld:{key}")
    booked = booked or {}
    row = add("fulfillment.dispatch_load", id=lid, code=next_no("LD", d), request_id=uid(f"ld:{key}:request"),
              warehouse_id=WH[FTL_WH], mode="FULL_TRUCKLOAD", carrier_id=booked.get("carrier"),
              load_plan_vehicle_id=plan_vehicle_id, vehicle_plate=booked.get("plate"),
              driver_name=booked.get("driver"), driver_phone=booked.get("phone"), status=status,
              booked_at=booked.get("at"), booked_by=DISPATCHER if booked else None,
              loading_started_at=loading, loading_started_by=DISPATCHER if loading else None,
              dispatched_at=None, dispatched_by=None, carrier_representative=None, signature_ref=None,
              handover_note=None, shipment_count=sum(1 for m in members if m[2] is None),
              package_count=sum(1 for p in loaded if p[3] is None), cod_total=0, cancelled_at=None,
              cancelled_by=None, cancel_reason=None, booking_cancelled_at=None, booking_cancelled_by=None,
              booking_cancel_reason=None, created_at=members[0][1],
              last_modified_at=loading or booked.get("at"), last_modified_by=BY)
    row.update(extra)
    for ship_id, added, removed in members:
        add("fulfillment.dispatch_load_shipment", id=uid(f"lds:{lid}:{ship_id}"), load_id=lid, shipment_id=ship_id,
            active=removed is None, added_at=added, added_by=MANAGER, removed_at=removed, removed_by=None,
            label_cod_amount=0, cod_amount=None, mode="FULL_TRUCKLOAD", created_at=added)
    for pkg_id, ship_id, at_, removed in loaded:
        add("fulfillment.dispatch_load_package", id=uid(f"ldp:{lid}:{pkg_id}"), load_id=lid, package_id=pkg_id,
            shipment_id=ship_id, scan_id=uid(f"ldp:{lid}:{pkg_id}:scan"), loaded_at=at_, loaded_by=DISPATCHER,
            active=removed is None, removed_at=removed, removed_by=None, created_at=at_)
    return row


VTP = carrier_ref("Viettel Post")


def along(parcels, code):
    """Pallets one behind the other on the floor, turned WLH (length across, width along z) - how the planner lays a
    1 200 x 1 000 mm pallet in a box truck narrower than two of them."""
    out, z = [], 0
    for p in parcels:
        w, h, d = extents(p["dims"], "WLH")
        out.append(dict(p, x=0, y=0, z=z, w=w, h=h, d=d))
        z += d
    check_vehicle(code, out)
    return out


# --- LP A: DISPATCHED (FTL-1, FTL-2 on one TRUCK-2T5) ------------------------------------------------------------
fa = FTL["ftl1"]["parcels"] + FTL["ftl2"]["parcels"]
gone = FTL["ftl1"]["o"]["when"]["SHIPPED"]
assert gone == FTL["ftl2"]["o"]["when"]["SHIPPED"]
a_made = FTL["ftl2"]["ready"] + timedelta(minutes=30)
a_calc, a_conf, a_book = a_made + timedelta(minutes=5), a_made + timedelta(minutes=40), a_made + timedelta(hours=2)
a_load = gone - timedelta(hours=1)
plan_a = plan_header("a", a_made.date(), a_made, "DISPATCHED", "SINGLE_TYPE", ["TRUCK-2T5", "TRUCK-5T"],
                     note="Giao nguyên chuyến cho Hoa Tươi và Trà Sữa Mây - nội thành Hà Nội",
                     calculated_at=a_calc, calculated_by=DISPATCHER,
                     solver_meta=solver_meta("GRASP_KIT", "TRUCK-2T5", fa, 412), confirmed_at=a_conf,
                     confirmed_by=MANAGER, dispatched_at=gone, revised_at=gone, version=3, last_modified_at=gone,
                     last_modified_by=BY)
plan_members(plan_a, fa, a_made, DISPATCHER)
va = plan_vehicle(plan_a, 1, "TRUCK-2T5", along(fa, "TRUCK-2T5"), a_calc)
load_a = vehicle_load("a", a_conf.date(), va, "LOADING",
                      [(FTL["ftl1"]["ship"]["id"], a_conf, None), (FTL["ftl2"]["ship"]["id"], a_conf, None)],
                      [(p["id"], p["ship"], a_load + timedelta(minutes=5 + 4 * k), None) for k, p in enumerate(fa)],
                      booked=dict(carrier=VTP, plate="29H-512.34", driver="Nguyễn Văn Hùng", phone="0983456721",
                                  at=a_book), loading=a_load)
# 09 §4.1 step 3: both sides sign, the load leaves; its shipments are handed over with it (their last vehicle, LP-3),
# the COD the handover fixes is nothing (a full truckload carries none, LP-2).
finish_later("fulfillment.dispatch_load", load_a, status="DISPATCHED", dispatched_at=gone, dispatched_by=DISPATCHER,
             carrier_representative="Nguyễn Văn Hùng", signature_ref=f"BBGN-{load_a['code']}",
             handover_note="Đủ 4 pallet, niêm phong nguyên vẹn", last_modified_at=gone)
for key in ("ftl1", "ftl2"):
    f = FTL[key]
    o, ship = f["o"], f["ship"]
    POST.append(("fulfillment.dispatch_load_package", f"UPDATE fulfillment.dispatch_load_shipment SET cod_amount = 0 "
                 f"WHERE load_id = {q(load_a['id'])} AND shipment_id = {q(ship['id'])};"))
    ship.update(status="READY_TO_DISPATCH", dispatched_at=None, handed_over_at=None, handed_over_by=None,
                whole_order_handover=False, load_id=None)
    finish_later("fulfillment.shipment", ship, status="HANDED_OVER", dispatched_at=gone, handed_over_at=gone,
                 handed_over_by=DISPATCHER, carrier_id=VTP, load_id=load_a["id"])
    # The order knows the shipment from the handover on (OrderService.recordHandover): what went, at its prices.
    o["row"]["goods_left_at"] = gone
    add("ordering.order_shipment", id=ship["id"], order_id=o["id"], status="HANDED_OVER", amount=o["total"],
        billed_amount=0, handed_over_at=gone, handed_over_by=DISPATCHER, outcome_at=None, outcome_by=None,
        failure_reason=None, failure_note=None, cod_amount=0, shipment_code=ship["code"], package_count=2,
        carrier_name="Viettel Post", delivery_mode="FULL_TRUCKLOAD", created_at=gone)
    for ln in o["lines"]:
        add("ordering.order_shipment_line", shipment_id=ship["id"], order_line_id=ln["id"], quantity=ln["qty"])

# --- LP B: CONFIRMED, vehicle 1 being loaded, vehicle 2 booked (after a booking that fell through) -------------
p3, p4, p5 = FTL["ftl3"]["parcels"], FTL["ftl4"]["parcels"], FTL["ftl5"]["parcels"]
b_made = max(FTL[k]["ready"] for k in ("ftl3", "ftl4", "ftl5")) + timedelta(hours=1)
b_calc, b_conf = b_made + timedelta(minutes=5), b_made + timedelta(minutes=40)
b_book1, b_book2 = b_made + timedelta(hours=2), b_made + timedelta(hours=2, minutes=20)
b_rebook, b_load = b_book2 + timedelta(hours=1, minutes=30), at(b_made.date() + timedelta(days=1), 8)
v1_boxes, v2_boxes = along(p3 + p4[:1], "TRUCK-1T25"), along(p4[1:] + p5, "TRUCK-1T25")
plan_b = plan_header("b", b_made.date(), b_made, "CONFIRMED", "SINGLE_TYPE", ["TRUCK-1T25"],
                     calculated_at=b_calc, calculated_by=DISPATCHER,
                     solver_meta=solver_meta("GRASP_KIT", "TRUCK-1T25", p3 + p4 + p5, 655), confirmed_at=b_conf,
                     confirmed_by=MANAGER, revised_at=b_conf, version=2, last_modified_at=b_conf,
                     last_modified_by=BY)
plan_members(plan_b, p3 + p4 + p5, b_made, DISPATCHER)
vb1 = plan_vehicle(plan_b, 1, "TRUCK-1T25", v1_boxes, b_calc)
vb2 = plan_vehicle(plan_b, 2, "TRUCK-1T25", v2_boxes, b_calc)
s3, s4, s5 = (FTL[k]["ship"]["id"] for k in ("ftl3", "ftl4", "ftl5"))
# Vehicle 1 arrived this morning: FTL-3's two pallets are on board, FTL-4's first is still to scan.
vehicle_load("b1", b_conf.date(), vb1, "LOADING", [(s3, b_conf, None), (s4, b_conf, None)],
             [(b["id"], b["ship"], b_load + timedelta(minutes=5 + 4 * k), None) for k, b in enumerate(v1_boxes[:2])],
             booked=dict(carrier=VTP, plate="29H-678.90", driver="Trần Minh Đức", phone="0912876543", at=b_book1),
             loading=b_load)
# Vehicle 2's first truck was called off by the haulier (DispatchLoad.cancelBooking: back to PLANNED, the reason kept)
# and another one booked: the last cancellation stays on the row next to the new booking.
vehicle_load("b2", b_conf.date(), vb2, "VEHICLE_BOOKED", [(s4, b_conf, None), (s5, b_conf, None)],
             booked=dict(carrier=VTP, plate="29H-345.12", driver="Phạm Quốc Bảo", phone="0936112233", at=b_rebook),
             booking_cancelled_at=b_book2 + timedelta(hours=1), booking_cancelled_by=DISPATCHER,
             booking_cancel_reason="Nhà xe báo xe 29H-201.55 hỏng, đặt xe khác cùng hãng")

# --- LP D: CANCELLED in DRAFT (FTL-6 alone), LP C: PLANNED (FTL-6 + FTL-7) ------------------------------------------
p6, p7 = FTL["ftl6"]["parcels"], FTL["ftl7"]["parcels"]
d_made = max(FTL["ftl6"]["ready"], FTL["ftl7"]["ready"]) + timedelta(hours=1)
d_cancel = d_made + timedelta(hours=1, minutes=30)
plan_d = plan_header("d", d_made.date(), d_made, "CANCELLED", "SINGLE_TYPE", ["TRUCK-8T"],
                     cancelled_at=d_cancel, cancelled_by=DISPATCHER,
                     cancel_reason="Gộp với đơn của Nhà hàng Sen cho đủ một chuyến, đổi sang xe nhỏ",
                     revised_at=d_cancel, version=1, last_modified_at=d_cancel, last_modified_by=BY)
plan_members(plan_d, p6, d_made, DISPATCHER, at=d_cancel, reason="PLAN_CANCELLED")
c_made = d_cancel + timedelta(minutes=10)
c_calc1, c_inputs, c_calc2 = c_made + timedelta(minutes=5), c_made + timedelta(minutes=20), c_made + timedelta(minutes=22)
plan_c = plan_header("c", c_made.date(), c_made, "PLANNED", "FEWEST_VEHICLES", ["TRUCK-1T25", "TRUCK-2T5"],
                     calculated_at=c_calc2, calculated_by=DISPATCHER,
                     solver_meta=solver_meta("GRASP_KIT_FLEET", "TRUCK-2T5", p6 + p7, 1873), revised_at=c_calc2,
                     version=3, last_modified_at=c_calc2, last_modified_by=BY)
plan_members(plan_c, p6 + p7, c_made, DISPATCHER)
# The first calculation (SINGLE_TYPE, TRUCK-1T25 only) needed two vehicles; changing the mode and the types sent the
# plan back to DRAFT and retired them (LoadPlan.backToDraft) - their placements were deleted, the rows stay.
plan_vehicle(plan_c, 1, "TRUCK-1T25", along(p6 + p7[:1], "TRUCK-1T25"), c_calc1, active=False, placed=False,
             changed=c_inputs)
plan_vehicle(plan_c, 2, "TRUCK-1T25", along(p7[1:], "TRUCK-1T25"), c_calc1, active=False, placed=False,
             changed=c_inputs)
plan_vehicle(plan_c, 1, "TRUCK-2T5", along(p6 + p7, "TRUCK-2T5"), c_calc2)

# --- LP E: DRAFT, vehicle types chosen, no shipment yet -----------------------------------------------------------
e_made = at(TODAY, 8, 30)
plan_header("e", TODAY, e_made, "DRAFT", "FEWEST_VEHICLES", ["TRUCK-8T", "TRUCK-15T"],
            note="Chuyến tuần sau cho các đại lý phía Bắc - chờ đơn đóng gói xong")

# --- load estimates (release dialog) -------------------------------------------------------------------------------
# LoadEstimateService: the packaging-spec resolver (PackagingRules) gives each order line its cartons - the default
# shipping unit, else the largest packing unit, else the item's box - and the planner lays them in the chosen types.
DEFAULT_PALLET = {sku: (per, (l_ * 10, w_ * 10, h_ * 10)) for sku, per, (l_, w_, h_), flag, _ in PALLET_DEMO if flag}
assert s_c.sku not in {sku for _, _, _, _, spec in FTL_ORDERS for sku, _ in spec}  # its CTN20 unit is not modelled


def estimate_line(ln):
    """PackagingRules.resolve for the units this data set gives the SKUs used here: the pallet flagged as default
    shipping unit (level 1) or the item's box (level 3: package sizes and weight, one box per unit)."""
    v = VAR[ln["sku"]]
    if ln["sku"] in DEFAULT_PALLET:
        per, dims = DEFAULT_PALLET[ln["sku"]]
        assert ln["qty"] % per == 0
        return dict(rule="DEFAULT_SHIPPING_UNIT", uom="PALLET", factor=per, cartons=ln["qty"] // per, dims=dims,
                    kg=round(per * v["dims"][0] + 25, 3))
    w, l_, wd, h = v["dims"]
    return dict(rule="ITEM_BOX", uom=v["uom"], factor=1, cartons=ln["qty"], dims=(l_ * 10, wd * 10, h * 10), kg=w)


def estimate(key, f, n, status, mode, code, types, made, superseded=None):
    """One stored estimate: lines, the viewer's layout (Layouts.of) and the planner's meta, as LoadEstimateService
    writes them through the application's ObjectMapper (non_null: absent fields are left out)."""
    o = f["o"]
    lines, groups = [], []
    for ln in o["lines"]:
        r = estimate_line(ln)
        lines.append({"orderLineId": ln["id"], "sku": ln["sku"], "inventoryItemId": ITEM[ln["sku"]],
                      "quantity": ln["qty"], "rule": r["rule"], "uom": r["uom"], "factor": r["factor"],
                      "cartons": r["cartons"], "dimsMm": dict(zip("lwh", r["dims"])), "weightKg": r["kg"],
                      "rotatable": True, "kitParts": 1})
        groups.append([dict(id=f"{ln['id']}#{k + 1}", source=ln["id"], sku=ln["sku"], dims=r["dims"], kg=r["kg"])
                       for k in range(r["cartons"])])
    vehicles = pack(code, groups)
    layouts = []
    for seq, boxes in enumerate(vehicles, 1):
        _, length, width, height, payload = VEHICLE_TYPE[code]
        order = load_sequence(boxes)
        volume, weight, vpct, wpct = usage(code, boxes)
        layouts.append({"seq": seq, "code": code, "vehicleTypeId": vehicle_type_ref(code),
                        "innerMm": {"l": length, "w": width, "h": height}, "maxPayloadKg": float(payload),
                        "gussets": [],
                        "boxes": [{"xMm": b["x"], "yMm": b["y"], "zMm": b["z"], "wMm": b["w"], "hMm": b["h"],
                                   "dMm": b["d"], "ref": b["id"], "sourceRef": b["source"], "label": b["sku"],
                                   "partName": b["sku"], "origMm": dict(zip("lwh", b["dims"])),
                                   "orientation": ORIENTATION[orientation_of(b["dims"], b["w"], b["h"], b["d"])][0],
                                   "rotatable": True, "weightKg": b["kg"], "loadSequence": s}
                                  for s, b in sorted(zip(order, boxes), key=lambda x: x[0])],
                        "usedVolumeMm3": volume, "usedWeightKg": weight, "volumeFillPct": vpct,
                        "weightFillPct": wpct})
    placed = sum(len(v) for v in vehicles)
    total = sum(b["dims"][0] * b["dims"][1] * b["dims"][2] for g in groups for b in g)
    _, length, width, height, _ = VEHICLE_TYPE[code]
    meta = {"seed": 0, "budgetMillis": 5000, "algorithm": "GRASP_KIT_FLEET" if mode == "FEWEST_VEHICLES" else "GRASP_KIT",
            "setsTotal": 0, "minVehicles": -(-total // (length * width * height)), "budgetExhausted": False,
            "elapsedMillis": 180 + 37 * placed, "bestFoundMillis": 60 + 11 * placed, "placedBoxes": placed,
            "unplacedBoxes": 0, "kitBalance": [{} for _ in vehicles], "kitWarnings": []}
    add("fulfillment.load_estimate", id=uid(f"est:{key}:{n}"), source_type="ORDER", source_id=o["id"],
        warehouse_id=WH[FTL_WH], vehicle_type_ids=[vehicle_type_ref(t) for t in types], mode=mode, status=status,
        lines=lines, layout={"vehicles": layouts, "unplaced": [], "meta": meta}, warnings=[], missing=[],
        incomplete=False, version=1 if superseded else 0, created_at=made, last_modified_at=superseded,
        last_modified_by=BY if superseded else None)


for key, plans in (("ftl1", [("SINGLE_TYPE", "TRUCK-1T25", ["TRUCK-1T25", "TRUCK-2T5"])]),
                   ("ftl4", [("SINGLE_TYPE", "TRUCK-1T25", ["TRUCK-1T25"]),
                             ("FEWEST_VEHICLES", "TRUCK-2T5", ["TRUCK-1T25", "TRUCK-2T5"])]),
                   ("ftl6", [("SINGLE_TYPE", "TRUCK-1T25", ["TRUCK-1T25"])])):
    release = FTL[key]["o"]["when"]["READY_TO_FULFILL"]
    times = [release - timedelta(minutes=15 * (len(plans) - n)) for n in range(len(plans))]
    for n, (mode, code, types) in enumerate(plans):
        last = n == len(plans) - 1
        estimate(key, FTL[key], n, "CURRENT" if last else "SUPERSEDED", mode, code, types, times[n],
                 superseded=None if last else times[n + 1])


# ============================================================================ 11. stock rows and ledger
for s in STOCK:
    assert 0 <= s.reserved <= s.on_hand, (s.sku, s.loc, s.on_hand, s.reserved)
    add("inventory.stock_item", id=s.id, sku=s.sku, location_code=s.loc, lot_number=s.lot, expiry_date=s.expiry,
        on_hand=s.on_hand, reserved=s.reserved, status=s.status, received_at=s.received, serial_number=None,
        stock_package_id=getattr(s, "pkg", None), created_at=s.received)
for m in MOVES:
    ROWS.setdefault("inventory.stock_movement", []).append({**m, "created_by": BY})
for issue in ISSUES:
    add("production.production_material_issue", **issue)

# ============================================================================ 12. histories, price lists, alerts, inbox
# Rows the application writes as a side effect of the work above, derived from it here so they agree with it. Nothing
# below draws from `random`: every choice is a fixed list or a computation, so the data generated before stays as it is.
UTC = timezone.utc


def instant(t):
    """java.time.Instant.toString of a whole-second time, as the dedupe keys built from an Instant carry it."""
    return t.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def reconcile_after(t):
    """The nightly AlertReconcileJob run (01:30 business time, alert-reconcile-cron) that first sees what changed at t."""
    run = at(t.astimezone(VN).date(), 1, 30)
    return run if run > t else run + timedelta(days=1)


# ---------------------------------------------------------------------------- customers' own prices (SCRUM-428)
# CustomerPriceService.create / replace, by the E-commerce Admin (catalog-customer-prices:CREATE). The repository
# replaces the tiers as a whole on every save, so a revised price's tiers carry the revision's time and the header's
# version moved once. A price list is made after the customer's last order of that SKU: an order placed later would
# have been priced from it (14 BR-03), and the seeded orders are priced from the segment / base price (unit_price).
CUSTOMER_PRICES = [
    # customer, sku, made at, tiers [(from qty, VND per unit)], note, revision (at, tiers) or None
    ("daily-tanphu", "LYG-1L-12OZ", at(date(2026, 10, 5), 10), [(20, 470000), (50, 460000), (100, 448000)],
     "Giá đại lý cấp 1 theo hợp đồng quý IV/2026",
     (at(date(2026, 10, 9), 15), [(20, 470000), (50, 458000), (100, 445000), (200, 435000)])),
    ("daily-tanphu", "LYG-1L-16OZ", at(date(2026, 10, 5), 10, 5), [(20, 552000), (50, 540000)],
     "Giá đại lý cấp 1 theo hợp đồng quý IV/2026", None),
    ("daily-tanphu", "NAP-GIAY-D90-WHITE", at(date(2026, 10, 5), 10, 10), [(20, 252000), (100, 240000)], None, None),
    ("caphemoc", "LYN-PET-12OZ", at(date(2026, 10, 8), 14), [(10, 455000), (30, 445000)],
     "Giá riêng chuỗi Cà phê Mộc từ tháng 10/2026", None),
    ("caphemoc", "LYG-2L-8OZ", at(date(2026, 10, 8), 14, 5), [(10, 615000), (40, 600000)],
     "Giá riêng chuỗi Cà phê Mộc từ tháng 10/2026", None),
    ("pizza4u", "HOP-PIZZA-12IN", at(date(2026, 10, 11), 9), [(20, 495000), (50, 485000)], None, None),
    ("banhmisaigon", "HOP-COM-M", at(date(2026, 10, 10), 9), [(10, 615000)], "Khách quen, giá ưu đãi hộp cơm size M",
     None),
]
for cust_code, sku, made, tiers, note, revision in CUSTOMER_PRICES:
    bought = [o["row"]["placed_at"] for o in ORDERS if o["cust"] == cust_code and o["row"]["placed_at"]
              and any(ln["sku"] == sku for ln in o["lines"])]
    assert not bought or max(bought) < made, (cust_code, sku, max(bought), made)
    assert all(price < unit_price(CUST[cust_code], sku) for _, price in tiers), (cust_code, sku)
    pid_ = uid(f"cprice:{cust_code}:{sku}")
    final_at, final_tiers = revision or (made, tiers)
    add("catalog.customer_price", id=pid_, customer_id=CUST[cust_code]["id"], sku=sku, currency="VND", note=note,
        version=1 if revision else 0, created_at=made, last_modified_at=final_at if revision else None,
        last_modified_by=BY if revision else None)
    for qty_from, price in final_tiers:
        add("catalog.customer_price_tier", id=uid(f"cprice:{cust_code}:{sku}:{qty_from}:{final_at.isoformat()}"),
            customer_price_id=pid_, min_quantity=qty_from, unit_price=price, version=0, created_at=final_at)

# ---------------------------------------------------------------------------- sales units (kltn-docs 01 BR-11)
# SkuUnitService.addSalesUnit for the four SKUs that have a pallet unit (PALLET_DEMO): listing units replaces the
# default (base unit only), so the carton - the base unit, sold at the catalog base price, hence no price of its own -
# is listed first and the seeded carton orders stay valid; the pallet is priced per pallet, whole VND. One pallet price
# was changed later (changeSalesUnit: version + 1, and a history entry naming the price).
UNITS_LISTED = at(date(2026, 10, 5), 9)
PALLET_RECUT = ("LYG-1L-12OZ", at(date(2026, 10, 9), 15, 30), 0.94)
SALES_UNIT_LOG = []   # (sku, unit, when, changed fields) for the product history below
for k, (sku, per_pallet, *_rest) in enumerate(PALLET_DEMO):
    v = VAR[sku]
    listed = UNITS_LISTED + timedelta(minutes=2 * k)
    add("product.variant_sales_units", id=uid(f"vsu:{sku}:CTN"), variant_id=v["id"],
        uom_conversion_id=Raw("(SELECT c.id FROM inventory.uom_conversions c JOIN inventory.inventory_items i "
                              f"ON i.id = c.inventory_item_id WHERE i.sku = {q(sku)} AND c.is_base)"),
        price=None, version=0, created_at=listed)
    SALES_UNIT_LOG.append((sku, "CTN", listed, ["unit:CTN"]))
    price = money(per_pallet * v["price"] * 0.95 / 1000) * 1000
    row = add("product.variant_sales_units", id=uid(f"vsu:{sku}:PALLET"), variant_id=v["id"],
              uom_conversion_id=uid("uom:" + sku + ":PALLET"), price=price, version=0,
              created_at=listed + timedelta(minutes=1))
    SALES_UNIT_LOG.append((sku, "PALLET", listed + timedelta(minutes=1), ["unit:PALLET"]))
    if sku == PALLET_RECUT[0]:
        row.update(price=money(per_pallet * v["price"] * PALLET_RECUT[2] / 1000) * 1000, version=1,
                   last_modified_at=PALLET_RECUT[1], last_modified_by=BY)
        SALES_UNIT_LOG.append((sku, "PALLET", PALLET_RECUT[1], ["unit:PALLET", "price"]))

# ---------------------------------------------------------------------------- product history (SCRUM-461)
# ProductChangeLogRepository.record, called in the transaction of each change; append-only. Entries for the changes
# the catalogue rows above already show (their timestamps and reasons): the two products still in the approval flow
# from creation on, the discontinued straw (ProductServiceImpl.discontinue: every variant to OBSOLETE first, then the
# product, both with the product's reason), the blocked canvas-bag variant (ProductVariantService.block: no reason),
# and the sales units. A product created with axes logs CREATED, then VARIANT_ADDED per generated variant (DRAFT);
# the later entry gets the later second so newest-first reads in the order it happened.
SUBJECT = {"CREATED": "PRODUCT", "UPDATED": "PRODUCT", "SUBMITTED": "PRODUCT", "DISCONTINUED": "PRODUCT",
           "VARIANT_ADDED": "VARIANT", "VARIANT_STATUS": "VARIANT", "SALES_UNIT": "SALES_UNIT"}
VARIANT_BLOCKED = ("TUI-VAI-M", at(date(2026, 9, 18), 10))


def product_log(pcode, change, when, actor, subject_id=None, frm=None, to=None, reason=None, fields=()):
    add("product.product_change_log", id=uid(f"pcl:{pcode}:{change}:{subject_id}:{when.isoformat()}:{fields}"),
        product_id=PROD[pcode]["id"], change_type=change, subject_type=SUBJECT[change], subject_id=subject_id,
        from_status=frm, to_status=to, reason=reason, changed_fields=tuple(fields), actor_id=U[actor],
        occurred_at=when)


for pcode in ("HOP-SALAD", "LYG-TET"):
    assert PROD[pcode]["status"] in ("PENDING_APPROVAL", "DRAFT"), pcode
    product_log(pcode, "CREATED", P_CREATED, EDITOR, to="DRAFT")
    for v in VARIANTS:
        if v["product"] == pcode:
            product_log(pcode, "VARIANT_ADDED", P_CREATED + timedelta(seconds=1), EDITOR, v["id"], to="DRAFT")
    if PROD[pcode]["status"] == "PENDING_APPROVAL":
        # submitted_at / submitted_by of the product row; the copy was reworded before it went to review.
        product_log(pcode, "UPDATED", at(date(2026, 8, 3), 9, 40), EDITOR,
                    fields=("shortDescription", "description"))
        product_log(pcode, "SUBMITTED", at(date(2026, 8, 3), 10), EDITOR, frm="DRAFT", to="PENDING_APPROVAL")
gone = next(p for p in PRODUCTS if p[6] == "DISCONTINUED")
gone_at, gone_why = at(date(2026, 9, 30), 17), "Ngừng sản xuất, chuyển sang ống hút giấy"   # the product row's values
for v in VARIANTS:
    if v["product"] == gone[0]:
        product_log(gone[0], "VARIANT_STATUS", gone_at, EDITOR, v["id"], frm="ACTIVE", to="OBSOLETE", reason=gone_why)
product_log(gone[0], "DISCONTINUED", gone_at + timedelta(seconds=1), EDITOR, frm="PUBLISHED", to="DISCONTINUED",
            reason=gone_why)
assert VAR[VARIANT_BLOCKED[0]]["vstatus"] == "BLOCKED"
product_log(VAR[VARIANT_BLOCKED[0]]["product"], "VARIANT_STATUS", VARIANT_BLOCKED[1], EDITOR,
            VAR[VARIANT_BLOCKED[0]]["id"], frm="ACTIVE", to="BLOCKED")
for sku, unit, when, fields in SALES_UNIT_LOG:
    product_log(VAR[sku]["product"], "SALES_UNIT", when, EDITOR, VAR[sku]["id"], fields=fields)

# ---------------------------------------------------------------------------- customer onboarding (SCRUM-450)
# CustomerOnboardingService.create: Sales creates the customer PENDING_ACTIVATION with a portal account for its contact
# (PortalAccountServiceImpl.invite: username = e-mail, lower case; no password until activation; CUSTOMER role) and an
# invitation (72 h, stockflow.identity.portal-invitation.lifetime) whose e-mail outcome is recorded after the commit.
# Only the token's SHA-256 is stored, so the digests here open nothing. Four customers onboarded this way show the
# invitation states the back office distinguishes: activated, pending, expired after a resend, and closed unactivated.
# The customers seeded in section 5 were created directly (no onboarding), which writes no status history.
INVITE_LIFETIME = timedelta(hours=72)


def onboard(key, company, contact, phone, by, when):
    email = f"lienhe@{key}.example"
    U["kh." + key] = uid("user:kh." + key)
    user_row = add("identity.app_user", id=U["kh." + key], username=email, email=email, password_hash=None,
                   full_name=contact, status="ACTIVE", created_at=when)
    add("identity.user_role", id=uid(f"ur:kh.{key}:CUSTOMER"), user_id=U["kh." + key], role_id=ROLE("CUSTOMER"),
        created_at=when)
    cust_row = add("customer.customer", id=uid("customer:" + key), full_name=company, email=email, phone=phone,
                   segment_id=None, status="PENDING_ACTIVATION", user_id=U["kh." + key], tax_code=None,
                   created_at=when)
    customer_status(key, None, "PENDING_ACTIVATION", None, by, when)
    return user_row, cust_row


def invite(key, n, issued, by, delivery, consumed=None, revoked=None):
    """One invitation; its version counts the saves after the insert (delivery outcome, consumption, revocation)."""
    changes = [t for t in (issued + timedelta(seconds=2), consumed, revoked) if t]
    add("identity.portal_invitation", id=uid(f"invite:{key}:{n}"), user_id=U["kh." + key],
        token_digest=sha(f"invite-token:{key}:{n}"), issued_at=issued, expires_at=issued + INVITE_LIFETIME,
        consumed_at=consumed, revoked_at=revoked, issued_by=U[by], delivery_status=delivery,
        delivery_attempted_at=issued + timedelta(seconds=2), version=len(changes), created_at=issued,
        last_modified_at=max(changes), last_modified_by=BY)


def customer_status(key, frm, to, reason, by, when):
    """CustomerStatusHistory.record: from NULL at onboarding, by the contact's own account at activation."""
    add("customer.customer_status_history", id=uid(f"cstat:{key}:{to}:{when.isoformat()}"),
        customer_id=uid("customer:" + key), from_status=frm, to_status=to, reason=reason, changed_by=by,
        changed_at=when, version=0, created_at=when)


def customer_change(key, field, old, new, by, when):
    """CustomerServiceImpl.update: a renamed company (the contact, PUT /me) or a tax code Sales records."""
    add("customer.customer_change_history", id=uid(f"cchg:{key}:{field}:{when.isoformat()}"),
        customer_id=uid("customer:" + key), field=field, old_value=old, new_value=new, changed_by=by,
        changed_at=when, created_at=when)


def touched(row, version, when):
    row.update(version=version, last_modified_at=when, last_modified_by=BY)


# Activated, then the tax code recorded (a household business supplying it later, decision 2026-10-12) and the
# company renamed by its contact.
t0 = at(date(2026, 10, 6), 10)
user_row, cust_row = onboard("tiemtraan", "Tiệm trà An Nhiên", "Phùng Thị An", "0912340601", U["sales1"], t0)
activated = at(date(2026, 10, 6), 20, 15)
invite("tiemtraan", 1, t0, "sales1", "SENT", consumed=activated)
customer_status("tiemtraan", "PENDING_ACTIVATION", "ACTIVE", None, U["kh.tiemtraan"], activated)
user_row.update(password_hash=PASSWORD, last_login_at=at(date(2026, 10, 11), 19, 40))
touched(user_row, 1, activated)
customer_change("tiemtraan", "TAX_CODE", None, "8012345678", U["sales1"], at(date(2026, 10, 7), 9))
customer_change("tiemtraan", "FULL_NAME", "Tiệm trà An Nhiên", "Hộ kinh doanh Tiệm trà An Nhiên",
                U["kh.tiemtraan"], at(date(2026, 10, 8), 20))
cust_row.update(status="ACTIVE", tax_code="8012345678", full_name="Hộ kinh doanh Tiệm trà An Nhiên")
touched(cust_row, 3, at(date(2026, 10, 8), 20))

# Pending: invited the day before "today", the link still open.
t0 = at(date(2026, 10, 11), 14)
onboard("xuongbanhmoclan", "Công ty TNHH Xưởng bánh Mộc Lan", "Đinh Mộc Lan", "0912340602", U["sales2"], t0)
invite("xuongbanhmoclan", 1, t0, "sales2", "SENT")

# The first e-mail failed; Sales resent it (the old link revoked when the new one was issued) and that one expired
# unused: the back office shows EXPIRED and offers another resend.
t0 = at(date(2026, 10, 7), 9, 30)
onboard("quancomnha", "Quán Cơm Nhà Bà Sáu", "Lê Thị Sáu", "0912340603", U["sales1"], t0)
resent = at(date(2026, 10, 8), 8, 45)
invite("quancomnha", 1, t0, "sales1", "FAILED", revoked=resent)
invite("quancomnha", 2, resent, "sales1", "SENT")

# Never activated; the E-commerce Admin closed it (customer-status:APPROVE). Closing suspends the portal account
# (syncPortalAccess -> PortalAccountService.suspend: ACTIVE -> DISABLED); the expired invitation is left as it was.
t0 = at(date(2026, 9, 22), 15)
user_row, cust_row = onboard("taphoabinhminh", "Cửa hàng tạp hóa Bình Minh", "Trương Văn Minh", "0912340604",
                             U["sales2"], t0)
invite("taphoabinhminh", 1, t0, "sales2", "SENT")
closed = at(date(2026, 10, 1), 11)
customer_status("taphoabinhminh", "PENDING_ACTIVATION", "INACTIVE", "Khách không kích hoạt tài khoản sau 7 ngày, "
                "đóng hồ sơ theo yêu cầu của khách", U["ecom2"], closed)
cust_row.update(status="INACTIVE")
touched(cust_row, 1, closed)
user_row.update(status="DISABLED")
touched(user_row, 1, closed)

# ---------------------------------------------------------------------------- staff status changes, renames
# CustomerServiceImpl.changeStatus by the E-commerce Admin, on customers without a portal account (nothing to suspend):
# the blocked and the closed customer of section 5, a block lifted (a reason is required, decision 2026-10-17) and a
# closed customer reopened (reason required, ck_customer_status_history_reopen_reason). No order falls in a time the
# customer was blocked or closed, which it could not have placed then; one reopened may order again.
STATUS_CHANGES = [
    # customer, [(from, to, reason, at)]
    ("bichan", [("ACTIVE", "BLOCKED", "Nợ quá hạn trên 60 ngày, nhiều lần không nhận hàng COD",
                 at(date(2026, 9, 15), 16))]),
    ("ngungban", [("ACTIVE", "INACTIVE", "Khách ngừng kinh doanh, yêu cầu đóng hồ sơ", at(date(2026, 9, 20), 10))]),
    ("trasuanho", [("ACTIVE", "BLOCKED", "Nghi ngờ đặt đơn giả mạo, tạm khóa chờ xác minh", at(date(2026, 9, 30), 9)),
                   ("BLOCKED", "ACTIVE", "Đã xác minh với chủ quán qua điện thoại, mở khóa", at(date(2026, 10, 2), 14))]),
    ("kemtuoi", [("ACTIVE", "INACTIVE", "Cửa hàng tạm nghỉ hết mùa hè", at(date(2026, 9, 25), 10)),
                 ("INACTIVE", "ACTIVE", "Khách mở lại cửa hàng, xác nhận qua điện thoại", at(date(2026, 10, 3), 9))]),
]
CUSTOMER_ROWS = {r["id"]: r for r in ROWS["customer.customer"]}
for key, steps in STATUS_CHANGES:
    assert CUST[key]["user"] is None, key
    assert CUSTOMER_ROWS[CUST[key]["id"]]["status"] == steps[-1][1], key
    # No order placed while the customer was blocked or closed (until a later step made it ACTIVE again).
    placed = [o["row"]["placed_at"] for o in ORDERS if o["cust"] == key and o["row"]["placed_at"]]
    for k, (frm, to, reason, when) in enumerate(steps):
        if to != "ACTIVE":
            until = steps[k + 1][3] if k + 1 < len(steps) else None
            assert not [t for t in placed if t >= when and (until is None or t < until)], (key, to, when)
    for frm, to, reason, when in steps:
        customer_status(key, frm, to, reason, U["ecom2"], when)
    touched(CUSTOMER_ROWS[CUST[key]["id"]], len(steps), steps[-1][3])
# A chain renamed by its contact soon after its account was opened (the name before the change is kept).
renamed_at = CUSTOMER_ROWS[CUST["comtamcali"]["id"]]["created_at"] + timedelta(days=1, hours=5)
customer_change("comtamcali", "FULL_NAME", "Cơm tấm Cali", CUST["comtamcali"]["company"], CUST["comtamcali"]["user"],
                renamed_at)
touched(CUSTOMER_ROWS[CUST["comtamcali"]["id"]], 1, renamed_at)

# ---------------------------------------------------------------------------- stock alerts (SCRUM-147/165)
# What StockAlertEvaluator and the nightly AlertReconciler leave on this stock: a SKU is watched while it may be bought
# again (ReplenishmentEligibility: ACTIVE variant of an APPROVED / PUBLISHED product); REORDER when usable on-hand
# (AVAILABLE, not expired, reservations not subtracted) <= reorder_point, SAFETY when < safety_stock (thresholds of
# section 4's inventory items). The first reconcile after the opening stock was put away found four SKUs that never had
# stock: the two mooncake boxes are still low (open, two acknowledged by the planner), the straw and the canvas bag
# closed NOT_PURCHASABLE at the reconcile after they were discontinued / blocked. No EXPIRING or EXPIRED alert: every
# lot of the seed expires in 2027 (expiry-warning-days 30) and none ever ran out, so the sweep (ExpirySweeper) would
# open none and close any it found.
def thresholds_of(v):
    return (20, 8) if "lot" in v["flags"] else (40, 15)   # reorder_point, safety_stock as set in section 4


def usable_at(sku, t):
    total = 0
    for m in MOVES:
        if m["sku"] == sku and m["occurred_at"] < t:
            total += m["quantity"] if m["to_location_code"] and m["to_status"] == "AVAILABLE" else 0
            total -= m["quantity"] if m["from_location_code"] and m["from_status"] == "AVAILABLE" else 0
    return total


def usable_now(sku):
    return sum(s.on_hand for s in STOCK if s.sku == sku and s.status == "AVAILABLE"
               and (s.expiry is None or s.expiry >= TODAY))


def low(qty, v):
    reorder, safety = thresholds_of(v)
    return [kind for kind, crossed in (("REORDER", qty <= reorder), ("SAFETY", qty < safety)) if crossed]


watched = [v for v in VARIANTS if v["vstatus"] == "ACTIVE" and v["pstatus"] in ("APPROVED", "PUBLISHED")]
# When each SKU left its alert state on the opening receipts: the last of those is when the stock was in.
first_above = []
for v in watched:
    for m in sorted((m for m in MOVES if m["sku"] == v["sku"]), key=lambda m: m["occurred_at"]):
        if not low(usable_at(v["sku"], m["occurred_at"] + timedelta(seconds=1)), v):
            first_above.append(m["occurred_at"])
            break
ALERTS_OPENED = reconcile_after(max(first_above))
# Watched then, no longer purchasable now: when that changed (the product row, the variant history above).
NOT_PURCHASABLE = {v["sku"]: gone_at for v in VARIANTS if v["product"] == gone[0]}
NOT_PURCHASABLE[VARIANT_BLOCKED[0]] = VARIANT_BLOCKED[1]
watched_then = watched + [VAR[sku] for sku in NOT_PURCHASABLE]
low_then = sorted((v for v in watched_then if low(usable_at(v["sku"], ALERTS_OPENED), v)), key=lambda v: v["sku"])
low_now = {v["sku"] for v in watched if low(usable_now(v["sku"]), v)}
assert {v["sku"] for v in low_then} == low_now | set(NOT_PURCHASABLE), (low_then, low_now)
assert all(not any(m["sku"] == v["sku"] for m in MOVES) for v in low_then), "an alerted SKU moved: observed qty changes"
ACKED = {"HOP-TRUNGTHU-S": at(ALERTS_OPENED.date(), 8, 40)}   # the planner has seen these; the rest are still OPEN
ALERT_ITEMS = {}   # kind -> [(alert id, sku)] announced together by the opening reconcile
for v in low_then:
    for kind in low(0, v):
        aid = uid(f"alert:{kind}:{v['sku']}:{ALERTS_OPENED.isoformat()}")
        ALERT_ITEMS.setdefault(kind, []).append((aid, v["sku"]))
        ended = NOT_PURCHASABLE.get(v["sku"])
        acked = ACKED.get(v["sku"])
        add("inventory.stock_alert", id=aid, kind=kind, sku=v["sku"], warehouse_id=None, lot_number=None,
            expiry_date=None, observed_qty=0, threshold_qty=thresholds_of(v)[0 if kind == "REORDER" else 1],
            reservation_impaired=False, status="RESOLVED" if ended else "ACKNOWLEDGED" if acked else "OPEN",
            opened_at=ALERTS_OPENED, last_observed_at=ALERTS_OPENED, acknowledged_by=U["kehoach"] if acked else None,
            acknowledged_at=acked, ack_note="Hộp trung thu chỉ sản xuất theo đơn in, không giữ tồn; theo dõi khi có đơn"
            if acked else None, resolved_at=reconcile_after(ended) if ended else None,
            resolution="NOT_PURCHASABLE" if ended else None, version=(1 if ended else 0) + (1 if acked else 0))

# ---------------------------------------------------------------------------- the bell (SCRUM-147, notification inbox)
# One row per recipient, posted by the notification listeners with the vi texts of i18n/messages_vi.properties and
# their dedupe keys. SYSTEM_ADMIN is also a recipient of stock alerts but the seed never refers to that account.
#  - InventoryAlertMessages: one message per kind and warehouse for what the opening reconcile announced, to the
#    INVENTORY_PLANNER (stock levels are system-wide); the planner read both that morning.
#  - ProductionNotificationListener: the press held MACHINE_DOWN goes to the WAREHOUSE_MANAGER of its warehouse,
#    unread ("today" is five days later).
ALERT_TITLE = {"REORDER": "%d SKU chạm ngưỡng đặt lại hàng", "SAFETY": "%d SKU dưới mức tồn an toàn"}
for kind, items in ALERT_ITEMS.items():
    skus = [sku for _, sku in items]
    body = "SKU: " + ", ".join(skus[:5]) + (f" và {len(skus) - 5} SKU khác" if len(skus) > 5 else "")
    posted = ALERTS_OPENED + timedelta(seconds=3)
    add("notification.user_notification", id=uid(f"inbox:{kind}:{ALERTS_OPENED.isoformat()}:kehoach"),
        recipient_id=U["kehoach"], category="INVENTORY_ALERT", severity="WARN" if kind == "REORDER" else "CRITICAL",
        title=ALERT_TITLE[kind] % len(items), body=body, link=f"/admin/inventory/alerts?kind={kind}",
        source_type="STOCK_ALERT",
        source_id=items[0][0] if len(items) == 1 else uid(f"alert-batch:{kind}:{ALERTS_OPENED.isoformat()}"),
        dedupe_key=f"{kind}:ALL:{ALERTS_OPENED.date().isoformat()}", created_at=posted,
        read_at=at(ALERTS_OPENED.date(), 8, 35))
HOLD_LABEL = {"FILE_REJECTED": "file in không đạt", "BLANKS_SHORT": "thiếu phôi", "MACHINE_DOWN": "máy hỏng",
              "WAITING_CUSTOMER": "chờ khách hàng", "SUBCONTRACTOR_LATE": "nhà gia công trễ hạn", "OTHER": "lý do khác"}
for held in (p for p in PRODUCTION if p["row"]["status"] == "ON_HOLD"):
    reason = held["row"]["hold_reason"]
    assert reason != "BLANKS_SHORT", "a BLANKS_SHORT hold also says how many blanks (production.hold.blanks)"
    audience = {"FILE_REJECTED": "SALES_STAFF", "WAITING_CUSTOMER": "SALES_STAFF",
                "BLANKS_SHORT": "PROCUREMENT_STAFF"}.get(reason, "WAREHOUSE_MANAGER")
    told = [name for name, _full, roles, whs, st in STAFF if audience in roles and st == "ACTIVE"
            and (audience != "WAREHOUSE_MANAGER" or held["p"] in whs)]
    held_at = held["times"]["ON_HOLD"]
    for name in told:
        add("notification.user_notification", id=uid(f"inbox:hold:{held['id']}:{name}"), recipient_id=U[name],
            category="PRODUCTION_HOLD", severity="WARN",
            title=f"Lệnh sản xuất {held['row']['order_number']} tạm dừng: {HOLD_LABEL[reason]}", body=None,
            link=f"/admin/production/orders/{held['id']}", source_type="PRODUCTION_ORDER", source_id=held["id"],
            dedupe_key=f"production-hold:{held['id']}:{reason}:{instant(held_at)}",
            created_at=held_at + timedelta(seconds=2), read_at=None)


# ============================================================================ emit
ORDER = """
identity.app_user identity.user_role
warehouse.warehouse warehouse.zone warehouse.shelf warehouse.shelf_level warehouse.storage_location warehouse.bin
warehouse.area warehouse.boundary warehouse.slotting_rule identity.user_warehouse identity.user_session
notification.preference customer.segment customer.customer customer.address customer.credit_profile
customer.customer_status_history customer.customer_change_history identity.portal_invitation
product.brands product.categories product.attributes product.attribute_values product.category_attributes
product.products product.product_categories product.product_attributes product.product_attribute_options
product.product_descriptive_values product.variants product.variant_attribute_values product.media
product.customization_templates product.print_areas
procurement.suppliers warehouse.subcontractor_site procurement.supplier_contacts procurement.supplier_addresses procurement.supplier_items
procurement.supplier_item_prices procurement.approval_limits
product.variant_sales_units product.product_change_log
catalog.pricing_rule catalog.promotion catalog.product_listing catalog.catalog_entry
catalog.customer_price catalog.customer_price_tier
design.design_draft design.design_artifact design.design_decision design.design_snapshot
ordering.customer_order ordering.order_line ordering.order_status_history ordering.order_hold
ordering.order_cancellation_request ordering.order_credit_check
ordering.order_partial_shipment_decision ordering.order_delivery_mode_change
ordering.quote ordering.quote_line ordering.quote_price_tier ordering.quote_status_history
production.sample_request ordering.approved_sample
production.production_order production.production_order_status_history production.production_qc_result
procurement.replenishment_proposals
procurement.purchase_orders procurement.purchase_order_revisions procurement.purchase_order_lines
procurement.purchase_order_line_revisions procurement.purchase_order_approvals procurement.purchase_order_events
procurement.purchase_order_attachments procurement.replenishment_proposal_lines procurement.po_delivery_decision
notification.po_delivery_control procurement.goods_receipts procurement.goods_receipt_lines
procurement.qc_inspections procurement.supplier_invoices procurement.supplier_invoice_lines
inventory.stock_item inventory.stock_reservation production.production_subcontract_receipt
production.production_subcontract_hold production.production_subcontract_recall procurement.subcontract_settlement
ordering.order_line_reservation ordering.order_line_production
payment.payment ordering.order_payment payment.receivable payment.customer_transfer
payment.transfer_allocation
fulfillment.wave fulfillment.pick fulfillment.pick_line fulfillment.pack fulfillment.design_verification
fulfillment.shipment fulfillment.shipment_event fulfillment.package fulfillment.package_line fulfillment.package_scan
ordering.order_shipment ordering.order_shipment_line
fulfillment.load_plan fulfillment.load_plan_vehicle_type fulfillment.load_plan_package fulfillment.load_plan_vehicle
fulfillment.load_placement fulfillment.load_estimate
fulfillment.dispatch_load fulfillment.dispatch_load_shipment fulfillment.dispatch_load_package
payment.cod_remittance payment.cod_remittance_line
inventory.cycle_count inventory.cycle_count_line inventory.stock_adjustment
inventory.stock_damage_report inventory.stock_damage_photo inventory.stock_damage_disposition
inventory.stock_damage_supplier_shipment
ordering.return_request ordering.return_request_line ordering.return_request_evidence
ordering.return_request_discrepancy ordering.return_request_status_history payment.refund
production.production_scrap
inventory.transfer_order inventory.transfer_order_line
warehouse.move_task warehouse.putaway_task inventory.stock_movement production.production_material_issue
production.production_print_run inventory.stock_alert
chat.conversation chat.message notification.template notification.delivery_log notification.user_notification
platform.audit_log
ordering.cart ordering.cart_line
""".split()
assert set(ORDER) >= set(ROWS), set(ROWS) - set(ORDER)
for t in ORDER:
    for r in ROWS.get(t, []):
        if t not in NO_AUDIT and "created_at" not in r and t not in ("catalog.product_listing",):
            r["created_at"] = at(date(2026, 8, 1), 8)

out = []
w = out.append
total_rows = sum(len(v) for v in ROWS.values())
w(f"""-- =============================================================================
-- StockFlowCommerce - TEST DATA ({total_rows:,} rows across {len(ROWS)} tables).
--
-- GENERATED by tools/db/seed/gen_seed_test_data.py - edit the generator, not this file.
--
-- Run on a database migrated with db/migration (any profile). Not a Flyway migration and not part of
-- db/demo: nothing loads it automatically.
--     psql -v ON_ERROR_STOP=1 -h <host> -U stockflow -d stockflow -f tools/db/seed/seed_test_data.sql
--
-- * One transaction: it loads completely or not at all, and refuses to run twice. A database holding an older
--   version of this seed is reset first (deploy/README.md "Reset the database"), then migrated and loaded.
-- * The design files and evidence photos it names are in tools/db/seed/files/ (key = path), real files whose
--   SHA-256 the rows carry: upload them to the bucket too, or prepress, QC and packing refuse the designs as changed
--   and the damage-report and return photos have nothing behind their links.
--     local:  docker cp tools/db/seed/files stockflow-minio:/tmp/seed-files && docker exec stockflow-minio sh -c \\
--               'mc alias set local http://localhost:9000 stockflow stockflow123 >/dev/null &&
--                mc mirror --overwrite /tmp/seed-files local/stockflow && rm -rf /tmp/seed-files'
--     VPS:    scp -r tools/db/seed/files deploy@<vps-ip>:/tmp/seed-files, then in /opt/stockflow:
--             docker compose cp /tmp/seed-files minio:/tmp/seed-files && docker compose exec -T minio sh -c \\
--               "mc alias set local http://localhost:9000 \\"\\$MINIO_ROOT_USER\\" \\"\\$MINIO_ROOT_PASSWORD\\" >/dev/null &&
--                mc mirror --overwrite /tmp/seed-files local/$(grep ^S3_BUCKET= .env | cut -d= -f2) && rm -rf /tmp/seed-files"
-- * Every row has created_by = 'seed'; ids are md5('seed:' || key)::uuid.
-- * Sign in with any staff account below, password  Stockflow@2026
--     quanly.hn / quanly.bd (WAREHOUSE_MANAGER), kho.hn1, kho.bd1 (WAREHOUSE_STAFF), qc.hn, muahang1,
--     kehoach, sales1, dieuphoi, ketoan, ecom / ecom2 (ECOMMERCE_ADMIN), sanxuat.hn, kiemduyet ...
--   No SYSTEM_ADMIN is created: use the one the database already has. Nothing here refers to it.
--   customer accounts: kh.<code>, e.g. kh.caphemoc, kh.trasuamay (CUSTOMER role)
-- * Scenario "today" is {TODAY.isoformat()}: history runs from August 2026.
--
-- What is in it: 3 warehouses with full maps, {len(VARIANTS)} SKUs of cups / lids / boxes / bags / inks,
-- {len(SUPPLIERS)} suppliers, {len(CUSTOMERS)} B2B customers, {len(PO)} purchase orders in every state with
-- receipts, QC, putaway and invoices, {len(ORDERS)} sales orders in every state with reservations, payments,
-- production, picks, packs, shipments, returns and receivables; transfers, adjustments, cycle counts,
-- move tasks, designs and samples, carts, chat, notifications, audit log, reports.
-- Stock on hand is exactly what the receipts put away, minus what was picked, plus what was moved or
-- adjusted. inventory.stock_movement holds every movement as the application writes it, a confirmed
-- pick line included (PICK, reference PICK_LINE). Print orders are made in the warehouse they ship from
-- (HN, BD: PRODUCTION area <wh>-PRD01): blanks issued to the print shop are held there as IN_PRODUCTION
-- (MOVE, reference PRODUCTION_ORDER, production_material_issue); a completed print job's goods went to
-- <wh>-PACK01 and were consumed there (CONSUMPTION, G4-Q2), so a pick has lines for stock lines only.
-- Verified against the application (jar from develop, Oct 2026): every GET endpoint answers 200 on it.
-- =============================================================================

BEGIN;
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = '30s';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM identity.app_user WHERE id = '{U['quanly.hn']}') THEN
        RAISE EXCEPTION 'seed_test_data.sql has already been loaded into this database';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM identity.app_role WHERE code = 'SYSTEM_ADMIN') THEN
        RAISE EXCEPTION 'run the Flyway migrations first: identity.app_role has no SYSTEM_ADMIN';
    END IF;
END $$;
""")

posts = {}
for after, sql in POST:
    posts.setdefault(after, []).append(sql)
initial_of = {id(r): init for _, _, r, init in STAGED}
updates = []
for prio, t, r, init in sorted(STAGED, key=lambda x: x[0]):
    changed = {k: r[k] for k, v in init.items() if r[k] != v}
    if changed:
        updates.append(f"UPDATE {t} SET " + ", ".join(f"{k} = {q(v)}" for k, v in changed.items())
                       + f" WHERE id = {q(r['id'])};")
posts.setdefault("procurement.goods_receipt_lines", []).insert(
    0, "\n-- receipts confirmed and closed, lines moved to QC, POs received: after their lines exist")
posts["procurement.goods_receipt_lines"][1:1] = updates
for t in ORDER:
    rows = [{**r, **initial_of.get(id(r), {})} for r in ROWS.get(t, [])]
    if rows:
        w(f"\n-- {t} ({len(rows)})")
        groups = {}
        for r in rows:
            groups.setdefault(tuple(r.keys()), []).append(r)
        for cols, grp in groups.items():
            for i in range(0, len(grp), 200):
                chunk = grp[i:i + 200]
                w(f"INSERT INTO {t} ({', '.join(cols)}) VALUES")
                w(",\n".join("    (" + ", ".join(q(r[c]) for c in cols) + ")" for r in chunk)
                  + CONFLICT.get(t, "") + ";")
    for sql in posts.get(t, []):
        w(sql)

w("""
-- -----------------------------------------------------------------------------
-- Derived rows: computed from what was inserted above rather than generated.
-- -----------------------------------------------------------------------------

-- Available to promise on the storefront entries: free stock in pickable bins.
UPDATE catalog.catalog_entry e
   SET atp = COALESCE((SELECT SUM(s.on_hand - s.reserved)
                         FROM inventory.stock_item s
                         JOIN warehouse.storage_location l ON l.location_code = s.location_code
                        WHERE s.sku = e.sku AND s.status = 'AVAILABLE' AND l.is_pickable), 0)
 WHERE e.created_by = 'seed';

-- No report rows: since SCRUM-344 the application owns reporting.* (per-day rollups plus the processed_order_events
-- ledger that makes them idempotent). Rows written here without the ledger would be counted twice by the first event
-- of their day. On its next start the application rolls up every seeded order (SalesRollupBackfill: orders older than
-- anything the ledger knows); POST /api/v1/reports/rebuild does the same by hand.

-- Number sequences: the application's next number on a seeded day must not collide with a seeded one.
INSERT INTO procurement.po_number_sequence (sequence_date, last_value)
SELECT to_date(split_part(po_number, '-', 2), 'YYYYMMDD'), MAX(split_part(po_number, '-', 3)::bigint)
  FROM procurement.purchase_orders WHERE created_by = 'seed' AND po_number LIKE 'PO-%' GROUP BY 1
ON CONFLICT (sequence_date) DO UPDATE SET last_value = GREATEST(procurement.po_number_sequence.last_value, EXCLUDED.last_value);

INSERT INTO ordering.order_number_sequence (sequence_date, last_value)
SELECT to_date(split_part(order_number, '-', 2), 'YYYYMMDD'), MAX(split_part(order_number, '-', 3)::bigint)
  FROM ordering.customer_order WHERE created_by = 'seed' GROUP BY 1
ON CONFLICT (sequence_date) DO UPDATE SET last_value = GREATEST(ordering.order_number_sequence.last_value, EXCLUDED.last_value);

INSERT INTO ordering.return_number_sequence (sequence_date, last_value)
SELECT to_date(split_part(rma_number, '-', 2), 'YYYYMMDD'), MAX(split_part(rma_number, '-', 3)::bigint)
  FROM ordering.return_request WHERE created_by = 'seed' GROUP BY 1
ON CONFLICT (sequence_date) DO UPDATE SET last_value = GREATEST(ordering.return_number_sequence.last_value, EXCLUDED.last_value);

INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
SELECT split_part(n, '-', 1), to_date(split_part(n, '-', 2), 'YYYYMMDD'), MAX(split_part(n, '-', 3)::bigint)
  FROM (SELECT receipt_number AS n FROM procurement.goods_receipts WHERE created_by = 'seed'
        UNION ALL SELECT transfer_number FROM inventory.transfer_order WHERE created_by = 'seed'
        UNION ALL SELECT adjustment_number FROM inventory.stock_adjustment WHERE created_by = 'seed'
        UNION ALL SELECT po_number FROM procurement.purchase_orders WHERE created_by = 'seed' AND po_number LIKE 'SPO-%'
        UNION ALL SELECT count_number FROM inventory.cycle_count WHERE created_by = 'seed'
        UNION ALL SELECT task_number FROM warehouse.move_task WHERE created_by = 'seed'
        UNION ALL SELECT rma_number FROM ordering.return_request WHERE created_by = 'seed'
        UNION ALL SELECT order_number FROM production.production_order WHERE created_by = 'seed'
        UNION ALL SELECT request_number FROM production.sample_request WHERE created_by = 'seed'
        UNION ALL SELECT quote_number FROM ordering.quote WHERE created_by = 'seed'
        UNION ALL SELECT code FROM fulfillment.dispatch_load WHERE created_by = 'seed'
        UNION ALL SELECT code FROM fulfillment.load_plan WHERE created_by = 'seed'
        UNION ALL SELECT report_number FROM inventory.stock_damage_report WHERE created_by = 'seed'
        UNION ALL SELECT d.record_number FROM inventory.stock_damage_disposition d
                  JOIN inventory.stock_damage_report r ON r.id = d.damage_report_id
                 WHERE r.created_by = 'seed' AND d.record_number IS NOT NULL) s
 GROUP BY 1, 2
ON CONFLICT (document_type, sequence_date) DO UPDATE
   SET last_value = GREATEST(platform.document_sequence.last_value, EXCLUDED.last_value);

COMMIT;

-- Quick look after loading:
--   SELECT status, COUNT(*) FROM ordering.customer_order WHERE created_by = 'seed' GROUP BY 1 ORDER BY 1;
--   SELECT status, COUNT(*) FROM procurement.purchase_orders WHERE created_by = 'seed' GROUP BY 1 ORDER BY 1;
--   SELECT location_code, sku, lot_number, on_hand, reserved, status FROM inventory.stock_item
--    WHERE created_by = 'seed' ORDER BY location_code LIMIT 50;
""")
OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
shutil.rmtree(FILES, ignore_errors=True)
for file_key, data in SEEDED_FILES.items():
    target = FILES / file_key
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
print(f"wrote {OUT} - {total_rows} rows, {len(ROWS)} tables, {len(ORDERS)} orders, {len(PO)} POs, "
      f"{len(STOCK)} stock rows, {len(MOVES)} movements")
