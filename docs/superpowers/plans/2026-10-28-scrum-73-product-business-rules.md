# SCRUM-73 Product Business Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the product-module validation gaps found in the SCRUM-73 re-analysis: `nameEn`/`taxClass` stated in the aggregate, inactive brand/category refused when chosen, and a non-blocking "possible duplicate" warning (SCRUM-87, SCRUM-88).

**Architecture:** Aggregate rules go into `Product` (`draft`, `updateDetails`, `submit`). Reference state (missing / inactive / active) comes through the `ProductRepository` port. Duplicate detection is a pure name-key function in `internal/domain` plus a read-only `ProductDuplicateService` in `internal/service`, fed by one new query on `ProductSearchRepository`. The controller adds the matches to the create, update and detail responses, and serves `GET /api/v1/products/duplicates`. The database half of each rule is **not** on this branch: it is `docs/business-design/db-design/SCRUM-73-schema-change-request.md`, for Tú.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, MapStruct, JUnit 5 + AssertJ + Mockito, Testcontainers.

**Spec:** Jira SCRUM-73 (description rewritten 2026-10-28), SCRUM-87, SCRUM-88; `docs/business-design/db-design/SCRUM-73-schema-change-request.md`.

## Global Constraints

- No file under `src/main/resources/db/migration` (CLAUDE.md §7: the schema is Tú's).
- Identifiers, code and javadoc in English; `messages_vi.properties` and `docs/business-design/**` in Vietnamese where the file already is.
- `ModularityTest` and `ArchitectureTest` must stay green: controllers never import `internal.repository`/`internal.entity`; `internal.domain` imports no Spring/JPA.
- Every new `ErrorCode` gets a key in `messages.properties` and `messages_vi.properties` (`MessageBundleTest`).
- Duplicates **warn, never block** (team decision still open; flipping to block later is a one-line change in the service).
- Commit only with the owner's go-ahead (CLAUDE.md §7). The commit steps below are prepared, not run.
- One full `mvn verify` at the end of the branch; targeted test classes per task. Never edit sources while `mvn` runs.

## Review Focus

1. A legacy row with `tax_class`/`name_en` NULL (bridge or carried rows) must still load and be suspended/discontinued; only `draft`, `updateDetails` and `submit` refuse. Pinned in Task 1 (`legacyRowWithoutDetailsStillMoves`).
2. A product already pointing at a brand that was later deactivated must still be editable when the brand is not changed. Pinned in Task 2 (`keptInactiveBrandIsAccepted`).
3. A name made only of punctuation or spaces must not match every other such product. Pinned in Task 3 (`emptyKeyMatchesNothing`).
4. A product must not be reported as a duplicate of itself after an edit. Pinned in Task 3 (`excludesTheProductItself`) and Task 4 (HTTP).
5. A missing brand or category on a draft means "no check yet", not "match the whole catalogue" (`Specs.eq(null)` returns `all()`). Pinned in Task 3 (`noBrandOrCategoryNoCandidates`).

---

### Task 1: `nameEn` and `taxClass` in the aggregate (SCRUM-87 item 1)

**Files:**
- Modify: `src/main/java/com/stockflow/product/internal/domain/Product.java` (`draft`, `updateDetails`, `submit`, class javadoc)
- Modify: `src/main/java/com/stockflow/common/error/ErrorCode.java` (after `PRODUCT_BRAND_REQUIRED`)
- Modify: `src/main/resources/i18n/messages.properties`, `messages_vi.properties`
- Test: `src/test/java/com/stockflow/product/internal/domain/ProductTest.java`, `src/test/java/com/stockflow/product/internal/service/ProductServiceImplTest.java`

**Interfaces:**
- Produces: `ErrorCode.PRODUCT_DETAILS_REQUIRED` (409, "An English name and a tax class are required before submitting for approval").

- [ ] **Step 1: Write the failing tests** in `ProductTest`
  - `Submit.missingDetailsAreRefusedWithFieldErrors`: a product built with the full constructor in `DRAFT` with brand + category but `nameEn = null`, `taxClass = null` → `submit` throws `BusinessException` with `PRODUCT_DETAILS_REQUIRED`, `fieldErrors` fields exactly `["nameEn", "taxClass"]`; status stays `DRAFT`. A blank `nameEn` (`"  "`) alone gives exactly `["nameEn"]`.
  - `Submit.categoryAndBrandStillComeFirst`: no category and no `nameEn` → `INVALID_PRODUCT_STATUS_TRANSITION`; category but no brand and no `nameEn` → `PRODUCT_BRAND_REQUIRED`.
  - `Code.draftRequiresDetails`: `Product.draft(... nameEn = null ...)`, `nameEn = " "` and `taxClass = null` each throw `IllegalArgumentException`.
  - `UpdateDetails.requiresDetails`: same three cases on `updateDetails`.
  - `SuspendAndResume.legacyRowWithoutDetailsStillMoves`: an `APPROVED` row from the constructor with `nameEn`/`taxClass` null can `suspend` and `resume`.
- [ ] **Step 2: Fix the existing callers that pass null:** `ProductTest` lines ~144, ~308–312 (pass `"Paper cup"`, `TaxClass.STANDARD`); `ProductServiceImplTest.productIn` (`nameEn` `"Paper cup"`) and the two `UpdateProductCommand`s in `updateRecordsChangedFields` (`nameEn` `"New"`; the expected changed-field list becomes `List.of("name", "nameEn")`).
- [ ] **Step 3: Run** `mvn test -Dtest=ProductTest+ProductServiceImplTest` — expected: the new tests FAIL, nothing else.
- [ ] **Step 4: Implement.** `draft` and `updateDetails` call `requireNonBlank(nameEn, "nameEn")` and a `requireTaxClass` (IAE); the constructor stays tolerant. `submit` runs category check, brand check, then collects one `FieldError` per missing field (`nameEn` blank/null, `taxClass` null) with code `PRODUCT_DETAILS_REQUIRED` and throws if any. Javadoc of `submit` names `ck_products_details_on_submit` as the database's statement (pending in the schema change request). Add the error code and both bundle keys (vi: "Cần nhập tên tiếng Anh và nhóm thuế trước khi gửi duyệt").
- [ ] **Step 5: Run** `mvn test -Dtest=ProductTest+ProductServiceImplTest+MessageBundleTest` — expected PASS.
- [ ] **Step 6: Commit** `feat(product): name in English and tax class are required to submit (SCRUM-87)`.

### Task 2: refuse an inactive brand or category when it is chosen (SCRUM-87 item 3)

**Files:**
- Create: `src/main/java/com/stockflow/product/internal/domain/ReferenceStatus.java` — `enum ReferenceStatus { MISSING, INACTIVE, ACTIVE }`
- Modify: `ProductRepository` — replace `boolean categoryExists(UUID)` / `boolean brandExists(UUID)` with `ReferenceStatus categoryStatus(UUID categoryId)` / `ReferenceStatus brandStatus(UUID brandId)`
- Modify: `ProductRepositoryAdapter` (implement from `brands.findById` / `categories.findById` and `isActive()`)
- Modify: `ProductServiceImpl.requireReferences` → `requireReferences(UUID brandId, UUID categoryId, UUID keptBrandId, UUID keptCategoryId)`; `create` passes `null, null`, `update` passes the product's current `brandId()`/`categoryId()`
- Modify: `ErrorCode` (`BRAND_INACTIVE`, `CATEGORY_INACTIVE`, both 409), both bundles
- Test: `ProductServiceImplTest`, new `src/test/java/com/stockflow/product/ProductBusinessRulesIntegrationTest.java`

**Interfaces:**
- Produces: `ReferenceStatus`, `ProductRepository#brandStatus`, `#categoryStatus`, `ErrorCode.BRAND_INACTIVE`, `ErrorCode.CATEGORY_INACTIVE`.

- [ ] **Step 1: Write the failing unit tests** in `ProductServiceImplTest`:
  - `createRefusesInactiveBrand`: `brandStatus(BRAND)` → `INACTIVE` → `BRAND_INACTIVE` with field error `brandId`; `products.save` never called. Same for category → `CATEGORY_INACTIVE` / `categoryId`.
  - `keptInactiveBrandIsAccepted`: update of a DRAFT whose brand is `BRAND`, `brandStatus(BRAND)` → `INACTIVE`, command keeps `BRAND` → no refusal, saved.
  - `changingToInactiveCategoryIsRefused`: update moving to a new category whose status is `INACTIVE` → `CATEGORY_INACTIVE`.
  - `missingStillAnswers404`: `MISSING` → `BRAND_NOT_FOUND` / `CATEGORY_NOT_FOUND` as before.
  - Replace the `brandExists`/`categoryExists` stubs in the existing tests with `brandStatus`/`categoryStatus` → `ACTIVE`.
- [ ] **Step 2: Run** `mvn test -Dtest=ProductServiceImplTest` — expected: compile failure, then the new tests FAIL.
- [ ] **Step 3: Implement** the enum, port, adapter and service change; the `ProductTaxonomyService` class javadoc sentence on deactivating gains "and a product cannot newly choose it". Service javadoc says the rule spans rows, so it has no `CHECK`.
- [ ] **Step 4: Write the integration test class** `ProductBusinessRulesIntegrationTest`, copying `ProductLifecycleIntegrationTest`'s class annotations, `@MockitoBean JwtDecoder` and `@TestPropertySource` verbatim so the Spring context is shared, and its `token`/`editor`/`send`/`data`/`createBody` helpers. Test `inactiveBrandOrCategoryIsRefusedWhenChosen`: deactivate a fresh brand (`taxonomy.updateBrand(id, name, null, false)`) → `POST /api/v1/products` is 409 `BRAND_INACTIVE` with `fieldErrors[0].field == "brandId"`; same for a category (`updateCategory(id, new CategoryDetails(..., false))`) → `CATEGORY_INACTIVE`; a draft created while the brand was active, brand then deactivated, `PUT` with the same brand and a new name → 200.
- [ ] **Step 5: Run** `mvn test -Dtest=ProductServiceImplTest+ProductBusinessRulesIntegrationTest+MessageBundleTest` — expected PASS.
- [ ] **Step 6: Commit** `feat(product): an inactive brand or category cannot be newly chosen (SCRUM-87)`.

### Task 3: possible-duplicate detection, domain and service (SCRUM-88)

**Files:**
- Create: `src/main/java/com/stockflow/product/internal/domain/ProductNameKey.java` — `public static String of(String name)`
- Modify: `ProductSearchRepository` — add `List<ProductSummary> sameBrandAndCategory(UUID brandId, UUID categoryId)`; `ProductRepositoryAdapter` implements it with `Specs.eq("brandId")`, `Specs.eq("categoryId")`, `Specs.notEq("status", ProductStatus.DISCONTINUED)` after `Objects.requireNonNull` on both ids (a null would make `Specs.eq` match every row)
- Create: `src/main/java/com/stockflow/product/internal/service/ProductDuplicateService.java` — `@Service @Transactional(readOnly = true) public class`, method `public List<ProductSummary> possibleDuplicates(String name, UUID brandId, UUID categoryId, UUID excludeProductId)`
- Test: `src/test/java/com/stockflow/product/internal/domain/ProductNameKeyTest.java`, `src/test/java/com/stockflow/product/internal/service/ProductDuplicateServiceTest.java`

**Interfaces:**
- Produces: `ProductNameKey.of(String) : String`, `ProductDuplicateService#possibleDuplicates(...) : List<ProductSummary>` (ordered by code).

- [ ] **Step 1: Write `ProductNameKeyTest`**: `"Ly giấy 12oz"`, `"ly giay  12OZ"`, `"LY GIẤY 12 OZ"`, `"Ly-giấy 12oz."` all give `"lygiay12oz"`; `"Đĩa"` → `"dia"`; `"  "`, `"--"` and `null` → `""`.
- [ ] **Step 2: Write `ProductDuplicateServiceTest`** with a mocked `ProductSearchRepository`:
  - `matchesSameKeyOnly`: candidates `"Ly giay 12oz"` and `"Ly giấy 16oz"` → only the first.
  - `excludesTheProductItself`: the candidate with `excludeProductId` is dropped.
  - `noBrandOrCategoryNoCandidates`: brand or category null → empty, `sameBrandAndCategory` never called.
  - `emptyKeyMatchesNothing`: name `"!!!"` with a candidate named `"???"` → empty, repository never called.
- [ ] **Step 3: Run** `mvn test -Dtest=ProductNameKeyTest+ProductDuplicateServiceTest` — expected: compile failure.
- [ ] **Step 4: Implement.** Normalisation: replace `đ`/`Đ` with `d`, NFD (`java.text.Normalizer`), drop `\p{M}`, lower case with `Locale.ROOT`, drop everything that is not `\p{L}` or `\p{N}`. Javadoc of `ProductNameKey`: why it drops spaces and punctuation (so `"12oz"` ≡ `"12 oz"`), why it is in Java (no `catalog.immutable_unaccent` from `product` SQL). Javadoc of the service: kltn-docs 01 §3 identity (name, code, category, brand), why it warns instead of blocking, and that blocking is one line here once the team decides.
- [ ] **Step 5: Run** the same command — expected PASS.
- [ ] **Step 6: Commit** `feat(product): find products that look like duplicates of a name, brand and category (SCRUM-88)`.

### Task 4: duplicates over HTTP (SCRUM-88)

**Files:**
- Create: `src/main/java/com/stockflow/product/internal/controller/dto/ProductMatchResponse.java` — `record ProductMatchResponse(UUID productId, String code, String name, String status)`
- Modify: `ProductResponse` — last component `List<ProductMatchResponse> possibleDuplicates`, `@Schema`: "Products of the same brand and primary category whose name normalises to the same key; a warning, never a refusal. Filled on create, update and detail; null in lists."
- Modify: `ProductWebMapper` — the existing `toResponse(ProductSummary)` gets `@Mapping(target = "possibleDuplicates", ignore = true)`; add `ProductResponse toResponse(ProductSummary summary, List<ProductSummary> possibleDuplicates)` and `ProductMatchResponse toMatch(ProductSummary summary)`
- Modify: `ProductController` — inject `ProductDuplicateService`; `create`, `update`, `findOne` answer with the two-argument mapping (`possibleDuplicates(summary.name(), summary.brandId(), summary.categoryId(), summary.productId())`); new `@GetMapping("/duplicates") public ApiResponse<List<ProductMatchResponse>> duplicates(@RequestParam String name, @RequestParam(required = false) UUID brandId, @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) UUID excludeProductId)` with `@RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)`
- Test: `ProductBusinessRulesIntegrationTest`

- [ ] **Step 1: Write the failing integration tests:**
  - `secondProductWithTheSameNameIsCreatedWithAWarning`: create `"Ly giấy 12oz"` under brand B + category C → 201, `possibleDuplicates` empty; create `"LY GIAY 12 OZ"` with another code under B + C → 201 (not refused), `possibleDuplicates[0].code` is the first code; `GET /api/v1/products/{second}` lists it too; `GET /api/v1/products?…` rows carry `possibleDuplicates` null.
  - `otherBrandOrDiscontinuedIsNotADuplicate`: same name under another brand → empty.
  - `duplicatesEndpoint`: `GET /api/v1/products/duplicates?name=ly%20giay%2012oz&brandId=B&categoryId=C` → the first product; with `excludeProductId` = that id → empty; without `name` → 400.
  - `editingKeepsItselfOut`: `PUT` on the first product without changing the name → `possibleDuplicates` contains the second, never itself.
- [ ] **Step 2: Run** `mvn test -Dtest=ProductBusinessRulesIntegrationTest` — expected FAIL.
- [ ] **Step 3: Implement** the DTO, mapper and controller changes.
- [ ] **Step 4: Run** `mvn test -Dtest=ProductBusinessRulesIntegrationTest+ProductLifecycleIntegrationTest+ArchitectureTest+ModularityTest` — expected PASS.
- [ ] **Step 5: Commit** `feat(product): report possible duplicates on create, update and detail, and on their own route (SCRUM-88)`.

### Task 5: documentation and the full build

**Files:**
- Modify: `docs/business-design/05-business-rules.md` — BR-PRD-001 (UoM and dimensions now on the inventory item; product enforces category, brand, `nameEn`, `taxClass` → `Product.submit()` + `ck_products_brand_on_submit` + `ck_products_details_on_submit` *(pending, SCRUM-73 schema request)*); BR-PRD-004 enforcement → `ProductPublicationAdapter` / `CatalogProjectionService` (`PRODUCT_CATEGORY_REQUIRED`, `PRODUCT_GALLERY_REQUIRED`); BR-PRD-006 → `InventoryItemsImpl.savePolicy` (`INVENTORY_POLICY_STOCK_CONFLICT`); new BR-PRD-009 (an inactive brand or category is not newly chosen; `ProductServiceImpl.requireReferences`, 3.1.9.1); new BR-PRD-010 (a possible duplicate is reported, never refused; `ProductDuplicateService`, 3.1.9.2)
- Add: `docs/business-design/db-design/SCRUM-73-schema-change-request.md` (already written)

- [ ] **Step 1: Edit** the rows; check each enforcement point named exists with `git grep`.
- [ ] **Step 2: Run** `python3 tools/verify.py` — expected: no findings.
- [ ] **Step 3: Run** `mvn verify` once (background; nothing edited while it runs) — expected: BUILD SUCCESS. Compare failures against `develop` before calling one new.
- [ ] **Step 4: Commit** `docs(product): business rules match the code; schema change request for Tú (SCRUM-73)`.
