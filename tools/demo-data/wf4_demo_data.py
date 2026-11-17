#!/usr/bin/env python3
"""
WF4 demo data (kltn-docs 17 / 07 / 08 / 09: pick -> carton check -> 3D load plan -> carrier handover).

Creates the data the WF4 demo starts from, ONLY through the public HTTP API (never SQL), and is safe to re-run: every
object is looked up first (by code, or by the marker kept in the quote's terms) and a step already done is skipped.

What it creates (see README.md for the details):

  D13  carriers          one MANUAL carrier (waybill typed by hand) and one WEBHOOK_LOAD carrier (events signed with a
                         shared secret); the vehicle types they use (PICKUP, TRUCK-1T25, TRUCK-2T5), checked active and
                         able to supply at least two vehicles (the presets exist after the migrations)
       staging area      one ACTIVE STAGING area in the demo warehouse (created, or re-activated, only if none is active)
  D14  one B2B order whose contract has a BEFORE_DELIVERY instalment that is NOT paid, and a delivery batch whose
       window starts tomorrow (the run date + 1 day)
  D23  two B2B orders, each with whole cartons held at the warehouse, that together need TWO vehicles of the small type
       (PICKUP) in a load plan - checked with the advisory trial estimate, which stores nothing

Each order goes through the real flow, so the data is what the application itself leaves behind:
  quote (Sales) -> sent -> accepted (the CUSTOMER's own login) -> converted to an order -> contract drawn up, sent,
  signed -> instalment on signing paid by a payment receipt (Accounting) -> order CONFIRMED -> shipping warehouse chosen
  (Sales; stock held) -> for the cartons missing: production order (Production Coordinator) -> submitted -> approved
  (a second user: the approver is never the drafter) -> goods receipt of the cartons -> putaway into bins -> the held
  stock now covers the order.
It stops there: nothing is released, picked or labelled; the presenter does steps 1-10 live. The orders stay in
AWAITING_STOCK (every carton held) until the warehouse manager releases them - that is how the backend leaves an order
whose cartons came from a production order (OrderStatus javadoc).

Credentials: SF_DEMO_USER / SF_DEMO_PASSWORD (environment only; never printed, never stored). The other accounts
the flow needs (the customers, the approver) are the seeded demo accounts, signed in with the same password unless
SF_DEMO_PASSWORD_<USERNAME> (upper case, other characters as _) says otherwise.

Python 3.9+, standard library only.
"""

import argparse
import datetime as dt
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

VN = dt.timezone(dt.timedelta(hours=7))
LOGIN_PATH = "/api/v1/identity/auth/login"
NAMESPACE = uuid.UUID("5f3c1a52-7a1e-4d1b-9a53-0d7a7c1e3f10")   # fixed: the idempotency keys of the quotes derive from it

# --------------------------------------------------------------------------------------------------- what to create

# The carriers (D13). Codes are 2-32 upper-case letters, digits, '-' or '_'. XT-NAMBAC is the carrier the standard test
# seed declares too, so a server that has it keeps its row (found by code, never created twice).
CARRIERS = [
    dict(code="XT-NAMBAC", name="Vận tải Nam Bắc", contact="Điều phối Nam Bắc", phone="0909111222",
         connection="MANUAL"),
    dict(code="XT-THANGLONG", name="Vận tải Thăng Long (kết nối webhook)", contact="Điều phối Thăng Long",
         phone="0909333444", connection="WEBHOOK_LOAD"),
]

# The vehicle types the demo uses. They are presets of the migrations; the values below are only used if one is missing.
VEHICLE_TYPES = [
    dict(code="PICKUP", name="Pickup truck (xe bán tải)", kind="PICKUP", length=1500, width=1140, height=500,
         gusset=0, payload="800", count=10),
    dict(code="TRUCK-1T25", name="Box truck 1.25 t (xe tải 1,25 tấn)", kind="TRUCK", length=3100, width=1600,
         height=1600, gusset=0, payload="1250", count=10),
    dict(code="TRUCK-2T5", name="Box truck 2.5 t (xe tải 2,5 tấn)", kind="TRUCK", length=4300, width=1800,
         height=1800, gusset=0, payload="2500", count=10),
]
SMALL_VEHICLE = "PICKUP"        # the "small vehicle type" of D23: a carton (60x40x50 cm) fits four to five on one
MIN_VEHICLES_AVAILABLE = 2      # a plan may use no more vehicles of a type than this count

# The orders. `customer` is the local part of the customer's e-mail (caphemoc@khachhang.example ...). The quantities
# are cartons of the customer's own SKU. `plan=True`: part of the two-vehicle load plan (D23).
ORDERS = [
    dict(key="D14", customer="caphemoc", sku="LY-GIAY-2L-12OZ-CPM", cartons=3, unit_price=900,
         payment="split", window_in_days=None, plan=False,
         label="D14: BEFORE_DELIVERY instalment unpaid, delivery window starts tomorrow"),
    dict(key="D23-A", customer="trasuamay", sku="TUI-GIAY-QUAI-M-TSM", cartons=4, unit_price=4200,
         payment="full", window_in_days=0, plan=True,
         label="D23: cartons held, no payment block (half of the two-vehicle plan)"),
    dict(key="D23-B", customer="banhmiviet", sku="HOP-BANH-KRAFT-M-BMV", cartons=4, unit_price=2500,
         payment="full", window_in_days=0, plan=True,
         label="D23: cartons held, no payment block (other half of the two-vehicle plan)"),
]

ACTOR_DEFAULTS = {          # role -> username; None = the user of SF_DEMO_USER
    "sales": None, "accountant": None, "coordinator": None, "warehouse": None, "admin": None,
    "approver": "truongkd",
}


# ------------------------------------------------------------------------------------------------------------ HTTP

class ApiError(Exception):
    def __init__(self, status, code, message, fields=None, details=None, path=""):
        super().__init__("%s %s %s" % (status, code, message))
        self.status, self.code, self.message, self.fields, self.details, self.path = \
            status, code, message, fields or [], details, path

    def describe(self):
        text = "HTTP %s %s: %s" % (self.status, self.code, self.message)
        if self.fields:
            text += " (" + "; ".join("%s: %s" % (f.get("field"), f.get("message")) for f in self.fields[:5]) + ")"
        return text


