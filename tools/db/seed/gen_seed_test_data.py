#!/usr/bin/env python3
"""
Generates tools/db/seed/seed_test_data.sql: a large, internally consistent test data set for a database
migrated with db/migration only (dev / deployed environments; it also coexists with db/demo on local).

    python tools/db/seed/gen_seed_test_data.py          # rewrites seed_test_data.sql next to this file

Why a generator rather than a hand-written file: the data has to agree with itself the way the
application would have left it. Stock on hand is what the receipts put away, minus what the picks
consumed, plus the posted adjustments; a reservation's quantity is in its stock row's `reserved`;
order, PO and invoice totals are the sums of their lines; design checksums are the manifest hash
DesignSnapshotJpaEntity computes. Keeping that true by hand across ~3,000 rows is not realistic.

Everything is deterministic (fixed seed, md5-derived ids), so regenerating gives the same file.
Ids are md5('seed:' || key)::uuid, the same scheme as db/demo with another namespace.
"""

import hashlib
import json
import random
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

random.seed(20261012)
VN = timezone(timedelta(hours=7))
TODAY = date(2026, 10, 12)
OUT = Path(__file__).with_name("seed_test_data.sql")
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
}

U = {}  # username -> user id
ROLE = lambda code: Raw(f"(SELECT id FROM identity.app_role WHERE code = '{code}')")
# Attributes are a shared dictionary: on a local database db/demo already has COLOR and MATERIAL, so
# they are inserted ON CONFLICT DO NOTHING and always referenced by code.
ATTR_ID = lambda code: Raw(f"(SELECT id FROM product.attributes WHERE code = '{code}')")
CONFLICT = {"product.attributes": " ON CONFLICT (code) DO NOTHING"}

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
]
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
                min_qty=10, max_qty=400 if not lot else 120, reorder_point=40 if not lot else 20,
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
        pack_size=1, lead_time_days=10, is_preferred=True, created_at=at(date(2026, 8, 1), 11))
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
            is_preferred=False, created_at=at(date(2026, 8, 1), 11))

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
        add("customer.credit_profile", id=uid("credit:" + code), customer_id=cid, allow_prepaid=True,
            allow_deposit=kind == "DEPOSIT" or kind == "CREDIT" and i % 2 == 0,
            allow_credit=kind == "CREDIT", default_payment_term=kind,
            deposit_percent=terms[1] if kind == "DEPOSIT" else (30 if kind == "CREDIT" and i % 2 == 0 else None),
            credit_limit=terms[1] if kind == "CREDIT" else None, credit_term_days=terms[2] if kind == "CREDIT" else None,
            currency="VND", approved_by=U["ketoan"], approved_at=created + timedelta(days=2),
            note="Hạn mức duyệt theo doanh số 6 tháng" if kind == "CREDIT" else "Khách mới, đặt cọc trước khi sản xuất",
            created_at=created + timedelta(days=2))
    CUST[code] = dict(id=cid, company=company, contact=contact, seg=seg, wh=region, status=status, terms=terms,
                      email=email, phone=phone, addr=addr, user=U.get("kh." + code))

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
# receiving a transfer or a return is a RECEIPT, a count correction or a scrap is an ADJUSTMENT, and a
# confirmed pick line writes one PICK row naming the line (reference PICK_LINE, SCRUM-267), as the
# application does since V20261014001000. The ledger reconciles to stock on hand.
APP_KIND = {"PUTAWAY": "MOVE", "COUNT_CORRECTION": "ADJUSTMENT", "SCRAP": "ADJUSTMENT", "TRANSFER_IN": "RECEIPT",
            "RETURN_IN": "RECEIPT"}


