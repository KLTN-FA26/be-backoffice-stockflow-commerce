# Issuing Blanks by Carton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A blank hold drawn from a carton is issued to the print shop only on a scan of that carton's LPN, and the
pending list shows which carton to bring.

**Architecture:** Production-only. `MaterialIssueService.requireScanned` already reads the chosen hold's `HoldView`,
which carries `lpn` / `wholeCarton` since the picking-by-carton change; it picks the scan rule by `lpn`. `pending`
copies the two fields onto `MaterialIssues.PendingHold`. Inventory, the ledger and the schema do not change.

**Tech Stack:** Java 21, Spring Boot 3.5 / Modulith, JUnit 5 + AssertJ, Testcontainers Postgres.

**Spec:** `docs/superpowers/specs/2026-10-29-scrum-473-blank-issue-by-carton-design.md`

## Global Constraints

- No migration, no change under `inventory/`, `db/migration/`, or to `IssueReservedStockCommand`.
- Refusal: `ErrorCode.ISSUE_ITEM_MISMATCH` (409), message exactly `"This hold is in carton <LPN>; scan its label"`;
  error code and bundle texts unchanged.
- No scanned code at all keeps working for every hold (SCRUM-397 contract). A hold with `lpn == null` keeps the
  SKU-or-barcode rule unchanged.
- LPN comparison: trimmed, case-insensitive (LPNs are stored upper case).
- Identifiers, javadoc and commit messages in English; javadoc explains why.
- Never edit sources while `mvn` runs; commits only with the owner's go-ahead.

## Review Focus

1. A carton hold scanned with its LPN in lower case and with surrounding blanks: accepted (Task 1, test 1).
2. A carton hold scanned with another carton's LPN: refused, nothing moves (Task 1, test 1).
3. A hold that is part of an opened carton (`wholeCarton = false`): still needs its LPN, not the SKU (Task 1, test 2).
4. A carton hold issued with no scanned code: still issued (Task 1, test 2).
5. A loose hold scanned with its SKU: still issued (existing `SampleToPackingIntegrationTest`, re-run in Task 1).

---

### Task 1: Carton holds are scanned by their LPN

**Files:**
- Modify: `src/main/java/com/stockflow/production/internal/service/MaterialIssues.java` (`PendingHold`, ~line 50)
- Modify: `src/main/java/com/stockflow/production/internal/service/MaterialIssueService.java` (`pending` ~line 151,
  `requireScanned` ~line 162)
- Test (create): `src/test/java/com/stockflow/production/MaterialIssueCartonIntegrationTest.java`

**Interfaces:**
- Produces: `record PendingHold(UUID reservationId, String sku, String lotNumber, String locationCode, int quantity,
  String barcode, String lpn, boolean wholeCarton)` — the two new components last.
- Consumes: `HoldView.lpn()`, `HoldView.wholeCarton()` (inventory api, already present).

- [ ] **Step 1: Write the failing tests**

New class with the same annotations, autowired beans, `setUp` users and `PRINT_SHOP` / `BLANK` constants as
`MaterialIssueIntegrationTest`. Fixture, per test, in one transaction:
- a fresh unit of the blank's item: `unit_of_measure` = `"CT" + 6 random upper-case hex chars`, `factor` 4, inserted
  into `inventory.uom_conversions` for the item of `ProductionFixtures.BLANK`;
- `carton(int left)` → LPN: one `inventory.stock_package` (`lpn = "LPN-IT-" + random`, `nominal_qty` 4,
  `received_qty` 4, `location_code` `HCM-A01-1-B`, `status` IN_STOCK, `unopened = (left == 4)`) and one AVAILABLE
  `stock_item` of the blank in it with `on_hand = left`, `received_at = '2000-01-01'` — older than any loose blank
  `stockBlanks` makes, so FIFO takes the carton first (as `CartonAllocationIntegrationTest.carton` does);
- `sample(int quantity)` → production order id: a SAMPLE of `quantity` blanks submitted for HCM, then
  `workflow.prepress(printer, id, true, null)` (as `sampleBlanksAreHeldFromSubmission`).