class Fatal(Exception):
    """A situation the script will not guess its way out of."""


def log(text=""):
    print(text, flush=True)


class Api:
    """One signed-in user. The password and the token never leave this object (nothing prints them)."""

    def __init__(self, base_url, username, password, dry_run):
        self.base = base_url.rstrip("/")
        self.username = username
        self._password = password
        self._token = None
        self.dry_run = dry_run

    # -- sign in (the only POST a dry run makes: a session is not data)
    def login(self):
        for attempt in range(4):
            try:
                answer = self._send("POST", LOGIN_PATH, {"username": self.username, "password": self._password},
                                    auth=False)
                self._token = answer["data"]["accessToken"]
                return
            except ApiError as error:
                if error.status == 429 and attempt < 3:
                    wait = 62
                    log("  (sign-in limit reached; waiting %ds)" % wait)
                    time.sleep(wait)
                    continue
                if error.status in (400, 401, 403):
                    raise Fatal("cannot sign in as %s: %s" % (self.username, error.describe()))
                raise

    def get(self, path, query=None):
        return self.call("GET", path, query=query)

    def call(self, method, path, body=None, query=None, form=None):
        if method != "GET" and self.dry_run:
            raise AssertionError("dry run attempted %s %s" % (method, path))
        if self._token is None:
            self.login()
        url_path = "/api/v1" + path
        if query:
            url_path += "?" + urllib.parse.urlencode([(k, v) for k, v in query.items() if v is not None])
        for attempt in range(3):
            try:
                return self._send(method, url_path, body, form=form)
            except ApiError as error:
                if error.status == 401 and attempt == 0:      # token expired: sign in once more
                    self._token = None
                    self.login()
                    continue
                if error.status == 429 and attempt < 2:
                    time.sleep(31)
                    continue
                raise

    def _send(self, method, path, body=None, auth=True, form=None):
        headers = {"Accept": "application/json"}
        data = None
        if form is not None:
            data, content_type = form
            headers["Content-Type"] = content_type
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if auth and self._token:
            headers["Authorization"] = "Bearer " + self._token
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                raw = response.read()
        except urllib.error.HTTPError as http:
            raw = http.read()
            payload = {}
            try:
                payload = json.loads(raw.decode("utf-8")) if raw else {}
            except ValueError:
                pass
            raise ApiError(http.code, payload.get("errorCode") or "HTTP_%d" % http.code,
                           payload.get("message") or http.reason, payload.get("fieldErrors"), payload.get("details"),
                           path)
        except urllib.error.URLError as net:
            raise Fatal("cannot reach %s (%s)" % (self.base, net.reason))
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            return {}

    def data(self, path, query=None):
        return self.get(path, query).get("data")


def multipart(fields, file_field, filename, content_type, content):
    boundary = "----wf4demo" + secrets.token_hex(12)
    out = []
    for name, value in fields.items():
        out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, name, value))
                   .encode("utf-8"))
    out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n"
                % (boundary, file_field, filename, content_type)).encode("utf-8"))
    out.append(content)
    out.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))
    return b"".join(out), "multipart/form-data; boundary=" + boundary


def blank_pdf():
    """A valid one-page PDF: the 'signed contract' scan the contract signature wants as its file."""
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>"]
    out = b"%PDF-1.4\n"
    offsets = []
    for number, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % number + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 4\n0000000000 65535 f \n" + b"".join(b"%010d 00000 n \n" % o for o in offsets)
    out += b"trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % xref
    return out


# ------------------------------------------------------------------------------------------------------ the run

