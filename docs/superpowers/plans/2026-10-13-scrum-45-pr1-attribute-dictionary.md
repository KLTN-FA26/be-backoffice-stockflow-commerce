# SCRUM-45 PR 1 — Attribute dictionary and category attributes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Map `product.attributes`, `product.attribute_values` and `product.category_attributes` and expose
them over REST, so attributes and their values can be maintained and a category can carry a suggested
attribute set (GitHub #93, Jira SCRUM-58).

**Architecture:** An `Attribute` aggregate (with its `AttributeValue` children) in `product/internal/domain`
holds every rule the table CHECKs state, behind an `AttributeRepository` port implemented by an adapter
that merges onto JPA entities. Category attributes are plain reference rows with no lifecycle, kept as an
entity edited by a service — the same choice `ProductTaxonomyService` makes for brands and categories.
Both get their own controllers guarded by the existing `product-attributes` permission resource.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA / Hibernate, PostgreSQL 16 (Testcontainers),
JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-13-scrum-45-variant-sku-design.md` — §3 (tables), §4 D8–D9,
§5 (components), §6 "Attribute dictionary" and "Category attributes".

## Global Constraints

- **No migration.** Every table already exists on `develop` (`V20260928001000__pim_dictionary.sql`). Do not add files to `db/migration`.
- Attribute code: upper-cased and stripped, then `^[A-Z0-9][A-Z0-9_]{0,49}$` (`ck_attributes_code` — **no hyphen**).
- Attribute value code: upper-cased and stripped, then `^[A-Z0-9][A-Z0-9_-]{0,49}$` (`ck_attribute_values_code`).
- `hex_color`: upper-cased, then `^#[0-9A-F]{6}$` (`ck_attribute_values_hex`).
- `data_type` ∈ `TEXT, NUMBER, BOOLEAN, SELECT, MULTISELECT, COLOR`; `default_role` ∈ `VARIANT_AXIS, DESCRIPTIVE`. Both `@Enumerated(EnumType.STRING)`.
- `name`, `label`: 1–255 characters after strip. `unit`: at most 20.
- An attribute's `code` and `dataType` never change after creation (`updatable = false` on both columns); a value's `code` never changes.
- Permission resource `product-attributes` (constant `ProductResources.ATTRIBUTES`); actions READ / CREATE / UPDATE / DELETE. Audit `resourceType` `"product-attribute"` and `"product-category-attributes"`.
- New error codes (each in `ErrorCode`, `messages.properties` and `messages_vi.properties` — `MessageBundleTest` fails otherwise): `ATTRIBUTE_NOT_FOUND` 404, `ATTRIBUTE_VALUE_NOT_FOUND` 404, `ATTRIBUTE_CODE_ALREADY_EXISTS` 409, `ATTRIBUTE_IN_USE` 409.
- Ids from `Identifiers.newId()`. Controllers never return entities. Proxied/handler methods are `public`.
- Identifiers and javadoc in English; javadoc says why. Vietnamese only in `messages_vi.properties`.
- Integration tests need Docker running (Testcontainers Postgres).
- **Commits:** CLAUDE.md §7 — never commit without the repository owner's go-ahead. Each "Commit" step below is done only once the owner has approved committing for this plan's execution.

## Review Focus

1. **Codes typed in any case or with spaces** (`" cup_size "`) — expected to be stored as `CUP_SIZE`; a hyphen in an *attribute* code is a 400, not a 500 from `ck_attributes_code`. Pinned in Task 1 (`codesAreNormalisedAndTheHyphenIsRefusedForAttributes`).
2. **A lower-case hex colour** (`"#ff00aa"`) — expected to be accepted and stored `#FF00AA`, since `ck_attribute_values_hex` only takes upper case. Pinned in Task 1 (`hexColourIsUpperCasedAndRequiredOnlyForColour`).
3. **Deleting an attribute that has values but is used nowhere** — expected 200, values deleted with it; `fk_attribute_values_attribute` is `RESTRICT`, so a naive delete is a 500. Pinned in Task 3 (`deletingAnUnusedAttributeDeletesItsValuesToo`).
4. **Re-saving a category set that keeps an attribute but changes its role** — expected an update in place. Hibernate flushes inserts before deletes, so delete-then-insert of the same pair trips `uk_category_attributes_pair`. Pinned in Task 5 (`replacingKeepsAnExistingPairAndChangesItInPlace`).
5. **The same attribute twice in one category request** — expected 400, not a 409/500 from the unique constraint. Pinned in Task 5 (`theSameAttributeTwiceInOneRequestIsRefused`).

---

## File Structure

All under `src/main/java/com/stockflow/product/internal/` unless stated.

| File | Responsibility |
|---|---|
| `domain/AttributeDataType.java` | enum of the six types + which may hold values / be an axis |
| `domain/AttributeRole.java` | enum `VARIANT_AXIS`, `DESCRIPTIVE` (reused by PR 2) |
| `domain/AttributeDetails.java` | record: the editable fields of an attribute, validated |
| `domain/AttributeValueDetails.java` | record: the editable fields of a value, validated |
| `domain/AttributeValue.java` | child entity of the aggregate |
| `domain/Attribute.java` | aggregate root: codes, type rules, values |
| `domain/AttributeRepository.java` | port |
| `entity/AttributeJpaEntity.java`, `entity/AttributeValueJpaEntity.java` | table mappings |
| `entity/CategoryAttributeJpaEntity.java` | table mapping |
| `repository/AttributeJpaRepository.java`, `repository/CategoryAttributeJpaRepository.java` | Spring Data |
| `repository/AttributePersistenceMapper.java` | entity ⇄ aggregate, merge onto managed rows |
| `repository/AttributeRepositoryAdapter.java` | implements the port; in-use checks; constraint translation |
| `service/AttributeDictionaryService.java` | transaction boundary for the dictionary |
| `service/CategoryAttributeService.java` | read / replace a category's set |
| `controller/AttributeController.java`, `controller/CategoryAttributeController.java` | REST |
| `controller/AttributeWebMapper.java` | hand-written view → response mapping (like `ProductAdminWebMapper`) |
| `controller/dto/*Attribute*Request/Response.java` | DTOs (listed per task) |
| `controller/ProductResources.java` | add `ATTRIBUTES` |
| `common/error/ErrorCode.java`, `resources/i18n/messages*.properties` | the four new codes |

Tests under `src/test/java/com/stockflow/product/internal/…` mirroring the main packages.

---

### Task 1: The `Attribute` aggregate

**Files:**
- Create: `domain/AttributeDataType.java`, `domain/AttributeRole.java`, `domain/AttributeDetails.java`, `domain/AttributeValueDetails.java`, `domain/AttributeValue.java`, `domain/Attribute.java`, `domain/AttributeRepository.java`
- Modify: `src/main/java/com/stockflow/common/error/ErrorCode.java` (404s next to `VARIANT_NOT_FOUND` line 55; 409s next to `VARIANT_SKU_ALREADY_EXISTS` line 181), `src/main/resources/i18n/messages.properties`, `src/main/resources/i18n/messages_vi.properties`
- Test: `src/test/java/com/stockflow/product/internal/domain/AttributeTest.java`

**Interfaces:**
- Produces:
  - `enum AttributeDataType { TEXT, NUMBER, BOOLEAN, SELECT, MULTISELECT, COLOR; boolean holdsValues(); boolean canBeVariantAxis(); }` — `holdsValues` true for SELECT, MULTISELECT, COLOR; `canBeVariantAxis` true for SELECT, COLOR.
  - `enum AttributeRole { VARIANT_AXIS, DESCRIPTIVE }`
  - `record AttributeDetails(String name, String unit, boolean filterable, boolean searchable, int sortOrder)` — compact ctor strips `name` (1–255) and `unit` (null or ≤ 20, blank → null), else `BusinessException(VALIDATION_FAILED)`.
  - `record AttributeValueDetails(String label, BigDecimal valueNumber, String hexColor, int sortOrder)` — strips `label` (1–255); upper-cases `hexColor`, blank → null.
  - `final class AttributeValue` — `UUID id()`, `String code()`, `AttributeValueDetails details()`, `boolean active()`, `long version()`; constructor `AttributeValue(UUID id, String code, AttributeValueDetails details, boolean active, long version)` (rehydration; package-visible mutators used by `Attribute` only).
  - `final class Attribute extends AggregateRoot`:
    - `static Attribute create(UUID id, String code, AttributeDataType dataType, AttributeDetails details)`
    - rehydration ctor `Attribute(UUID id, String code, AttributeDataType dataType, AttributeDetails details, boolean active, long version, List<AttributeValue> values)`
    - `void update(long expectedVersion, AttributeDetails details, boolean active)`
    - `AttributeValue addValue(UUID valueId, String code, AttributeValueDetails details)`
    - `void updateValue(UUID valueId, long expectedVersion, AttributeValueDetails details, boolean active)`
    - `void removeValue(UUID valueId)`
    - `AttributeValue value(UUID valueId)` — `ATTRIBUTE_VALUE_NOT_FOUND` if absent
    - accessors `id() code() dataType() details() active() version() values()` (`values()` unmodifiable, ordered by `sortOrder` then `code`)
    - `static String normaliseCode(String raw)` and `static String normaliseValueCode(String raw)` — public, reused by the service for the pre-insert exists check.
  - `interface AttributeRepository extends AggregateRepository<Attribute, UUID> { boolean existsByCode(String code); boolean isInUse(UUID attributeId); boolean isValueInUse(UUID valueId); void delete(Attribute attribute); }`
  - `ErrorCode.ATTRIBUTE_NOT_FOUND("Attribute not found", 404)`, `ATTRIBUTE_VALUE_NOT_FOUND("Attribute value not found", 404)`, `ATTRIBUTE_CODE_ALREADY_EXISTS("An attribute or value with this code already exists", 409)`, `ATTRIBUTE_IN_USE("This attribute or value is used by a product or category", 409)`.

- [ ] **Step 1: Write the failing tests** in `AttributeTest` (plain JUnit, no Spring):

```java
@Test void codesAreNormalisedAndTheHyphenIsRefusedForAttributes() {
    assertThat(Attribute.create(id(), " cup_size ", SELECT, details()).code()).isEqualTo("CUP_SIZE");
    assertThatThrownBy(() -> Attribute.create(id(), "CUP-SIZE", SELECT, details()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    var select = Attribute.create(id(), "CAPACITY", SELECT, details());
    assertThat(select.addValue(id(), " 12oz-hot ", label("12 oz")).code()).isEqualTo("12OZ-HOT");
}

@Test void hexColourIsUpperCasedAndRequiredOnlyForColour() {
    var color = Attribute.create(id(), "COLOR", COLOR, details());
    assertThat(color.addValue(id(), "PINK", new AttributeValueDetails("Hồng", null, "#ff00aa", 0))
            .details().hexColor()).isEqualTo("#FF00AA");
    assertValidationFailed(() -> color.addValue(id(), "RED", new AttributeValueDetails("Đỏ", null, null, 0)));
    assertValidationFailed(() -> color.addValue(id(), "BAD", new AttributeValueDetails("x", null, "#GG0000", 0)));
    var select = Attribute.create(id(), "CAPACITY", SELECT, details());
    assertValidationFailed(() -> select.addValue(id(), "12OZ", new AttributeValueDetails("12 oz", null, "#000000", 0)));
}

@Test void onlySelectMultiselectAndColourHoldValues() {
    for (var type : List.of(TEXT, NUMBER, BOOLEAN)) {
        assertValidationFailed(() -> Attribute.create(id(), "X", type, details()).addValue(id(), "A", label("a")));
    }
}

@Test void aValueCodeIsUniqueWithinItsAttribute() {
    var a = Attribute.create(id(), "CAPACITY", SELECT, details());
    a.addValue(id(), "12OZ", label("12 oz"));
    assertThatThrownBy(() -> a.addValue(id(), "12oz", label("again")))
            .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS);
}

@Test void aStaleVersionIsRefused() {
    var a = new Attribute(id(), "CAPACITY", SELECT, details(), true, 3, List.of());
    assertThatThrownBy(() -> a.update(2, details(), true))
            .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    var value = new AttributeValue(id(), "12OZ", label("12 oz"), true, 5);
    var b = new Attribute(id(), "CAPACITY", SELECT, details(), true, 0, List.of(value));
    assertThatThrownBy(() -> b.updateValue(value.id(), 4, label("x"), true))
            .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
}

@Test void detailsValidateNameLabelAndUnit() {
    assertValidationFailed(() -> new AttributeDetails(" ", null, false, false, 0));
    assertValidationFailed(() -> new AttributeDetails("Dung tích", "x".repeat(21), false, false, 0));
    assertThat(new AttributeDetails(" Dung tích ", " ", false, false, 0).unit()).isNull();
    assertValidationFailed(() -> new AttributeValueDetails("", null, null, 0));
}

@Test void valuesAreOrderedBySortOrderThenCode() {
    var a = Attribute.create(id(), "CAPACITY", SELECT, details());
    a.addValue(id(), "22OZ", new AttributeValueDetails("22", null, null, 2));
    a.addValue(id(), "16OZ", new AttributeValueDetails("16", null, null, 1));
    a.addValue(id(), "12OZ", new AttributeValueDetails("12", null, null, 1));
    assertThat(a.values()).extracting(AttributeValue::code).containsExactly("12OZ", "16OZ", "22OZ");
}

@Test void removingAnUnknownValueIsNotFound() {
    var a = Attribute.create(id(), "CAPACITY", SELECT, details());
    assertThatThrownBy(() -> a.removeValue(id()))
            .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ATTRIBUTE_VALUE_NOT_FOUND);
}
```

Helpers in the test: `id()` = `Identifiers.newId()`, `details()` = `new AttributeDetails("Dung tích", null, true, false, 0)`, `label(s)` = `new AttributeValueDetails(s, null, null, 0)`, `assertValidationFailed(ThrowingCallable)` asserting `ErrorCode.VALIDATION_FAILED`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=AttributeTest`
Expected: compilation failure — `Attribute`, `AttributeDataType` … not found.

- [ ] **Step 3: Implement the domain types listed under Interfaces.** Version checks throw `BusinessException(ErrorCode.OPTIMISTIC_LOCK, …)` the way `warehouse/internal/domain/Boundary.requireVersion` does. The class javadoc of `Attribute` says why it is an aggregate while brands are not: the value rules depend on the attribute's type, and `ck_attribute_values_hex` / the type checks are stated a second time here (CLAUDE.md §7). Add the four `ErrorCode` constants and their two bundle lines each (Vietnamese: "Không tìm thấy thuộc tính", "Không tìm thấy giá trị thuộc tính", "Đã tồn tại thuộc tính hoặc giá trị với mã này", "Thuộc tính hoặc giá trị đang được sản phẩm hoặc danh mục sử dụng").

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='AttributeTest,MessageBundleTest,ArchitectureTest'`
Expected: all green (`ArchitectureTest.domainDoesNotDependOnFrameworks` covers the new domain classes).

- [ ] **Step 5: Commit** (after owner's go-ahead)

```bash
git add src/main/java/com/stockflow/product/internal/domain/Attribute*.java src/main/java/com/stockflow/common/error/ErrorCode.java src/main/resources/i18n/messages.properties src/main/resources/i18n/messages_vi.properties src/test/java/com/stockflow/product/internal/domain/AttributeTest.java
git commit -m "feat(product): attribute aggregate with its type and code rules (SCRUM-58)"
```

---

### Task 2: Persistence for attributes

**Files:**
- Create: `entity/AttributeJpaEntity.java`, `entity/AttributeValueJpaEntity.java`, `repository/AttributeJpaRepository.java`, `repository/AttributePersistenceMapper.java`, `repository/AttributeRepositoryAdapter.java`
- Test: `src/test/java/com/stockflow/product/internal/repository/AttributeRepositoryAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1's `Attribute`, `AttributeValue`, `AttributeRepository`.
- Produces:
  - `AttributeJpaEntity extends BaseEntity` → `product.attributes`; `code`, `dataType` `updatable = false`; `@OneToMany(mappedBy = "attribute", cascade = ALL, orphanRemoval = true) List<AttributeValueJpaEntity> values`.
  - `AttributeValueJpaEntity extends BaseEntity` → `product.attribute_values`; `@ManyToOne(fetch = LAZY) @JoinColumn(name = "attribute_id", updatable = false) AttributeJpaEntity attribute`; `code` `updatable = false`.
  - `interface AttributeJpaRepository extends BaseJpaRepository<AttributeJpaEntity>`:
    - `@Query("select distinct a from AttributeJpaEntity a left join fetch a.values where a.id = :id") Optional<AttributeJpaEntity> findWithValuesById(UUID id)`
    - `@Query("select distinct a from AttributeJpaEntity a left join fetch a.values where a.id in :ids") List<AttributeJpaEntity> findWithValuesByIdIn(Collection<UUID> ids)`
    - `boolean existsByCode(String code)`
    - native `exists` queries for in-use: attribute referenced from `product.product_attributes` or `product.category_attributes`; value referenced from `product.product_attribute_options` or `product.product_descriptive_values`.
  - `AttributeRepositoryAdapter implements AttributeRepository` (`@Repository`, package-private class): `save` loads the managed row with values and merges child by child (update existing, add new, remove missing) before `saveAndFlush`; a `DataIntegrityViolationException` on `uk_attributes_code` or `uk_attribute_values_code` becomes `ATTRIBUTE_CODE_ALREADY_EXISTS`, and a foreign-key violation (SQLSTATE `23503`) on delete becomes `ATTRIBUTE_IN_USE`. Name matching uses a copy of `warehouse/internal/repository/ConstraintViolations.isViolationOf` logic — package-private helper in `product/internal/repository`, not an import across modules.

- [ ] **Step 1: Write the failing integration test** (`@IntegrationTest @Import(PostgresContainer.class) @Transactional`):

```java
@Test void anAttributeRoundTripsWithItsValues() {
    var a = Attribute.create(Identifiers.newId(), "CAPACITY", SELECT, details());
    a.addValue(Identifiers.newId(), "12OZ", new AttributeValueDetails("12 oz", new BigDecimal("12"), null, 1));
    repository.save(a);
    entityManager.flush(); entityManager.clear();
    var loaded = repository.findById(a.id()).orElseThrow();
    assertThat(loaded.values()).extracting(AttributeValue::code).containsExactly("12OZ");
    assertThat(loaded.values().getFirst().details().valueNumber()).isEqualByComparingTo("12");
}

@Test void removingAValueFromTheAggregateDeletesItsRow() { /* save with 2 values, removeValue one, save, count rows = 1 */ }

@Test void theDemoColourIsInUseAndAFreshAttributeIsNot() {
    assertThat(repository.isInUse(DemoData.demoId("demo:attr:COLOR"))).isTrue();
    assertThat(repository.isValueInUse(DemoData.demoId("demo:val:COLOR:GREY"))).isTrue();
    var fresh = repository.save(Attribute.create(Identifiers.newId(), "WALL", SELECT, details()));
    assertThat(repository.isInUse(fresh.id())).isFalse();
}

@Test void aRacingDuplicateCodeIsTranslated() {
    repository.save(Attribute.create(Identifiers.newId(), "LID", SELECT, details()));
    assertThatThrownBy(() -> repository.save(Attribute.create(Identifiers.newId(), "LID", SELECT, details())))
            .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS);
}

@Test void loadingAnAttributeWithValuesIsOneQuery() { /* Hibernate Statistics: prepareStatementCount == 1 for findById after clear — see jpa-orm-review skill */ }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=AttributeRepositoryAdapterIntegrationTest`
Expected: compilation failure (`AttributeRepositoryAdapter` unknown — the test autowires `AttributeRepository`).

- [ ] **Step 3: Implement the entities, the Spring Data repository, `AttributePersistenceMapper` (`static Attribute toDomain(AttributeJpaEntity)`, `static void merge(Attribute, AttributeJpaEntity)`, `static AttributeJpaEntity newEntity(Attribute)`) and the adapter.** Rehydration goes through the `Attribute` rehydration ctor, as `ProductPersistenceMapper` does, so a row that breaks a rule fails loudly on load. `save` returns `toDomain` of the flushed row so versions are current.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=AttributeRepositoryAdapterIntegrationTest`
Expected: PASS (Docker must be running).

- [ ] **Step 5: Commit** (after owner's go-ahead)

```bash
git add src/main/java/com/stockflow/product/internal/entity/Attribute*.java src/main/java/com/stockflow/product/internal/repository/Attribute*.java src/main/java/com/stockflow/product/internal/repository/ConstraintViolations.java src/test/java/com/stockflow/product/internal/repository/AttributeRepositoryAdapterIntegrationTest.java
git commit -m "feat(product): persist attributes and their values (SCRUM-58)"
```

---

### Task 3: `AttributeDictionaryService`

**Files:**
- Create: `service/AttributeDictionaryService.java`
- Test: `src/test/java/com/stockflow/product/internal/service/AttributeDictionaryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 domain, Task 2 `AttributeRepository`, `AttributeJpaRepository.findWithValuesByIdIn`.
- Produces (`@Service @Transactional public class AttributeDictionaryService`):
  - `record AttributeView(UUID id, String code, String name, AttributeDataType dataType, String unit, boolean filterable, boolean searchable, int sortOrder, boolean active, long version, List<AttributeValueView> values)`
  - `record AttributeValueView(UUID id, String code, String label, BigDecimal valueNumber, String hexColor, int sortOrder, boolean active, long version)`
  - `@Transactional(readOnly = true) PageResponse<AttributeView> list(String search, AttributeDataType dataType, Boolean active, int page, int size, String sort)` — `Specs.contains` on `code`/`name`, `Specs.eq` on `dataType`/`active`; sort whitelist `sortOrder, code, name, createdAt`, default `sortOrder ASC`; values for the page in **one** `findWithValuesByIdIn` call.
  - `@Transactional(readOnly = true) AttributeView get(UUID attributeId)`
  - `AttributeView create(String code, AttributeDataType dataType, AttributeDetails details)` — `existsByCode` pre-check → `ATTRIBUTE_CODE_ALREADY_EXISTS`
  - `AttributeView update(UUID attributeId, long version, AttributeDetails details, boolean active)`
  - `void delete(UUID attributeId, long version)` — `isInUse` → `ATTRIBUTE_IN_USE`; otherwise deletes values then the attribute
  - `AttributeView addValue(UUID attributeId, String code, AttributeValueDetails details)`
  - `AttributeView updateValue(UUID attributeId, UUID valueId, long version, AttributeValueDetails details, boolean active)`
  - `AttributeView deleteValue(UUID attributeId, UUID valueId, long version)` — `isValueInUse` → `ATTRIBUTE_IN_USE`
  - Every write `@Auditable(resourceType = "product-attribute", …)`; missing attribute → `ATTRIBUTE_NOT_FOUND`.

- [ ] **Step 1: Write the failing integration test** (`@IntegrationTest @Import(PostgresContainer.class) @Transactional`):

```java
@Test void createListAndGet() {
    var created = service.create("capacity", SELECT, new AttributeDetails("Dung tích", "oz", true, false, 1));
    service.addValue(created.id(), "12OZ", new AttributeValueDetails("12 oz", new BigDecimal("12"), null, 0));
    assertThat(service.get(created.id()).values()).extracting(AttributeValueView::code).containsExactly("12OZ");
    assertThat(service.list("capac", null, null, 0, 20, null).items()).extracting(AttributeView::code).contains("CAPACITY");
}
@Test void aDuplicateCodeIsAConflict() { /* create "LID" twice → ATTRIBUTE_CODE_ALREADY_EXISTS */ }
@Test void aStaleVersionIsAConflict() { /* update with version+1 → OPTIMISTIC_LOCK */ }
@Test void deletingAnAttributeInUseIsRefused() {
    var color = DemoData.demoId("demo:attr:COLOR");
    assertCode(() -> service.delete(color, service.get(color).version()), ErrorCode.ATTRIBUTE_IN_USE);
    var grey = DemoData.demoId("demo:val:COLOR:GREY");
    assertCode(() -> service.deleteValue(color, grey, versionOfValue(grey)), ErrorCode.ATTRIBUTE_IN_USE);
}
@Test void deletingAnUnusedAttributeDeletesItsValuesToo() {
    var a = service.create("WALL", SELECT, details());
    a = service.addValue(a.id(), "SINGLE", label("Một lớp"));
    service.delete(a.id(), a.version());
    assertThat(jdbc.queryForObject("select count(*) from product.attribute_values where attribute_id = ?", Long.class, a.id())).isZero();
    assertCode(() -> service.get(a.id()), ErrorCode.ATTRIBUTE_NOT_FOUND);
}
@Test void deactivatingKeepsTheRowAndItsValues() { /* update active=false → get().active() false, values unchanged */ }
@Test void listingAPageLoadsValuesInOneQuery() { /* Hibernate Statistics: list(...) issues at most 3 statements (page, count, values) */ }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=AttributeDictionaryIntegrationTest`
Expected: compilation failure (`AttributeDictionaryService` unknown).

- [ ] **Step 3: Implement `AttributeDictionaryService`** with the signatures above. Search uses `AttributeJpaRepository` + `Specification` (the read side may use Spring Data directly, as `ProductTaxonomyService` does); writes go through the `AttributeRepository` port.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=AttributeDictionaryIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit** (after owner's go-ahead)

```bash
git add src/main/java/com/stockflow/product/internal/service/AttributeDictionaryService.java src/test/java/com/stockflow/product/internal/service/AttributeDictionaryIntegrationTest.java
git commit -m "feat(product): attribute dictionary service (SCRUM-58)"
```

---

### Task 4: `AttributeController`

**Files:**
- Create: `controller/AttributeController.java`, `controller/AttributeWebMapper.java`, `controller/dto/CreateAttributeRequest.java`, `controller/dto/UpdateAttributeRequest.java`, `controller/dto/CreateAttributeValueRequest.java`, `controller/dto/UpdateAttributeValueRequest.java`, `controller/dto/AttributeResponse.java`, `controller/dto/AttributeValueResponse.java`
- Modify: `controller/ProductResources.java` (add `public static final String ATTRIBUTES = "product-attributes";`)
- Test: `src/test/java/com/stockflow/product/internal/controller/AttributeControllerHttpTest.java`

**Interfaces:**
- Consumes: Task 3 service and views.
- Produces (base `/api/v1`, all `@RequiresPermission(resource = ProductResources.ATTRIBUTES, …)`):

| Handler | Mapping | Action | Body / params |
|---|---|---|---|
| `list` | `GET /attributes` | READ | `q`, `dataType`, `active`, `page`, `size`, `sort` |
| `get` | `GET /attributes/{attributeId}` | READ | — |
| `create` | `POST /attributes` → 201 | CREATE | `CreateAttributeRequest(@NotBlank code, @NotNull AttributeDataType dataType, @NotBlank name, unit, boolean filterable, boolean searchable, Integer sortOrder)` |
| `update` | `PUT /attributes/{attributeId}` | UPDATE | `UpdateAttributeRequest(@NotNull Long version, @NotBlank name, unit, boolean filterable, boolean searchable, Integer sortOrder, @NotNull Boolean active)` |
| `delete` | `DELETE /attributes/{attributeId}?version=` | DELETE | `@RequestParam long version`, returns `ApiResponse<Void>` like `BoundaryController.delete` |
| `addValue` | `POST /attributes/{attributeId}/values` → 201 | UPDATE | `CreateAttributeValueRequest(@NotBlank code, @NotBlank label, BigDecimal valueNumber, hexColor, Integer sortOrder)` |
| `updateValue` | `PUT /attributes/{attributeId}/values/{valueId}` | UPDATE | `UpdateAttributeValueRequest(@NotNull Long version, @NotBlank label, BigDecimal valueNumber, hexColor, Integer sortOrder, @NotNull Boolean active)` |
| `deleteValue` | `DELETE /attributes/{attributeId}/values/{valueId}?version=` | UPDATE | returns the attribute |

  Adding or removing a value is an UPDATE of the attribute, not a CREATE/DELETE of a resource of its own — SALES_STAFF holds READ only, so this matters only for future grants; say it in the controller javadoc.
  - `AttributeResponse(UUID attributeId, String code, String name, String dataType, String unit, boolean filterable, boolean searchable, int sortOrder, boolean active, long version, List<AttributeValueResponse> values)`; `AttributeValueResponse(UUID valueId, String code, String label, BigDecimal valueNumber, String hexColor, int sortOrder, boolean active, long version)`.
  - `AttributeWebMapper` — `@Component` package-private class, `AttributeResponse toResponse(AttributeView)`.

- [ ] **Step 1: Write the failing test** (standalone MockMvc with a mocked service, as `ShelfControllerHttpTest` does):

```java
@Test void creatingAnAttributePassesTheTypedFields() throws Exception {
    when(service.create(eq("capacity"), eq(SELECT), any())).thenReturn(view());
    mvc.perform(post("/api/v1/attributes").contentType("application/json").content("""
            {"code":"capacity","dataType":"SELECT","name":"Dung tích","unit":"oz","filterable":true}"""))
       .andExpect(status().isCreated())
       .andExpect(jsonPath("$.data.code").value("CAPACITY"))
       .andExpect(jsonPath("$.data.values[0].code").value("12OZ"));
    var details = ArgumentCaptor.forClass(AttributeDetails.class);
    verify(service).create(eq("capacity"), eq(SELECT), details.capture());
    assertThat(details.getValue().unit()).isEqualTo("oz");
}
@Test void anUnknownDataTypeIsABadRequest() { /* "dataType":"DATE" → 400, verifyNoInteractions(service) */ }
@Test void updateWithoutAVersionIsABadRequest() { /* PUT without version → 400 */ }
@Test void deleteRequiresTheVersion() { /* DELETE without ?version → 400; with → service.delete(id, 3) */ }
@Test void everyHandlerIsGuardedByProductAttributes() {
    for (Method m : AttributeController.class.getDeclaredMethods()) {
        if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
            assertThat(m.getAnnotation(RequiresPermission.class).resource()).isEqualTo("product-attributes");
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=AttributeControllerHttpTest`
Expected: compilation failure (`AttributeController` unknown).

- [ ] **Step 3: Implement the controller, DTOs, mapper and the `ProductResources.ATTRIBUTES` constant.** Handlers are `public` (CLAUDE.md §5: a non-public handler may not get `@RequiresPermission` applied). `size` defaults to `Pages.DEFAULT_PAGE_SIZE`, `sortOrder` to 0, as `ProductTaxonomyController` does.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='AttributeControllerHttpTest,ArchitectureTest'`
Expected: PASS.

- [ ] **Step 5: Commit** (after owner's go-ahead)

```bash
git add src/main/java/com/stockflow/product/internal/controller/ src/test/java/com/stockflow/product/internal/controller/AttributeControllerHttpTest.java
git commit -m "feat(product): attribute dictionary REST API (SCRUM-58)"
```

---

### Task 5: Category attributes

**Files:**
- Create: `entity/CategoryAttributeJpaEntity.java`, `repository/CategoryAttributeJpaRepository.java`, `service/CategoryAttributeService.java`, `controller/CategoryAttributeController.java`, `controller/dto/ReplaceCategoryAttributesRequest.java`, `controller/dto/CategoryAttributeResponse.java`
- Modify: `controller/AttributeWebMapper.java` (add `CategoryAttributeResponse toResponse(CategoryAttributeView)`)
- Test: `src/test/java/com/stockflow/product/internal/service/CategoryAttributeIntegrationTest.java`, `src/test/java/com/stockflow/product/internal/controller/CategoryAttributeControllerHttpTest.java`

**Interfaces:**
- Consumes: `AttributeDataType.canBeVariantAxis()`, `AttributeRole` (Task 1); `AttributeJpaRepository` (Task 2); `CategoryJpaRepository` (existing).
- Produces:
  - `CategoryAttributeJpaEntity extends BaseEntity` → `product.category_attributes`; `categoryId`, `attributeId` plain `UUID` columns, `updatable = false`; `defaultRole` `AttributeRole` STRING; `required`; `sortOrder`.
  - `CategoryAttributeJpaRepository`: `List<CategoryAttributeJpaEntity> findByCategoryId(UUID categoryId)`.
  - `CategoryAttributeService` (`@Service @Transactional public`):
    - `record CategoryAttributeEntry(UUID attributeId, AttributeRole defaultRole, boolean required, int sortOrder)`
    - `record CategoryAttributeView(UUID attributeId, String attributeCode, String attributeName, AttributeDataType dataType, AttributeRole defaultRole, boolean required, int sortOrder)`
    - `@Transactional(readOnly = true) List<CategoryAttributeView> list(UUID categoryId)` — ordered by `sortOrder` then attribute code; attributes loaded in one `findAllById` call, not per row.
    - `@Auditable(action = UPDATE, resourceType = "product-category-attributes", resourceId = "#categoryId") List<CategoryAttributeView> replace(UUID categoryId, List<CategoryAttributeEntry> entries)` — `CATEGORY_NOT_FOUND`; duplicate `attributeId` in `entries` → `VALIDATION_FAILED`; unknown attribute → `ATTRIBUTE_NOT_FOUND`; `VARIANT_AXIS` on a type that is not `canBeVariantAxis()` → `VALIDATION_FAILED`; an **inactive** attribute may stay if already in the set but may not be newly added → `VALIDATION_FAILED`. Rows whose attribute is kept are **updated in place**; rows not in `entries` are deleted and flushed **before** new rows are inserted.
  - `CategoryAttributeController` — `GET /api/v1/categories/{categoryId}/attributes` (`product-attributes` READ), `PUT /api/v1/categories/{categoryId}/attributes` (`product-attributes` UPDATE) taking `ReplaceCategoryAttributesRequest(@NotNull List<@Valid Entry> attributes)` with nested `record Entry(@NotNull UUID attributeId, @NotNull AttributeRole defaultRole, boolean required, Integer sortOrder)`. Its own controller, so `ProductTaxonomyController` (SCRUM-171, Võ) is untouched.
  - `CategoryAttributeResponse(UUID attributeId, String attributeCode, String attributeName, String dataType, String defaultRole, boolean required, int sortOrder)`.

- [ ] **Step 1: Write the failing integration test** (demo category `SOFA` = `DemoData.demoId("demo:cat:SOFA")` holds COLOR as `VARIANT_AXIS` and MATERIAL as `DESCRIPTIVE`):

```java
@Test void listsTheDemoSet() {
    assertThat(service.list(SOFA)).extracting(CategoryAttributeView::attributeCode, CategoryAttributeView::defaultRole)
            .containsExactlyInAnyOrder(tuple("COLOR", VARIANT_AXIS), tuple("MATERIAL", DESCRIPTIVE));
}
@Test void replacingKeepsAnExistingPairAndChangesItInPlace() {
    UUID rowId = jdbc.queryForObject("select id from product.category_attributes where category_id = ? and attribute_id = ?", UUID.class, SOFA, MATERIAL);
    service.replace(SOFA, List.of(new CategoryAttributeEntry(COLOR, DESCRIPTIVE, false, 0),
                                  new CategoryAttributeEntry(MATERIAL, DESCRIPTIVE, true, 1)));
    entityManager.flush();
    assertThat(jdbc.queryForObject("select id from product.category_attributes where category_id = ? and attribute_id = ?", UUID.class, SOFA, MATERIAL)).isEqualTo(rowId);
    assertThat(service.list(SOFA)).extracting(CategoryAttributeView::attributeCode, CategoryAttributeView::defaultRole)
            .containsExactly(tuple("COLOR", DESCRIPTIVE), tuple("MATERIAL", DESCRIPTIVE));
}
@Test void anAttributeLeftOutIsRemovedAndANewOneAdded() { /* replace with [LENGTH VARIANT_AXIS] → only LENGTH */ }
@Test void theSameAttributeTwiceInOneRequestIsRefused() { /* [COLOR, COLOR] → VALIDATION_FAILED */ }
@Test void aTextAttributeCannotBeAVariantAxis() { /* create TEXT attr via AttributeDictionaryService, replace with VARIANT_AXIS → VALIDATION_FAILED */ }
@Test void anInactiveAttributeCannotBeNewlyAdded() { /* deactivate LENGTH, replace SOFA with [LENGTH] → VALIDATION_FAILED; SOFA keeps an inactive COLOR → OK */ }
@Test void unknownCategoryOrAttributeIsNotFound() { /* CATEGORY_NOT_FOUND / ATTRIBUTE_NOT_FOUND */ }
@Test void anEmptyListClearsTheSet() { /* replace(SOFA, List.of()) → list empty */ }
```

And `CategoryAttributeControllerHttpTest`: a valid PUT reaches `service.replace` with the mapped entries; a body entry with no `attributeId` is 400 with no service call; every public handler carries `@RequiresPermission(resource = "product-attributes")`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='CategoryAttributeIntegrationTest,CategoryAttributeControllerHttpTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement entity, repository, service, controller, DTOs and the mapper method.** The service javadoc carries the two reasons that are not visible in the code: the set is a suggestion and is not enforced on products (spec D9), and why kept rows are updated in place (Hibernate's insert-before-delete flush order against `uk_category_attributes_pair`).

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='CategoryAttributeIntegrationTest,CategoryAttributeControllerHttpTest'`
Expected: PASS.

- [ ] **Step 5: Commit** (after owner's go-ahead)

```bash
git add src/main/java/com/stockflow/product/internal/ src/test/java/com/stockflow/product/internal/
git commit -m "feat(product): suggested attribute set per category (SCRUM-58)"
```

---

### Task 6: Security through the real filter chain, and the full build

**Files:**
- Create: `src/test/java/com/stockflow/product/AttributeSecurityIntegrationTest.java`

**Interfaces:**
- Consumes: the endpoints of Tasks 4 and 5. Pattern: `src/test/java/com/stockflow/InventoryCatalogSecurityIntegrationTest.java` (mocked `JwtDecoder`, `token(permission)` helper, `stockflow.security.enabled=true`).

- [ ] **Step 1: Write the test**

```java
@Test void anonymousIsUnauthorised() { /* GET /api/v1/attributes → 401 */ }
@Test void readIsEnoughToListButNotToCreate() {
    String reader = token("product-attributes:READ");
    mvc.perform(get("/api/v1/attributes").header("Authorization", reader)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/attributes").header("Authorization", reader).contentType("application/json")
            .content("{\"code\":\"SEC_X\",\"dataType\":\"SELECT\",\"name\":\"x\"}")).andExpect(status().isForbidden());
}
@Test void productProductsAloneDoesNotOpenTheDictionary() {
    mvc.perform(get("/api/v1/attributes").header("Authorization", token("product-products:READ")))
       .andExpect(status().isForbidden());
}
@Test void categoryAttributesNeedUpdateToReplace() {
    String reader = token("product-attributes:READ");
    mvc.perform(get("/api/v1/categories/{id}/attributes", SOFA).header("Authorization", reader)).andExpect(status().isOk());
    mvc.perform(put("/api/v1/categories/{id}/attributes", SOFA).header("Authorization", reader)
            .contentType("application/json").content("{\"attributes\":[]}")).andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run it**

Run: `mvn -q test -Dtest=AttributeSecurityIntegrationTest`
Expected: PASS. (Written after the endpoints exist, so this step does not fail first; it pins the guard against regressions.)

- [ ] **Step 3: Run the fitness functions and the whole suite**

Run: `python tools/verify.py` → expected: all checks pass.
Run: `mvn -q test -Dtest='ModularityTest,ArchitectureTest'` → expected: PASS.
Run: `mvn verify` → expected: BUILD SUCCESS. Any failure outside the product module is reported, not "fixed" by disabling a rule (CLAUDE.md §7).

- [ ] **Step 4: Commit** (after owner's go-ahead)

```bash
git add src/test/java/com/stockflow/product/AttributeSecurityIntegrationTest.java
git commit -m "test(product): attribute endpoints through the real security chain (SCRUM-58)"
```

- [ ] **Step 5: Tick the checklist in GitHub #93** and open the PR against `develop` with `Refs #93` (not `Closes` — the issue is closed by hand after merge).