```java
@Test
@DisplayName("a hold that is a whole sealed carton is listed with its LPN and issued only on that LPN")
void cartonHoldIsIssuedByItsLpnOnly() {
    String lpn = carton(4);
    UUID lsx = sample(4);
    MaterialIssues.PendingHold hold = issues.pending(storekeeper, lsx).holds().getFirst();
    assertThat(hold.lpn()).isEqualTo(lpn);
    assertThat(hold.wholeCarton()).isTrue();
    assertThat(hold.quantity()).isEqualTo(4);

    for (String wrong : List.of(BLANK.code(), "LPN-IT-NOT-THIS-ONE")) {
        assertThatThrownBy(() -> issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(),
                PRINT_SHOP, hold.reservationId(), hold.locationCode(), wrong)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_ITEM_MISMATCH);
                    assertThat(e.getMessage()).isEqualTo("This hold is in carton " + lpn + "; scan its label");
                });
    }
    assertThat(issues.pending(storekeeper, lsx).outstanding()).as("refused scans moved nothing").isEqualTo(4);

    MaterialIssues.Result issued = issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(),
            PRINT_SHOP, hold.reservationId(), hold.locationCode(), "  " + lpn.toLowerCase(Locale.ROOT) + " "));
    assertThat(issued.issue().quantity()).isEqualTo(4);
    assertThat(issued.order().summary().status()).isEqualTo(ProductionOrder.Status.MATERIAL_ISSUED);
}

@Test
@DisplayName("part of an opened carton also needs its LPN; an issue with no scan still moves it")
void openedCartonHoldNeedsItsLpnUnlessNothingIsScanned() {
    String lpn = carton(2);
    UUID lsx = sample(2);
    MaterialIssues.PendingHold hold = issues.pending(storekeeper, lsx).holds().getFirst();
    assertThat(hold.lpn()).isEqualTo(lpn);
    assertThat(hold.wholeCarton()).isFalse();
    assertThatThrownBy(() -> issues.issue(storekeeper, lsx, new MaterialIssues.Issue(UUID.randomUUID(),
            PRINT_SHOP, hold.reservationId(), hold.locationCode(), BLANK.code())))
            .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_ITEM_MISMATCH));

    MaterialIssues.Result issued = issues.issue(storekeeper, lsx,
            new MaterialIssues.Issue(UUID.randomUUID(), PRINT_SHOP, hold.reservationId()));
    assertThat(issued.issue().quantity()).isEqualTo(2);
}
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `mvn -o -q test -Dtest=MaterialIssueCartonIntegrationTest`
Expected: compilation failure — `PendingHold` has no `lpn()` / `wholeCarton()`.

- [ ] **Step 3: Add `lpn` and `wholeCarton` to `PendingHold`**

Append the two components; `pending` passes `hold.lpn()` and `hold.wholeCarton()`. Rewrite the record's javadoc: a
carton hold (`lpn` set) is confirmed by scanning that LPN, since the hold is deducted from that carton; `wholeCarton`
says the hold is the carton's whole sealed content, so it is brought as it stands.

- [ ] **Step 4: Run the tests and see the scan assertions fail**

Run: `mvn -o -q test -Dtest=MaterialIssueCartonIntegrationTest`
Expected: both tests fail — the SKU scan is accepted (no exception thrown).

- [ ] **Step 5: Pick the scan rule by the hold's LPN in `requireScanned`**

When `scannedCode` is present: if `hold.lpn() != null`, accept only `sameCode(issue.scannedCode(), hold.lpn())`, else
throw `ISSUE_ITEM_MISMATCH` with the message from Global Constraints; otherwise keep the SKU-or-barcode check as is.
Extend the method's javadoc with why: the hold is deducted from that carton, so a SKU scan would let the clerk bring
any carton of the SKU and leave the books disagreeing with the shelf.

- [ ] **Step 6: Run the new tests and the existing scan tests**

Run: `mvn -o -q test -Dtest='MaterialIssueCartonIntegrationTest,MaterialIssueIntegrationTest,SampleToPackingIntegrationTest'`
Expected: BUILD SUCCESS (exit 0).

---

### Task 2: Document the flow

**Files:**
- Modify: `CLAUDE.md` §4, after "Picking by carton (SCRUM-473)"

- [ ] **Step 1: Add a short "Issuing blanks by carton (SCRUM-473)" note**

Two or three sentences: a production hold with an LPN is issued only on that LPN (or with no scan at all, the
SCRUM-397 contract); the blanks still enter the print shop loose, the carton opening or emptying; finished goods are
not put in cartons because G4-Q2 consumes them at completion.

---

### Final verification (after both tasks)

- [ ] `python -I -X utf8 tools/verify.py` → `all static checks passed`
- [ ] `mvn -o verify` in the background → `Tests run: …, Failures: 0, Errors: 0`, BUILD SUCCESS
- [ ] One fresh whole-branch review on the most capable model against the spec
- [ ] Commit (with the owner's go-ahead): `feat(production): SCRUM-473 issue a carton hold by its LPN`, then
  `docs: SCRUM-473 issuing blanks by carton` with the spec, this plan and the `CLAUDE.md` note