class Run:
    def __init__(self, args):
        self.args = args
        self.dry = args.dry_run
        self.base = args.base_url
        user = os.environ.get("SF_DEMO_USER", "").strip()
        self._main_password = os.environ.get("SF_DEMO_PASSWORD", "")
        if not user or not self._main_password:
            raise Fatal("set SF_DEMO_USER and SF_DEMO_PASSWORD in the environment")
        self.main_user = user
        self.apis = {}
        self.actor_names = dict(ACTOR_DEFAULTS)
        for role, name in (a.split("=", 1) for a in args.actor):
            if role not in self.actor_names:
                raise Fatal("--actor: unknown role %r (one of %s)" % (role, ", ".join(sorted(self.actor_names))))
            self.actor_names[role] = name
        self.customer_users = dict(c.split("=", 1) for c in args.customer_user)
        self.today = dt.datetime.now(VN).date()
        self.todo = 0           # things a real run would do (dry run) / did
        self.warnings = []
        self.summary = {"orders": [], "carriers": [], "vehicles": [], "staging": None, "plan": None, "users": {}}
        self.created_secret_carrier = False

    # -- accounts
    def api(self, username):
        username = username or self.main_user
        if username not in self.apis:
            env = "SF_DEMO_PASSWORD_" + re.sub(r"[^A-Za-z0-9]", "_", username).upper()
            password = os.environ.get(env) or self._main_password
            self.apis[username] = Api(self.base, username, password, self.dry)
        return self.apis[username]

    def actor(self, role):
        name = self.actor_names[role]
        if name is None:
            name = self.main_user
        self.summary["users"].setdefault(name, set()).add(role)
        return self.api(name)

    def warn(self, text):
        self.warnings.append(text)
        log("  [warn] " + text)

    def say(self, mark, text):
        log("  [%s] %s" % (mark, text))

    def plan_or_do(self, text):
        """Announce a change: a dry run only says it would; a real run says it does (and the caller then does it)."""
        self.todo += 1
        self.say("todo" if self.dry else "do  ", text)
        return not self.dry

    # -- warehouse
    def warehouse(self):
        if not hasattr(self, "_wh"):
            api = self.actor("warehouse")
            rows = api.data("/warehouses", {"size": 100})["items"]
            match = [w for w in rows if w["prefix"].upper() == self.args.warehouse.upper()]
            if not match:
                raise Fatal("no warehouse with prefix %s (have %s)" % (self.args.warehouse,
                                                                      ", ".join(w["prefix"] for w in rows)))
            if match[0]["status"] != "ACTIVE":
                raise Fatal("warehouse %s is not ACTIVE" % self.args.warehouse)
            self._wh = match[0]
        return self._wh

    # ---------------------------------------------------------------------------------------------- carriers (D13)
    def carriers(self):
        log("\n== D13 carriers")
        api = self.actor("warehouse")
        rows = api.data("/fulfillment/carriers", {"size": 200})["items"]
        by_code = {c["code"].upper(): c for c in rows}
        for spec in CARRIERS:
            existing = by_code.get(spec["code"])
            if existing:
                conn = existing["connection"]["connectionType"]
                note = ""
                if conn != spec["connection"]:
                    note = " (connection is %s, expected %s: left as it is)" % (conn, spec["connection"])
                if existing["status"] != "ACTIVE":
                    note += " (INACTIVE: it takes no new load)"
                    self.warn("carrier %s exists but is INACTIVE; activate it on the carrier screen" % spec["code"])
                self.say("ok  ", "carrier %s \"%s\" exists, %s%s" % (existing["code"], existing["name"], conn, note))
                self.summary["carriers"].append((existing["code"], existing["name"], conn, existing["status"]))
                continue
            if self.plan_or_do("create carrier %s \"%s\" (%s)" % (spec["code"], spec["name"], spec["connection"])):
                connection = {"connectionType": spec["connection"]}
                if spec["connection"] == "WEBHOOK_LOAD":
                    secret = os.environ.get("SF_DEMO_WEBHOOK_SECRET") or secrets.token_hex(32)
                    connection.update({"webhookEnabled": True, "webhookSecret": secret})
                body = {"code": spec["code"], "name": spec["name"], "contactName": spec["contact"],
                        "contactPhone": spec["phone"], "status": "ACTIVE", "connection": connection}
                try:
                    made = api.call("POST", "/fulfillment/carriers", body)["data"]
                except ApiError as error:
                    if error.code == "SECRETS_UNAVAILABLE":
                        self.todo -= 1
                        self.warn("carrier %s not created: the server has no secrets key (STOCKFLOW_SECRETS_KEY), "
                                  "so it cannot store a webhook secret (503 SECRETS_UNAVAILABLE)" % spec["code"])
                        continue
                    raise
                self.summary["carriers"].append((made["code"], made["name"], spec["connection"], made["status"]))
                if spec["connection"] == "WEBHOOK_LOAD" and not os.environ.get("SF_DEMO_WEBHOOK_SECRET"):
                    self.created_secret_carrier = True

    def vehicle_types(self):
        log("\n== D13 vehicle types")
        api = self.actor("warehouse")
        rows = api.data("/fulfillment/vehicle-types", {"size": 200})["items"]
        by_code = {v["code"].upper(): v for v in rows}
        for spec in VEHICLE_TYPES:
            found = by_code.get(spec["code"])
            if found is None:
                if self.plan_or_do("create vehicle type %s" % spec["code"]):
                    body = {"code": spec["code"], "name": spec["name"], "kind": spec["kind"],
                            "innerLengthMm": spec["length"], "innerWidthMm": spec["width"],
                            "innerHeightMm": spec["height"], "gussetMm": spec["gusset"],
                            "maxPayloadKg": spec["payload"], "availableCount": spec["count"], "status": "ACTIVE"}
                    found = api.call("POST", "/fulfillment/vehicle-types", body)["data"]
                else:
                    continue
            elif found["status"] != "ACTIVE" or found["availableCount"] < MIN_VEHICLES_AVAILABLE:
                if self.plan_or_do("set vehicle type %s ACTIVE with at least %d vehicles available (is %s, %d)"
                                   % (found["code"], MIN_VEHICLES_AVAILABLE, found["status"], found["availableCount"])):
                    body = {"code": found["code"], "name": found["name"], "kind": found["kind"],
                            "innerLengthMm": found["innerLengthMm"], "innerWidthMm": found["innerWidthMm"],
                            "innerHeightMm": found["innerHeightMm"], "gussetMm": found["gussetMm"],
                            "doorWidthMm": found.get("doorWidthMm"), "doorHeightMm": found.get("doorHeightMm"),
                            "maxPayloadKg": found["maxPayloadKg"],
                            "availableCount": max(found["availableCount"], spec["count"]),
                            "rentalPrice": found.get("rentalPrice"), "status": "ACTIVE", "version": found["version"]}
                    found = api.call("PUT", "/fulfillment/vehicle-types/" + found["vehicleTypeId"], body)["data"]
                else:
                    continue
            else:
                self.say("ok  ", "vehicle type %s: %dx%dx%d mm, %s kg, %d available" % (
                    found["code"], found["innerLengthMm"], found["innerWidthMm"], found["innerHeightMm"],
                    found["maxPayloadKg"], found["availableCount"]))
            self.summary["vehicles"].append((found["code"], found["name"], found["status"]))

    # -------------------------------------------------------------------------------------------- staging area
    def staging(self):
        log("\n== STAGING area of warehouse %s" % self.args.warehouse)
        api = self.actor("warehouse")
        wh = self.warehouse()
        layout = api.data("/warehouses/%s/layout" % wh["id"])
        areas = layout["areas"]
        active = [a for a in areas if a["type"] == "STAGING" and a.get("effectiveStatus", a["status"]) == "ACTIVE"
                  and a.get("locationCode")]
        if active:
            self.say("ok  ", "STAGING area %s (%s) is active" % (active[0]["code"], active[0]["locationCode"]))
            self.summary["staging"] = active[0]["locationCode"]
            return
        inactive = [a for a in areas if a["type"] == "STAGING" and a.get("locationCode")]
        if inactive:
            area = inactive[0]
            if self.plan_or_do("activate STAGING area %s (%s)" % (area["code"], area["locationCode"])):
                made = api.call("PUT", "/areas/%s/status" % area["id"], {"status": "ACTIVE"})["data"]
                self.summary["staging"] = made.get("locationCode") or area["locationCode"]
            return
        taken = {a["code"].upper() for a in areas}
        code = next(c for c in ("CHOXUAT01", "STG01", "CHOXUAT02", "STG02") if c not in taken)
        if not self.plan_or_do("create STAGING area %s in %s" % (code, wh["prefix"])):
            return
        width, length = 12, 6
        spots = [(x, y) for y in range(0, int(wh["mapHeight"]) - length + 1, 2)
                 for x in range(0, int(wh["mapWidth"]) - width + 1, 2)]
        last = None
        for x, y in spots:
            body = {"code": code, "type": "STAGING", "name": "Khu chờ xuất", "obstacle": False,
                    "footprint": {"x": x, "y": y, "width": width, "length": length, "rotation": 0},
                    "location": {"pickable": False, "putawayTarget": False}}
            try:
                made = api.call("POST", "/warehouses/%s/areas" % wh["id"], body)["data"]
                self.summary["staging"] = made.get("locationCode")
                self.say("ok  ", "created STAGING area %s (%s) at %s,%s" % (code, made.get("locationCode"), x, y))
                return
            except ApiError as error:
                if error.code in ("LAYOUT_OVERLAP", "LAYOUT_OUT_OF_BOUNDS"):
                    last = error
                    continue
                raise
        raise Fatal("no free %dx%d spot on the map of %s for a STAGING area (%s)" % (
            width, length, wh["prefix"], last.describe() if last else "?"))

    # ----------------------------------------------------------------------------------------------- the orders
    def orders(self):
        log("\n== D14 / D23 orders (set %r)" % self.args.set)
        customers = self.find_customers()
        for spec in ORDERS:
            if self.args.only_order and spec["key"] not in self.args.only_order:
                continue
            DemoOrder(self, spec, customers[spec["customer"]]).drive()
        self.verify_two_vehicles()

    def find_customers(self):
        api = self.actor("sales")
        rows, page = [], 0
        while True:
            body = api.data("/customers", {"size": 100, "page": page})
            rows += body["items"]
            if not body.get("hasNext"):
                break
            page += 1
        found = {}
        for spec in ORDERS:
            code = spec["customer"]
            if code in found:
                continue
            match = [c for c in rows if (c.get("email") or "").lower().startswith(code + "@")]
            if not match:
                raise Fatal("no customer whose e-mail starts with %s@ (needed by order %s)" % (code, spec["key"]))
            if match[0]["status"] != "ACTIVE":
                raise Fatal("customer %s is %s, not ACTIVE" % (code, match[0]["status"]))
            found[code] = match[0]
        return found

    def customer_api(self, code, customer):
        name = self.customer_users.get(code)
        if name is None:
            try:
                name = self.actor("admin").data("/identity/users/" + customer["userId"])["username"]
            except (ApiError, KeyError):
                raise Fatal("cannot tell which account signs in as customer %s: pass --customer-user %s=<username>"
                            % (code, code))
        self.summary["users"].setdefault(name, set()).add("customer")
        return self.api(name)

    # ----------------------------------------------------------------------- the two-vehicle check (D23, advisory)
    def verify_two_vehicles(self):
        pair = [o for o in self.summary["orders"] if o["plan"]]
        if len(pair) < 2:
            return
        log("\n== D23 check: the two D23 orders on the small vehicle type (trial estimate, stores nothing)")
        if any(o["state"] != "READY" for o in pair):
            self.say("..  ", "the D23 orders are not all ready (cartons held) yet; the check runs once they are")
            return
        if self.dry:
            # a dry run may only GET; the trial estimate is a POST that stores nothing, so it is left for a real run
            self.say("..  ", "trial estimate skipped in a dry run (it is a POST, although it stores nothing)")
            return
        api = self.actor("warehouse")
        vehicle = next((v for v in api.data("/fulfillment/vehicle-types", {"size": 200})["items"]
                        if v["code"].upper() == SMALL_VEHICLE), None)
        if vehicle is None:
            self.warn("vehicle type %s not found; D23 not checked" % SMALL_VEHICLE)
            return
        lines = []
        try:
            for order in pair:
                prev = api.data("/inventory/items/%s/packaging-preview" % order["inventory_item_id"],
                                {"quantity": order["cartons"] * order["units_per_carton"]})
                carton = prev["carton"]
                lines.append({"name": order["sku"], "group": order["number"], "lengthMm": carton["lengthMm"],
                              "widthMm": carton["widthMm"], "heightMm": carton["heightMm"],
                              "quantity": order["cartons"], "rotatable": bool(carton.get("rotatable", True))})
            body = {"lines": lines, "vehicleTypeIds": [vehicle["vehicleTypeId"]], "selectionMode": "SINGLE_TYPE"}
            answer = api.call("POST", "/fulfillment/load-estimates/trial", body)["data"]
        except ApiError as error:
            self.warn("D23 check could not run: %s" % error.describe())
            return
        count = len(answer["vehicles"])
        total = sum(l["quantity"] for l in lines)
        verdict = "OK" if count == 2 and not answer["unplaced"] else "NOT TWO VEHICLES"
        self.say("ok  " if verdict == "OK" else "warn", "%d cartons on %s -> %d vehicle(s) %s, %d unplaced [%s]" % (
            total, SMALL_VEHICLE, count, [len(v["boxes"]) for v in answer["vehicles"]], len(answer["unplaced"]),
            verdict))
        self.summary["plan"] = dict(cartons=total, vehicles=count, verdict=verdict)
        if verdict != "OK":
            self.warn("the D23 pair does not need exactly two %s vehicles: change the cartons in ORDERS" % SMALL_VEHICLE)