def move(kind, sku, qty, ref_type, ref_id, when, actor, frm=None, to=None, lot=None, fs=None, ts_=None, reason=None):
    kind = APP_KIND.get(kind, kind)
    MOVES.append(dict(id=uid(f"mv:{len(MOVES)}:{kind}:{ref_id}"), movement_type=kind, sku=sku, lot_number=lot,
                      from_location_code=frm, to_location_code=to, quantity=qty, from_status=fs, to_status=ts_,
                      reference_type=ref_type, reference_id=ref_id, reason=reason, actor_id=U[actor],
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
            rejected_first=False, po_type="STANDARD", production_order_id=None, source_proposal=None, number=None):
    """lines: [(sku, qty, unit price)]. Returns the PO dict with line ids."""
    pid = uid("po:" + key)
    approver = approver or ("quanly.hn" if p == "HN" else "quanly.bd")
    number = number or next_no("PO", d, 6)
    order_dt = at(d, 9, 15)
    sub_dt, app_dt, conf_dt = order_dt + timedelta(hours=1), order_dt + timedelta(hours=4), order_dt + timedelta(days=1)
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
    for role, ext, ctype, size in (("CUSTOMER_PREVIEW", "png", "image/png", 845123),
                                   ("PRINT_READY", "pdf", "application/pdf", 5242880)):
        aid = uid(f"art:{key}:{role}")
        skey = f"design/{did}/{role.lower()}/{aid}.{ext}"
        arts.append(dict(id=aid, role=role, key=skey, name=f"{cust_code}-{role.lower()}.{ext}", ctype=ctype,
                         size=size, checksum=sha(f"file:{aid}")))
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
    production_order(f"sample:{key}", "SAMPLE", c["wh"] if c["wh"] != "DN" else "BD", sku, sid, checksum, 1, "COMPLETED",
                     sd, sample_request=srid)
    return DESIGNS[key]


PRODUCTION = []


def production_order(key, typ, p, sku, snap, checksum, qty, status, start, sample_request=None, order=None,
                     line=None, mode="IN_HOUSE", hold=None):
    pid = uid("prod:" + key)
    path = ["PENDING_PREPRESS", "READY", "MATERIAL_ISSUED", "PRINTING", "QC", "COMPLETED"]
    if mode == "SUBCONTRACTED":
        path = ["PENDING_PREPRESS", "READY", "SUBCONTRACTED", "QC", "COMPLETED"]
    target = hold[0] if status == "ON_HOLD" else status
    reached = path[:path.index(target) + 1]
    done = status == "COMPLETED"
    printed = qty + (1 if done and qty > 3 else 0) if (done or status == "QC") else 0
    good = printed - (1 if printed > qty else 0)
    worker = "sanxuat.hn" if p == "HN" else "sanxuat.bd"
    row = add("production.production_order", id=pid, order_number=next_no("PRD", start.date()), type=typ, mode=mode,
              status=status, status_before_hold=hold[0] if status == "ON_HOLD" else None,
              hold_reason=hold[1] if status == "ON_HOLD" else None, hold_note=hold[2] if status == "ON_HOLD" else None,
              sample_request_id=sample_request, sales_order_id=order, sales_order_line_id=line, parent_id=None,
              approved_sample_id=None, warehouse_id=WH[p], blank_sku=sku, design_snapshot_id=snap,
              design_checksum=checksum, due_date=start.date() + timedelta(days=10), planned_quantity=qty,
              issued_quantity=qty + (1 if qty > 3 else 0) if "MATERIAL_ISSUED" in reached or
              (mode == "SUBCONTRACTED" and "SUBCONTRACTED" in reached) else 0,
              printed_quantity=printed, good_quantity=good, scrap_quantity=printed - good,
              subcontract_method="SUPPLIED_BLANKS" if mode == "SUBCONTRACTED" else None,
              subcontractor_id=SUP["INSG"] if mode == "SUBCONTRACTED" else None,
              subcontract_unit_price=120000 if mode == "SUBCONTRACTED" else None,
              subcontract_currency="VND" if mode == "SUBCONTRACTED" else None, purchase_order_id=None,
              prepress_checked_by=U["kiemduyet"] if len(reached) > 1 else None,
              prepress_checked_at=start + timedelta(hours=4) if len(reached) > 1 else None,
              completed_at=start + timedelta(days=len(path)) if done else None, cancelled_at=None,
              cancellation_reason=None, created_at=start)
    prev = None
    for k, s in enumerate(reached + (["ON_HOLD"] if status == "ON_HOLD" else [])):
        add("production.production_order_status_history", id=uid(f"prod:{key}:h:{k}"), production_order_id=pid,
            from_status=prev, to_status=s, reason=hold[2] if s == "ON_HOLD" else None,
            changed_at=start + timedelta(days=k, hours=1), changed_by=U["kiemduyet" if k == 1 else worker])
        prev = s
    if printed:
        add("production.production_qc_result", id=uid(f"prod:{key}:qc"), production_order_id=pid, round_no=1,
            passed_quantity=good, failed_quantity=printed - good,
            note="Lệch màu nhẹ 1 thùng" if printed > good else "Đạt",
            inspected_at=start + timedelta(days=len(path) - 1), inspected_by=U["qc.hn" if p == "HN" else "qc.bd"])
    if printed > good:
        add("production.production_scrap", id=uid(f"prod:{key}:scrap"), production_order_id=pid,
            quantity=printed - good, reason="QC_FAILED", note="Lệch màu so với mẫu duyệt", stock_adjustment_id=None,
            recorded_at=start + timedelta(days=len(path) - 1), recorded_by=U[worker])
    PRODUCTION.append(dict(id=pid, row=row, key=key, done=done))
    if done and line:
        add("ordering.order_line_production", production_order_id=pid, order_line_id=line, good_quantity=good,
            completed_at=start + timedelta(days=len(path)))
    return pid


def status_path(custom, credit, final):
    path = ["PENDING_PAYMENT"] if not credit else []
    path += ["CONFIRMED"]
    if custom:
        path += ["IN_PRODUCTION"]
    path += ["READY_TO_FULFILL", "IN_FULFILMENT", "SHIPPED", "DELIVERED", "COMPLETED"]
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


def place_order(key, cust_code, d, final, lines_spec=None, custom=False, term=None, hold=None, cancel=None,
                prod_status=None, pick_partial=False, return_spec=None, cancel_request=None, sub=False):
    c = CUST[cust_code]
    p = c["wh"]
    oid = uid("order:" + key)
    terms = c["terms"]
    term = term or (terms[0] if terms else "PREPAID")
    credit = term == "CREDIT"
    placed = at(d, random.randint(8, 16), random.choice([0, 15, 30, 45]))
    number = next_no("SO", d, 6)
    # lines
    if lines_spec is None:
        pool = [v for v in SELLABLE if (p != "DN" or v["sku"] in DN_SKUS)]
        random.shuffle(pool)
        lines_spec = [(v["sku"], random.randint(1, 6) * 5) for v in pool[:random.randint(1, 4)]]
        if custom:
            cust_pool = [v for v in SELLABLE if v["kind"] == "CUSTOMIZABLE" and (p != "DN" or v["sku"] in DN_SKUS)]
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
        t = t + timedelta(days=random.choice([1, 1, 2]), hours=random.randint(1, 5))
    when = dict(zip(path, times))
    cur = path[-1]
    if hold:
        cur = "ON_HOLD"
    if cancel:
        cur = "CANCELLED"
    if final == "RETURNED":
        cur = "RETURNED"
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
              credit_term_days=terms[2] if credit else None, created_at=placed)
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
    if final == "RETURNED":
        hist.append("RETURNED")
        when["RETURNED"] = when["DELIVERED"] + timedelta(days=4)
    actors = {"PENDING_PAYMENT": c["user"], "DRAFT": c["user"], "CONFIRMED": None, "IN_PRODUCTION": U["dieuphoi"],
              "READY_TO_FULFILL": U["dieuphoi"], "IN_FULFILMENT": U[staff_of(p)[0]], "SHIPPED": U[staff_of(p)[0]],
              "DELIVERED": None, "COMPLETED": None, "ON_HOLD": U["dieuphoi"], "CANCELLED": U["dieuphoi"],
              "RETURNED": U[staff_of(p)[2]]}
    for k, s in enumerate(hist):
        add("ordering.order_status_history", id=uid(f"order:{key}:h:{k}"), order_id=oid, from_status=prev, to_status=s,
            reason={"ON_HOLD": hold[2] if hold else None, "CANCELLED": cancel[2] if cancel else None,
                    "RETURNED": "Khách trả hàng lỗi"}.get(s), actor_id=actors.get(s), occurred_at=when[s],
            created_at=when[s])
        prev = s
    o = dict(id=oid, key=key, cust=cust_code, p=p, lines=lines, total=total, row=row, when=when, status=cur,
             term=term, number=number, custom=custom, deposit=deposit)
    ORDERS.append(o)
    if final == "DRAFT":
        return o
    # payments --------------------------------------------------------------------------------
    paid = 0
    pays = []

    def pay(tag, amount, method, at_, status="CAPTURED"):
        nonlocal paid
        pid_ = uid(f"pay:{key}:{tag}")
        add("payment.payment", id=pid_, order_id=oid, amount=amount, currency="VND", method=method, status=status,
            provider_ref=(f"VCB{at_.strftime('%y%m%d')}{len(ROWS.get('payment.payment', [])):05d}"
                          if method == "BANK_TRANSFER" else (f"COD-{number}" if method == "COD" else None)),
            captured_at=at_ if status in ("CAPTURED", "REFUNDED") else None, created_at=at_ - timedelta(hours=2))
        if status in ("CAPTURED", "REFUNDED"):
            add("ordering.order_payment", payment_id=pid_, order_id=oid, amount=amount, currency="VND",
                captured_at=at_)
            paid += amount
        pays.append(pid_)
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
            if "DELIVERED" in when:
                cod = sub
                pid_ = pay("rest", total - deposit, "COD" if cod else "BANK_TRANSFER",
                           when["DELIVERED"] if cod else when["DELIVERED"] + timedelta(days=1))
                row["paid_in_full_at"] = when["DELIVERED"] if cod else when["DELIVERED"] + timedelta(days=1)
                if cod:
                    SHIPMENTS_COD.append((o, pid_, total - deposit))
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
            RECEIVABLES.append(r)
    row["paid_amount"] = paid
    # reservations ------------------------------------------------------------------------------
    picked_state = None
    if "IN_FULFILMENT" in when and not (cancel and cancel[0] == "IN_FULFILMENT"):
        picked_state = "PICKING" if (cur == "IN_FULFILMENT" and pick_partial) else "PICKED"
    if cur == "ON_HOLD" and "IN_FULFILMENT" in when:
        picked_state = "PICKING"
    consumed = picked_state == "PICKED"
    res_rows = []
    for ln in lines:
        for k, (s, qn) in enumerate(reserve_from[ln["id"]]):
            rid = uid(f"res:{key}:{ln['id']}:{k}")
            if cur == "CANCELLED":
                st, closed, reason, exp = ("EXPIRED", when["CANCELLED"], "RESERVATION_EXPIRED",
                                           placed + timedelta(days=2)) if cancel[1] == "PAYMENT_NOT_RECEIVED" else \
                    ("RELEASED", when["CANCELLED"], "ORDER_CANCELLED", None)
            elif consumed or (picked_state == "PICKING" and k == 0 and ln is lines[0] and cur != "ON_HOLD"):
                st, closed, reason, exp = "CONSUMED", when["IN_FULFILMENT"] + timedelta(hours=2), None, None
            else:
                st, closed, reason = "HELD", None, None
                exp = placed + timedelta(days=7) if cur == "PENDING_PAYMENT" else None
            add("inventory.stock_reservation", id=rid, stock_item_id=s.id, order_id=oid, root_request_id=uid(f"req:{key}"),
                request_id=uid(f"req:{key}:{ln['id']}:{k}"), quantity=qn, reserved_at=placed, expires_at=exp,
                status=st, release_reason=reason, closed_at=closed)
            add("ordering.order_line_reservation", order_line_id=ln["id"], reservation_id=rid)
            if st == "HELD":
                s.reserved += qn
            elif st == "CONSUMED":
                s.on_hand -= qn
            res_rows.append(dict(id=rid, line=ln, stock=s, qty=qn, status=st))
    # production -----------------------------------------------------------------------------------
    for n, ln in enumerate(lines):
        if not ln["design"] or "IN_PRODUCTION" not in when:
            continue
        ps = "COMPLETED" if "READY_TO_FULFILL" in when else (prod_status or "PRINTING")
        hold_p = ("PRINTING", "MACHINE_DOWN", "Máy in flexo số 2 hỏng trục, chờ thay") if ps == "ON_HOLD" else None
        prod_wh = p if p != "DN" else "BD"
        pid_ = production_order(f"order:{key}:{n}", "ORDER", prod_wh, ln["sku"], ln["design"]["snapshot"],
                                ln["design"]["checksum"], ln["qty"], ps, when["IN_PRODUCTION"], order=oid, line=ln["id"],
                                mode="SUBCONTRACTED" if ps == "SUBCONTRACTED" else "IN_HOUSE", hold=hold_p)
        ln["production"] = pid_
    # fulfilment ----------------------------------------------------------------------------------
    if picked_state:
        staff, staff2, mgr, qcu = staff_of(p)
        t_pick = when["IN_FULFILMENT"]
        wave_id = WAVES.get((p, t_pick.date()))
        if wave_id is None:
            wave_id = uid(f"wave:{p}:{t_pick.date()}")
            WAVES[(p, t_pick.date())] = wave_id
            add("fulfillment.wave", id=wave_id, code=f"WAVE-{p}-{t_pick.strftime('%Y%m%d')}",
                status="COMPLETED" if t_pick.date() < TODAY - timedelta(days=1) else "RELEASED",
                released_at=at(t_pick.date(), 7, 30), created_at=at(t_pick.date(), 7))
        pick_id = uid(f"pick:{key}")
        pick_done = picked_state == "PICKED"
        add("fulfillment.pick", id=pick_id, wave_id=wave_id, order_id=oid, order_number=number,
            warehouse_id=WH[p], status=picked_state, assigned_user_id=U[staff], assigned_at=t_pick, started_at=t_pick + timedelta(minutes=20),
            picked_at=t_pick + timedelta(hours=2) if pick_done else None, needs_put_back=False, created_at=t_pick)
        for k, r in enumerate(res_rows):
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
        if pick_done:
            shipped = "SHIPPED" in when
            pack_id = uid(f"pack:{key}")
            pack_status = "PACKED" if shipped or cur == "IN_FULFILMENT" and not pick_partial else "PACKING"
            if cur == "ON_HOLD":
                pack_status = "ON_HOLD"
            add("fulfillment.pack", id=pack_id, pick_id=pick_id, status=pack_status,
                packed_at=t_pick + timedelta(hours=4) if pack_status == "PACKED" else None,
                created_at=t_pick + timedelta(hours=2))
            for n, ln in enumerate(lines):
                if ln["design"]:
                    add("fulfillment.design_verification", id=uid(f"dv:{key}:{n}"), pack_id=pack_id, order_id=oid,
                        order_line_id=ln["id"], snapshot_id=ln["design"]["snapshot"],
                        expected_checksum=ln["design"]["checksum"], actual_checksum=ln["design"]["checksum"],
                        result="MATCH", verified_by=U[staff2], verified_at=t_pick + timedelta(hours=3),
                        created_at=t_pick + timedelta(hours=3))
            ship_id = uid(f"ship:{key}") if shipped else None
            carrier = CARRIERS[len(ORDERS) % 4]
            tracking = f"{carrier[:3].upper().replace(' ', '')}{number[-6:]}{len(ORDERS):03d}VN" if shipped else None
            if shipped:
                sstatus = "DELIVERED" if "DELIVERED" in when else "DISPATCHED"
                add("fulfillment.shipment", id=ship_id, pack_id=pack_id, order_id=oid, carrier=carrier,
                    tracking_number=tracking, status=sstatus, dispatched_at=when["SHIPPED"], created_at=when["SHIPPED"])
                evs = [("LABEL_CREATED", when["SHIPPED"] - timedelta(hours=2)), ("HANDED_OVER", when["SHIPPED"]),
                       ("IN_TRANSIT", when["SHIPPED"] + timedelta(hours=6))]
                if "DELIVERED" in when:
                    evs += [("OUT_FOR_DELIVERY", when["DELIVERED"] - timedelta(hours=4)),
                            ("DELIVERED", when["DELIVERED"])]
                for k, (es, et) in enumerate(evs):
                    add("fulfillment.shipment_event", id=uid(f"sev:{key}:{k}"), shipment_id=ship_id, status=es,
                        occurred_at=et, source="CARRIER_WEBHOOK" if k else "MANUAL",
                        carrier_event_id=f"{tracking}-{k}" if k else None,
                        location_text=["Kho " + p, "Bưu cục " + p, "Trung tâm phân loại", "Bưu cục phát",
                                       ship["province"]][k],
                        note=None, raw_payload={"status": es, "tracking": tracking} if k else None, created_at=et)
                add("notification.delivery_log", id=uid(f"mail:ship:{key}"), template_code="shipment.dispatched",
                    channel="EMAIL", recipient=c["email"], status="SENT", error=None, sent_at=when["SHIPPED"],
                    external_reference=None, operation_reference=f"shipment:{ship_id}", terminal=True,
                    delivery_generation=0, attempt_number=1, created_at=when["SHIPPED"])
            o["shipment"] = ship_id
            n_pk = 2 if sum(ln["qty"] for ln in lines) > 40 else 1
            for k in range(n_pk):
                pk = uid(f"pkg:{key}:{k}")
                sealed = pack_status == "PACKED"
                add("fulfillment.package", id=pk, pack_id=pack_id, shipment_id=ship_id, package_no=k + 1,
                    weight_kg=round(sum(VAR[ln["sku"]]["dims"][0] * ln["qty"] for ln in lines) / n_pk, 3),
                    length_cm=120, width_cm=100, height_cm=150, dim_weight_kg=300.0,
                    tracking_number=f"{tracking}-{k + 1}" if tracking else None,
                    label_url=f"https://labels.stockflow.example/{tracking}-{k + 1}.pdf" if tracking else None,
                    status="SEALED" if sealed else "OPEN", sealed_at=t_pick + timedelta(hours=4) if sealed else None,
                    created_at=t_pick + timedelta(hours=3))
                for m, ln in enumerate(lines):
                    if m % n_pk == k:
                        add("fulfillment.package_line", id=uid(f"pkgl:{key}:{k}:{m}"), package_id=pk,
                            order_line_id=ln["id"], quantity=ln["qty"], created_at=t_pick + timedelta(hours=3))
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
                add("payment.refund", id=uid(f"refund:{key}"), payment_id=pays[0], amount=paid - retained,
                    currency="VND", reason="Hoàn tiền hủy đơn (trừ phí)", status="COMPLETED",
                    refunded_at=when["CANCELLED"] + timedelta(days=1), order_id=oid, source="ORDER_CANCELLED",
                    created_at=when["CANCELLED"])
    if return_spec:
        rstatus = return_spec
        rr = uid(f"rma:{key}")
        rq = when["DELIVERED"] + timedelta(days=2)
        decided = rstatus not in ("REQUESTED", "CANCELLED")
        received = rstatus in ("RECEIVED", "REFUNDED")
        ref = None
        ln = lines[0]
        rqty = max(1, ln["qty"] // 5)
        if rstatus == "REFUNDED":
            ref = uid(f"refund:rma:{key}")
            add("payment.refund", id=ref, payment_id=pays[0], amount=rqty * ln["price"], currency="VND",
                reason="Hoàn tiền hàng trả lại lỗi", status="COMPLETED", refunded_at=rq + timedelta(days=3),
                order_id=oid, source="RETURN", created_at=rq + timedelta(days=2))
        add("ordering.return_request", id=rr, rma_number=next_no("RMA", rq.date()), order_id=oid, customer_id=c["id"],
            status=rstatus, reason_code="DEFECTIVE" if rstatus != "REJECTED" else "CHANGED_MIND",
            note="Một số thùng bị móp, ly bên trong biến dạng" if rstatus != "REJECTED" else "Khách muốn đổi size",
            requested_at=rq, decided_by=U["dieuphoi"] if decided else None, decided_at=rq + timedelta(hours=5)
            if decided else None, rejection_reason="Hàng in logo theo yêu cầu không áp dụng đổi trả do đổi ý"
            if rstatus == "REJECTED" else None, received_at=rq + timedelta(days=2) if received else None,
            refund_id=ref, created_at=rq)
        rl = uid(f"rmal:{key}")
        add("ordering.return_request_line", id=rl, return_request_id=rr, order_line_id=ln["id"], quantity=rqty,
            item_condition="DAMAGED" if received else None, disposition="QUARANTINE" if received else None,
            created_at=rq)
        if received:
            Stock(ln["sku"], f"{p}-QRT01", rqty, rq + timedelta(days=2), "DAMAGED", key=f"return:{key}")
            move("RETURN_IN", ln["sku"], rqty, "ORDER", oid, rq + timedelta(days=2), staff_of(p)[0],
                 to=f"{p}-QRT01", ts_="DAMAGED", reason="Hàng khách trả - móp")
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
    plan.append(dict(cust=[PREPAID, CREDIT, DEPOSIT, CREDIT][i][i % 3], d=day(52 + i), final="IN_FULFILMENT",
                     pick_partial=i % 2 == 1))
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
plan.append(dict(cust=PREPAID[2], d=day(50), final="IN_FULFILMENT",
                 hold=("IN_FULFILMENT", "ADDRESS_VERIFICATION", "Địa chỉ giao không liên lạc được, chờ khách xác nhận")))
plan.append(dict(cust=PREPAID[3], d=day(20), final="PENDING_PAYMENT",
                 cancel=("PENDING_PAYMENT", "PAYMENT_NOT_RECEIVED", "Quá hạn thanh toán, hệ thống tự hủy")))
plan.append(dict(cust=PREPAID[4], d=day(30), final="CONFIRMED", cancel_request=10,
                 cancel=("CONFIRMED", "CUSTOMER_REQUEST", "Khách yêu cầu hủy - lùi ngày khai trương")))
plan.append(dict(cust=DEPOSIT[0], d=day(34), final="PENDING_PAYMENT",
                 cancel=("PENDING_PAYMENT", "DUPLICATE_ORDER", "Khách đặt trùng đơn")))
plan.append(dict(cust=PREPAID[0], d=day(14), final="RETURNED", return_spec="REFUNDED"))
plan.append(dict(cust=PREPAID[5 % len(PREPAID)], d=day(38), final="COMPLETED", return_spec="REJECTED"))
plan.append(dict(cust=DEPOSIT[1], d=day(41), final="DELIVERED", return_spec="REQUESTED"))
plan.append(dict(cust=PREPAID[6 % len(PREPAID)], d=day(42), final="DELIVERED", return_spec="APPROVED"))

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
add("ordering.order_hold", id=uid("hold:resolved"), order_id=done_o["id"], reason="PAYMENT_REVIEW",
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
    if status == "PAID":
        r["order_row"]["paid_in_full_at"] = paid_at
    if r["paid"]:
        extra = 5000000 if i % 5 == 0 else 0
        t = uid(f"transfer:{r['id']}")
        add("payment.customer_transfer", id=t, customer_id=CUST[r["cust"]]["id"],
            reference=f"FT{paid_at.strftime('%y%m%d')}{i:06d}", amount=r["paid"] + extra, unallocated_amount=extra,
            currency="VND", received_on=paid_at.date(), recorded_by=U["ketoan"], recorded_at=paid_at + timedelta(hours=2),
            note="Thanh toán công nợ theo sao kê Vietcombank" + (" - dư chuyển kỳ sau" if extra else ""),
            created_at=paid_at + timedelta(hours=2))
        add("payment.transfer_allocation", id=uid(f"alloc:{r['id']}"), transfer_id=t, receivable_id=r["id"],
            amount=r["paid"], allocated_at=paid_at + timedelta(hours=2), allocated_by=U["ketoan"])

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
def transfer(key, frm, to, status, d, items, reason="REBALANCING", damaged=0, carrier=None):
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
        cancel_reason="Kho đích đã nhập đủ từ NCC" if status == "CANCELLED" else None, created_at=t)
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
add("design.design_artifact", id=uid("art:draft:" + k), draft_id=d_draft,
    storage_key=f"design/{d_draft}/customer_preview/{uid('art:draft:' + k)}.png", original_name="logo-may-winter.png",
    content_type="image/png", size_bytes=612233, stored_at=at(date(2026, 10, 9), 11), checksum=sha("file:draft:" + k),
    upload_key=None, artifact_role="CUSTOMER_PREVIEW", created_at=at(date(2026, 10, 9), 11))
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
sub_prod = next((x for x in PRODUCTION if x["row"]["mode"] == "SUBCONTRACTED"), None)
if sub_prod:
    pr = sub_prod["row"]
    d = pr["created_at"].date()
    spo = make_po("subcontract", "HN" if pr["warehouse_id"] == WH["HN"] else "BD", "INSG", d, "CONFIRMED",
                  [(pr["blank_sku"], pr["planned_quantity"], 120000)], submitter="muahang1", approver="quanly.hn",
                  po_type="SUBCONTRACT", production_order_id=pr["id"], number=next_no("SPO", d),
                  note=f"Gia công in {pr['order_number']}")
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
    ("shipment.dispatched", "EMAIL", "Đơn {{orderNumber}} đã được giao cho đơn vị vận chuyển",
     "Đơn {{orderNumber}} đã xuất kho, mã vận đơn {{trackingNumber}} ({{carrier}})."),
    ("shipment.dispatched", "SMS", None, "SF Pack: don {{orderNumber}} da giao cho {{carrier}}, ma {{trackingNumber}}."),
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

# ============================================================================ 11. stock rows and ledger
for s in STOCK:
    assert 0 <= s.reserved <= s.on_hand, (s.sku, s.loc, s.on_hand, s.reserved)
    add("inventory.stock_item", id=s.id, sku=s.sku, location_code=s.loc, lot_number=s.lot, expiry_date=s.expiry,
        on_hand=s.on_hand, reserved=s.reserved, status=s.status, received_at=s.received, serial_number=None,
        created_at=s.received)
for m in MOVES:
    ROWS.setdefault("inventory.stock_movement", []).append({**m, "created_by": BY})

# ============================================================================ emit
ORDER = """
identity.app_user identity.user_role
warehouse.warehouse warehouse.zone warehouse.shelf warehouse.shelf_level warehouse.storage_location warehouse.bin
warehouse.area warehouse.boundary warehouse.slotting_rule identity.user_warehouse identity.user_session
notification.preference customer.segment customer.customer customer.address customer.credit_profile
product.brands product.categories product.attributes product.attribute_values product.category_attributes
product.products product.product_categories product.product_attributes product.product_attribute_options
product.product_descriptive_values product.variants product.variant_attribute_values product.media
product.customization_templates product.print_areas
procurement.suppliers procurement.supplier_contacts procurement.supplier_addresses procurement.supplier_items
procurement.supplier_item_prices procurement.approval_limits
catalog.pricing_rule catalog.promotion catalog.product_listing catalog.catalog_entry
design.design_draft design.design_artifact design.design_decision design.design_snapshot
production.sample_request ordering.approved_sample
ordering.customer_order ordering.order_line ordering.order_status_history ordering.order_hold
ordering.order_cancellation_request ordering.order_credit_check
production.production_order production.production_order_status_history production.production_qc_result
procurement.replenishment_proposals
procurement.purchase_orders procurement.purchase_order_revisions procurement.purchase_order_lines
procurement.purchase_order_line_revisions procurement.purchase_order_approvals procurement.purchase_order_events
procurement.purchase_order_attachments procurement.replenishment_proposal_lines procurement.po_delivery_decision
notification.po_delivery_control procurement.goods_receipts procurement.goods_receipt_lines
procurement.qc_inspections procurement.supplier_invoices procurement.supplier_invoice_lines
inventory.stock_item inventory.stock_reservation ordering.order_line_reservation ordering.order_line_production
payment.payment ordering.order_payment payment.refund payment.receivable payment.customer_transfer
payment.transfer_allocation
fulfillment.wave fulfillment.pick fulfillment.pick_line fulfillment.pack fulfillment.design_verification
fulfillment.shipment fulfillment.shipment_event fulfillment.package fulfillment.package_line
payment.cod_remittance payment.cod_remittance_line
ordering.return_request ordering.return_request_line
inventory.cycle_count inventory.cycle_count_line inventory.stock_adjustment production.production_scrap
inventory.transfer_order inventory.transfer_order_line
warehouse.move_task warehouse.putaway_task inventory.stock_movement
chat.conversation chat.message notification.template notification.delivery_log platform.audit_log
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
-- * One transaction: it loads completely or not at all, and refuses to run twice.
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
-- pick line included (PICK, reference PICK_LINE).
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

-- Report read models, from the orders that were sold (not draft, not cancelled).
INSERT INTO reporting.sales_daily_summary (id, day, orders_count, revenue, currency, created_at, created_by)
SELECT md5('seed:report:day:' || d)::uuid, d, COUNT(*), SUM(o.total_amount), 'VND', now(), 'seed'
  FROM (SELECT (o.placed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d, o.total_amount
          FROM ordering.customer_order o
         WHERE o.created_by = 'seed' AND o.status NOT IN ('DRAFT', 'CANCELLED')) o
 GROUP BY d
ON CONFLICT (day) DO UPDATE SET orders_count = reporting.sales_daily_summary.orders_count + EXCLUDED.orders_count,
                                revenue = reporting.sales_daily_summary.revenue + EXCLUDED.revenue;

INSERT INTO reporting.product_sales_summary (id, sku, period, quantity_sold, revenue, created_at, created_by)
SELECT md5('seed:report:sku:' || l.sku || ':' || p)::uuid, l.sku, p, SUM(l.quantity), SUM(l.quantity * l.unit_price),
       now(), 'seed'
  FROM (SELECT ol.*, to_char(o.placed_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM') AS p
          FROM ordering.order_line ol JOIN ordering.customer_order o ON o.id = ol.order_id
         WHERE o.created_by = 'seed' AND o.status NOT IN ('DRAFT', 'CANCELLED')) l
 GROUP BY l.sku, p
ON CONFLICT (sku, period) DO UPDATE SET quantity_sold = reporting.product_sales_summary.quantity_sold + EXCLUDED.quantity_sold,
                                        revenue = reporting.product_sales_summary.revenue + EXCLUDED.revenue;

-- Number sequences: the application's next number on a seeded day must not collide with a seeded one.
INSERT INTO procurement.po_number_sequence (sequence_date, last_value)
SELECT to_date(split_part(po_number, '-', 2), 'YYYYMMDD'), MAX(split_part(po_number, '-', 3)::bigint)
  FROM procurement.purchase_orders WHERE created_by = 'seed' AND po_number LIKE 'PO-%' GROUP BY 1
ON CONFLICT (sequence_date) DO UPDATE SET last_value = GREATEST(procurement.po_number_sequence.last_value, EXCLUDED.last_value);

INSERT INTO ordering.order_number_sequence (sequence_date, last_value)
SELECT to_date(split_part(order_number, '-', 2), 'YYYYMMDD'), MAX(split_part(order_number, '-', 3)::bigint)
  FROM ordering.customer_order WHERE created_by = 'seed' GROUP BY 1
ON CONFLICT (sequence_date) DO UPDATE SET last_value = GREATEST(ordering.order_number_sequence.last_value, EXCLUDED.last_value);

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
        UNION ALL SELECT request_number FROM production.sample_request WHERE created_by = 'seed') s
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
print(f"wrote {OUT} - {total_rows} rows, {len(ROWS)} tables, {len(ORDERS)} orders, {len(PO)} POs, "
      f"{len(STOCK)} stock rows, {len(MOVES)} movements")