class Blocked(Exception):
    pass


class DemoOrder:
    """One demo order, driven by what it is now: every step looks first, and only then acts."""

    def __init__(self, run, spec, customer):
        self.run, self.spec, self.customer = run, spec, customer
        self.request_id = str(uuid.uuid5(NAMESPACE, "%s/%s" % (run.args.set, spec["key"])))
        self.marker = "WF4-DEMO[%s/%s]" % (run.args.set, spec["key"])
        self.quote = None
        self.order = None
        self.order_id = None

    # ---- lookups
    def sales(self):
        return self.run.actor("sales")

    def find_quote(self):
        """The quote of this order: by the marker in its terms (a GET only, so a dry run can do it)."""
        page = 0
        while True:
            body = self.sales().data("/quotes", {"customerId": self.customer["customerId"], "size": 100, "page": page})
            for row in body["items"]:
                quote = self.sales().data("/quotes/" + row["id"])
                if (quote.get("terms") or "").startswith(self.marker):
                    return quote
            if not body.get("hasNext"):
                return None
            page += 1

    def contract(self):
        try:
            return self.sales().data("/orders/%s/contract" % self.order_id)
        except ApiError as error:
            if error.code in ("SALES_CONTRACT_NOT_FOUND", "NOT_FOUND") or error.status == 404:
                return None
            raise

    def window_start(self):
        days = self.spec["window_in_days"]
        return self.run.today + dt.timedelta(days=self.run.args.d14_window_in_days if days is None else days)

    # ---- the state machine
    def drive(self):
        spec = self.spec
        log("\n-- order %s: %s" % (spec["key"], spec["label"]))
        log("   customer %s, %d cartons of %s, window starts in %s" % (
            spec["customer"], spec["cartons"], spec["sku"],
            "%s day(s)" % (self.run.args.d14_window_in_days if spec["window_in_days"] is None
                           else spec["window_in_days"])))
        steps_done = []
        for _ in range(60):
            try:
                step = self.next_step()
            except Blocked as why:
                self.say_final("BLOCKED", str(why))
                return
            if step is None:
                self.finish("READY")
                return
            name, note = step
            if self.run.dry:
                self.run.todo += 1
                self.say_plan(name, note)
                steps_done.append(name)
                # a dry run cannot continue past the first thing it would change: list what follows from the name
                for later in self.following(name):
                    self.say_plan(later, "")
                self.finish("PLAN")
                return
            self.say_plan(name, note, doing=True)
            self.run.todo += 1
            getattr(self, "do_" + name)()
            steps_done.append(name)
        raise Fatal("order %s: more than 60 steps without finishing - stopping" % spec["key"])

    ORDER = ["create_quote", "send_quote", "accept_quote", "convert_quote", "create_contract", "send_contract",
             "sign_contract", "pay_signing", "choose_warehouse", "draw_production_order",
             "submit_production_order", "approve_production_order", "receive_goods", "put_away"]
    TEXT = {
        "create_quote": "draw up the quote (Sales)", "send_quote": "send the quote to the customer (Sales)",
        "accept_quote": "accept the quote (the customer's own login)",
        "convert_quote": "convert the accepted quote into the order",
        "create_contract": "draw up the sales contract (one delivery batch, payment plan)",
        "send_contract": "send the contract", "sign_contract": "record the contract signed (signed file attached)",
        "pay_signing": "record the payment receipt of the instalment on signing (Accounting)",
        "choose_warehouse": "choose the shipping warehouse (stock is held there)",
        "draw_production_order": "draw up the production order for the missing cartons (Production Coordinator)",
        "submit_production_order": "submit the production order for approval",
        "approve_production_order": "approve the production order (the approver, not the drafter)",
        "receive_goods": "receive the cartons against the production order (goods receipt)",
        "put_away": "put the received cartons away into bins (the order's hold completes)",
    }

    def following(self, name):
        """The steps after `name`, for a dry run's plan (it cannot look past what it would change)."""
        rest = self.ORDER[self.ORDER.index(name) + 1:]
        if self.free_stock_covers():
            rest = [s for s in rest if s not in ("draw_production_order", "submit_production_order",
                                                 "approve_production_order", "receive_goods", "put_away")]
        return rest

    def free_stock_covers(self):
        """Whether the cartons free at the warehouse already cover the order (so no production order is needed)."""
        if hasattr(self, "stock_enough"):
            return self.stock_enough
        try:
            wh = self.run.warehouse()
            api = self.run.actor("warehouse")
            item = next(i for i in api.data("/inventory/items", {"sku": self.spec["sku"]})["items"]
                        if i["sku"] == self.spec["sku"])
            factor = api.data("/inventory/items/%s/packaging-preview" % item["id"],
                              {"quantity": 1000000})["factor"]
            free = api.data("/inventory/stock-items/atp", {"sku": self.spec["sku"], "warehouse": wh["prefix"]})
            self.stock_enough = free >= self.spec["cartons"] * factor
        except (ApiError, StopIteration, KeyError, TypeError):
            self.stock_enough = False
        return self.stock_enough

    def say_plan(self, name, note, doing=False):
        text = self.TEXT[name] + (" - " + note if note else "")
        if doing:
            self.run.say("do  ", text)
        else:
            self.run.say("todo", text)

    def say_final(self, mark, text):
        self.run.say(mark.ljust(4), "%s: %s" % (self.spec["key"], text))
        self.record(mark)

    def finish(self, state):
        self.record(state)
        if state == "READY":
            self.run.say("ok  ", "%s: order %s has every carton held at %s; contract, payments and window as below" % (
                self.spec["key"], self.order["orderNumber"], self.run.args.warehouse))

    def record(self, state):
        entry = dict(key=self.spec["key"], state=state, plan=self.spec["plan"], cartons=self.spec["cartons"],
                     sku=self.spec["sku"], customer=self.customer["fullName"], number=None, status=None,
                     contract=None, installments=[], window=None, held=None, held_at=None, inventory_item_id=None,
                     units_per_carton=None)
        if self.order is not None:
            entry["number"] = self.order["orderNumber"]
            entry["status"] = self.order["status"]
        if self.order_id and state in ("READY", "PLAN", "consumed", "BLOCKED"):
            try:
                self.fill_details(entry)
            except ApiError:
                pass
        self.run.summary["orders"].append(entry)

    def fill_details(self, entry):
        contract = self.contract()
        if contract:
            entry["contract"] = contract["contractNumber"]
            entry["window"] = [(b["seq"], b["windowStart"], b["windowEnd"]) for b in contract["deliverySchedule"]]
        installments = self.run.actor("accountant").data("/installments/orders/%s" % self.order_id)
        entry["installments"] = [(i["seq"], i["name"], i["milestone"], i.get("deliveryBatchSeq"), i["amount"],
                                  i["status"], i["paidAmount"]) for i in installments["installments"]]
        coverage = self.run.actor("sales").data("/orders/%s/stock-coverage" % self.order_id)
        entry["held"] = coverage["fullyHeld"]
        entry["held_at"] = coverage.get("warehouseCode")
        line = self.order["lines"][0]
        entry["units_per_carton"] = line["quantity"] // max(self.spec["cartons"], 1)
        items = self.run.actor("warehouse").data("/inventory/items", {"sku": self.spec["sku"]})["items"]
        entry["inventory_item_id"] = next((i["id"] for i in items if i["sku"] == self.spec["sku"]), None)

    def next_step(self):
        """(step, note) to do now, None when the order is where the demo starts from, Blocked when it cannot be."""
        quote = self.find_quote()
        self.quote = quote
        if quote is None:
            self.check_customer_sku()
            return "create_quote", ""
        status = quote["status"]
        if status == "DRAFT":
            return "send_quote", ""
        if status == "SENT":
            return "accept_quote", ""
        if status == "ACCEPTED":
            return "convert_quote", ""
        if status != "CONVERTED":
            raise Blocked("its quote %s is %s; use --set <new name> to draw a fresh one" % (quote["quoteNumber"], status))
        self.order_id = quote["convertedOrder"]["orderId"]
        self.order = self.sales().data("/orders/" + self.order_id)
        order_status = self.order["status"]
        if order_status == "DRAFT":
            contract = self.contract()
            if contract is None or contract["status"] == "CANCELLED":
                return "create_contract", ""
            if contract["status"] == "DRAFT":
                return "send_contract", ""
            raise Blocked("order is DRAFT but its contract is %s" % contract["status"])
        if order_status == "PENDING_CONTRACT":
            return "sign_contract", ""
        if order_status == "PENDING_PAYMENT":
            return "pay_signing", ""
        if order_status not in ("CONFIRMED", "AWAITING_STOCK"):
            if order_status == "ON_HOLD":
                raise Blocked("order %s is ON_HOLD (%s)" % (self.order["orderNumber"], self.order.get("holdReason")))
            raise Blocked("order %s is already %s: the demo data was used; use --set <new name> for a fresh one"
                          % (self.order["orderNumber"], order_status))
        return self.warehouse_step()

    def warehouse_step(self):
        wh = self.run.warehouse()
        options = self.sales().data("/orders/%s/shipping-warehouse/options" % self.order_id)
        chosen = options.get("chosenWarehouseId")
        if chosen is None:
            self.stock_enough = self.enough_without_production(options, wh)
            return "choose_warehouse", ("the free stock already covers it" if self.stock_enough
                                         else "stock will fall short: a production order follows")
        if chosen != wh["id"]:
            raise Blocked("the order ships from another warehouse than %s" % wh["prefix"])
        coverage = self.sales().data("/orders/%s/stock-coverage" % self.order_id)
        if coverage["fullyHeld"]:
            return None
        orders = self.run.actor("coordinator").data("/production-orders", {"salesOrderId": self.order_id, "size": 50})["items"]
        live = [p for p in orders if p["status"] not in ("CANCELLED", "CLOSED")]
        if not live:
            return "draw_production_order", "%d carton(s) short" % sum(l["shortCartons"] for l in coverage["lines"])
        po = live[0]
        self.po = po
        if po["status"] == "DRAFT":
            return "submit_production_order", po["code"]
        if po["status"] == "PENDING_APPROVAL":
            return "approve_production_order", po["code"]
        if po["status"] == "SAMPLE_PENDING":
            raise Blocked("production order %s waits for a print sample approval" % po["code"])
        warehouse = self.run.actor("warehouse")
        receipts = warehouse.data("/goods-receipts", {"productionOrderId": po["id"], "size": 50})["items"]
        if any(r["status"] == "DRAFT" for r in receipts):
            return "receive_goods", "finish the draft receipt"
        for r in receipts:
            if r["status"] == "IN_PUTAWAY" and self.open_putaway_tasks(r["id"]):
                return "put_away", r["number"]
        remaining = self.remaining_cartons(po)
        if remaining > 0 and po["status"] in ("SENT", "ACCEPTED", "IN_PRODUCTION", "PARTIALLY_RECEIVED"):
            return "receive_goods", "%d carton(s) of %s" % (remaining, po["code"])
        raise Blocked("production order %s is %s but the order is still short; check it by hand" % (po["code"],
                                                                                                  po["status"]))

    def enough_without_production(self, options, wh):
        try:
            warehouse = next(w for w in options["warehouses"] if w["warehouseId"] == wh["id"])
            return all(l["availableCartons"] >= l["cartons"] for l in warehouse["lines"])
        except (StopIteration, KeyError):
            return False

    def check_customer_sku(self):
        skus = self.sales().data("/customer-skus", {"customerId": self.customer["customerId"]})
        if not any(s["sku"] == self.spec["sku"] and s.get("status", "ACTIVE") == "ACTIVE" for s in skus):
            raise Fatal("customer %s has no ACTIVE SKU %s" % (self.spec["customer"], self.spec["sku"]))

    def remaining_cartons(self, po):
        return sum(max(line["cartons"] - line["receivedCartons"], 0) for line in po["lines"])

    def open_putaway_tasks(self, receipt_id):
        tasks = self.run.actor("warehouse").data("/putaway-tasks", {"receiptId": receipt_id, "size": 200})["items"]
        return [t for t in tasks if t["status"] not in ("DONE", "CANCELLED")]

    # ---- the steps (each one does exactly one thing and is only called when the state says it is due)
    def do_create_quote(self):
        spec = self.spec
        valid_until = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=45)).strftime("%Y-%m-%dT%H:%M:%SZ")
        body = {"requestId": self.request_id, "customerId": self.customer["customerId"], "validUntil": valid_until,
                "terms": "%s %s" % (self.marker, spec["label"]),
                "requestedDeliveryDate": self.window_start().isoformat(),
                "lines": [{"sku": spec["sku"], "cartons": spec["cartons"],
                           "tiers": [{"minQuantity": 1, "unitPrice": spec["unit_price"]}]}], "fees": []}
        self.sales().call("POST", "/quotes", body)

    def do_send_quote(self):
        self.sales().call("POST", "/quotes/%s/sending" % self.quote["id"], {"version": self.quote["version"]})

    def do_accept_quote(self):
        api = self.run.customer_api(self.spec["customer"], self.customer)
        api.call("POST", "/quotes/%s/acceptance" % self.quote["id"], {"version": self.quote["version"]})

    def do_convert_quote(self):
        self.sales().call("POST", "/quotes/%s/conversion" % self.quote["id"], {"version": self.quote["version"]})

    def do_create_contract(self):
        line = self.order["lines"][0]
        start = self.window_start()
        schedule = [{"windowStart": start.isoformat(), "windowEnd": (start + dt.timedelta(days=7)).isoformat(),
                     "lines": [{"orderLineId": line["lineId"], "cartons": self.spec["cartons"]}]}]
        if self.spec["payment"] == "split":
            plan = [{"name": "Đặt cọc khi ký", "milestone": "ON_SIGNING", "percent": 30},
                    {"name": "Thanh toán trước giao", "milestone": "BEFORE_DELIVERY", "percent": 70,
                     "deliveryBatchSeq": 1}]
        else:
            plan = [{"name": "Thanh toán khi ký", "milestone": "ON_SIGNING", "percent": 100}]
        self.sales().call("POST", "/orders/%s/contract" % self.order_id,
                          {"deliverySchedule": schedule, "paymentPlan": plan})

    def do_send_contract(self):
        contract = self.contract()
        self.sales().call("POST", "/orders/%s/contract/sending" % self.order_id, {"version": contract["version"]})

    def do_sign_contract(self):
        contract = self.contract()
        form = multipart({"version": contract["version"], "signedOn": self.run.today.isoformat()}, "file",
                         "%s-signed.pdf" % contract["contractNumber"], "application/pdf", blank_pdf())
        self.sales().call("POST", "/orders/%s/contract/signature" % self.order_id, form=form)

    def do_pay_signing(self):
        accountant = self.run.actor("accountant")
        standing = accountant.data("/installments/orders/%s" % self.order_id)
        due = [i for i in standing["installments"] if i["milestone"] == "ON_SIGNING" and i["outstanding"] > 0]
        for installment in due:
            amount = int(round(installment["outstanding"]))
            reference = "WF4-DEMO-%s-%s-DOT%d" % (self.run.args.set, self.spec["key"], installment["seq"])
            existing = accountant.data("/payment-receipts", {"reference": reference, "size": 5})["items"]
            if any(r["status"] == "RECORDED" for r in existing):
                self.run.say("ok  ", "receipt %s is already recorded" % reference)
                continue
            accountant.call("POST", "/payment-receipts", {
                "customerId": self.customer["customerId"], "reference": reference,
                "receivedOn": self.run.today.isoformat(), "note": "WF4 demo data",
                "lines": [{"installmentId": installment["installmentId"], "amount": amount}]})
        # the order is confirmed by an event handled after the receipt: wait for it
        self.wait("the order to be CONFIRMED", lambda: self.sales().data("/orders/" + self.order_id)["status"]
                  in ("CONFIRMED", "AWAITING_STOCK"))

    def do_choose_warehouse(self):
        wh = self.run.warehouse()
        self.sales().call("PUT", "/orders/%s/shipping-warehouse" % self.order_id, {"warehouseId": wh["id"]})

    def do_draw_production_order(self):
        producers = self.run.actor("coordinator").data("/producers", {"size": 100})["items"]
        active = [p for p in producers if p["status"] == "ACTIVE"]
        if not active:
            raise Fatal("no ACTIVE producer to send the production order to")
        coordinator = self.run.actor("coordinator")
        coordinator.call("POST", "/production-orders", {"salesOrderId": self.order_id, "deliveryBatchSeq": 1,
                                                        "producerId": active[0]["id"]})

    def do_submit_production_order(self):
        self.run.actor("coordinator").call("POST", "/production-orders/%s/submission" % self.po["id"],
                                           {"version": self.po["version"]})

    def do_approve_production_order(self):
        approver = self.run.actor("approver")
        if approver.username == self.po.get("drawnUpByName") or approver.username == self.run.actor("coordinator").username:
            raise Fatal("the approver of a production order must not be the user who drew it up: pass --actor "
                        "approver=<another user>")
        approver.call("POST", "/production-orders/%s/approval" % self.po["id"], {"version": self.po["version"]})

    def do_receive_goods(self):
        warehouse = self.run.actor("warehouse")
        po = self.run.actor("coordinator").data("/production-orders/" + self.po["id"])
        receipts = warehouse.data("/goods-receipts", {"productionOrderId": po["id"], "size": 50})["items"]
        draft = next((r for r in receipts if r["status"] == "DRAFT"), None)
        if draft is None:
            draft = warehouse.call("POST", "/goods-receipts/production", {
                "productionOrderId": po["id"], "deliveryNote": "WF4-DEMO-%s" % self.spec["key"],
                "note": "WF4 demo data"})["data"]
        wh = self.run.warehouse()
        receiving = self.area_code(wh, "RECEIVING")
        lines = []
        for line in po["lines"]:
            count = line["cartons"] - line["receivedCartons"]
            if count > 0:
                lines.append({"productionOrderLineId": line["id"], "locationCode": receiving,
                              "packages": [{} for _ in range(count)]})
        warehouse.call("PUT", "/goods-receipts/%s/lines" % draft["id"], {"lines": lines})
        warehouse.call("POST", "/goods-receipts/%s/confirmation" % draft["id"], {})

    def area_code(self, wh, kind):
        layout = self.run.actor("warehouse").data("/warehouses/%s/layout" % wh["id"])
        for area in layout["areas"]:
            if area["type"] == kind and area.get("effectiveStatus", area["status"]) == "ACTIVE" and area.get("locationCode"):
                return area["locationCode"]
        raise Fatal("warehouse %s has no ACTIVE %s area" % (wh["prefix"], kind))

    def do_put_away(self):
        warehouse = self.run.actor("warehouse")
        po = self.po
        receipts = warehouse.data("/goods-receipts", {"productionOrderId": po["id"], "size": 50})["items"]
        for receipt in receipts:
            for _ in range(200):
                tasks = self.open_putaway_tasks(receipt["id"])
                if not tasks:
                    break
                task = warehouse.data("/putaway-tasks/" + tasks[0]["id"])
                if not task["suggestions"]:
                    raise Fatal("no bin suggested for carton %s: the warehouse has no free bin" % task.get("lpn"))
                warehouse.call("POST", "/putaway-tasks/%s/confirmations" % task["id"],
                               {"locationCode": task["suggestions"][0]["locationCode"], "lpn": task["lpn"]})
        # holding what arrived is done by an event: wait until the order is covered
        self.wait("the order's stock to be held", lambda: self.sales().data(
            "/orders/%s/stock-coverage" % self.order_id)["fullyHeld"])

    def wait(self, what, check, timeout=90):
        end = time.time() + timeout
        while time.time() < end:
            if check():
                return
            time.sleep(2)
        raise Fatal("timed out waiting for %s (order %s)" % (what, self.spec["key"]))


# ------------------------------------------------------------------------------------------------------- report

def money(value):
    return "{:,.0f}".format(value).replace(",", ".")


def report(run):
    log("\n" + "=" * 78)
    log("DEMO DATA SUMMARY (%s)" % ("DRY RUN: nothing was changed" if run.dry else "run finished"))
    log("=" * 78)
    log("Server: %s   Warehouse: %s   Run date (Vietnam): %s   Set: %s" % (
        run.base, run.args.warehouse, run.today.isoformat(), run.args.set))
    if run.summary["carriers"]:
        log("\nCarriers (D13)")
        for code, name, conn, status in run.summary["carriers"]:
            log("  %-14s %-42s %-13s %s" % (code, name, conn, status))
    if run.summary["vehicles"]:
        log("\nVehicle types: " + ", ".join("%s (%s)" % (c, s) for c, n, s in run.summary["vehicles"]))
    if run.summary["staging"]:
        log("\nSTAGING location: %s" % run.summary["staging"])
    if run.summary["orders"]:
        log("\nOrders")
        for o in run.summary["orders"]:
            log("  %-6s %-20s %-18s %2d cartons of %s  [%s]" % (o["key"], o["number"] or "(not created yet)",
                                                              o["status"] or "-", o["cartons"], o["sku"], o["state"]))
            log("         customer: %s   contract: %s" % (o["customer"], o["contract"] or "-"))
            for seq, start, end in (o["window"] or []):
                log("         delivery batch %d: window %s .. %s%s" % (
                    seq, start, end, "   <- starts tomorrow" if start == (run.today + dt.timedelta(days=1)).isoformat()
                    else ("   <- starts today" if start == run.today.isoformat() else "")))
            for seq, name, milestone, batch, amount, status, paid in o["installments"]:
                log("         instalment %d: %s [%s%s] %s VND - %s%s" % (
                    seq, name, milestone, (", batch %d" % batch) if batch else "", money(amount), status,
                    (" (paid %s)" % money(paid)) if paid else ""))
            if o["held"] is not None:
                log("         stock held at %s: %s" % (o["held_at"] or "-", "every carton" if o["held"] else "NOT every carton"))
    if run.summary["plan"]:
        p = run.summary["plan"]
        log("\nLoad plan check (D23, %s): %d cartons -> %d vehicle(s) [%s]" % (SMALL_VEHICLE, p["cartons"],
                                                                         p["vehicles"], p["verdict"]))
        log("  Plan these two orders together: the D14 order is kept apart (its handover is blocked on purpose).")
    log("\nAccounts the presenter signs in with (the password is the demo password of the server; not printed here)")
    for username, roles in sorted(run.summary["users"].items()):
        log("  %-14s used by this script as: %s" % (username, ", ".join(sorted(roles))))
    log("  Live demo: quanly.hn (warehouse manager: release, load plan, handover), kho.hn1 / kho.hn2 (warehouse staff:"
        " picking, carton check, loading),")
    log("             sales1 (Sales: partial-shipment flag, delivery window change), ketoan (Accounting: payment receipts),")
    log("             kh.caphemoc (customer of the D14 order)   [seeded accounts; the backend has no separate Dispatch Staff role]")
    if run.created_secret_carrier:
        log("\nNote: the webhook carrier got a random signing secret that nobody saw. Set SF_DEMO_WEBHOOK_SECRET before the "
            "run (or re-enter the secret on the carrier screen) if you want to sign test events yourself.")
    if run.warnings:
        log("\nWarnings")
        for w in run.warnings:
            log("  - " + w)
    log("\n%s %d change(s)." % ("A real run would make" if run.dry else "Made", run.todo))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Create the WF4 demo data through the public API (idempotent).")
    parser.add_argument("--base-url", required=True, help="server root, e.g. http://localhost:8080 (no default)")
    parser.add_argument("--dry-run", action="store_true",
                        help="only GET (plus the sign-in) and print what a real run would do")
    parser.add_argument("--warehouse", default="HAN", help="prefix of the demo warehouse (default HAN)")
    parser.add_argument("--set", default="1", help="name of the data set; a new name makes a fresh set of orders "
                                                   "(use it after the first set was consumed by a demo)")
    parser.add_argument("--d14-window-in-days", type=int, default=1,
                        help="the D14 order's delivery window starts this many days after the run date (default 1)")
    parser.add_argument("--only", action="append", choices=["carriers", "vehicles", "staging", "orders"],
                        help="run only this part (repeatable); default: all")
    parser.add_argument("--only-order", action="append", choices=[o["key"] for o in ORDERS],
                        help="with orders: only this order (repeatable)")
    parser.add_argument("--actor", action="append", default=[], metavar="ROLE=USERNAME",
                        help="who acts in a role (%s); default: SF_DEMO_USER, the approver truongkd"
                             % ", ".join(sorted(ACTOR_DEFAULTS)))
    parser.add_argument("--customer-user", action="append", default=[], metavar="CODE=USERNAME",
                        help="the account of a customer, if it cannot be looked up (e.g. caphemoc=kh.caphemoc)")
    args = parser.parse_args(argv)
    parts = args.only or ["carriers", "vehicles", "staging", "orders"]
    try:
        run = Run(args)
        log("WF4 demo data - %s - %s" % (args.base_url, "DRY RUN" if args.dry_run else "REAL RUN"))
        if "carriers" in parts:
            run.carriers()
        if "vehicles" in parts:
            run.vehicle_types()
        if "staging" in parts:
            run.staging()
        if "orders" in parts:
            run.orders()
        report(run)
        return 3 if any(o["state"] == "BLOCKED" for o in run.summary["orders"]) else 0
    except Fatal as stop:
        log("\nSTOPPED: %s" % stop)
        return 2
    except ApiError as error:
        log("\nSTOPPED by the server on %s: %s" % (error.path, error.describe()))
        return 1


if __name__ == "__main__":
    sys.exit(main())
