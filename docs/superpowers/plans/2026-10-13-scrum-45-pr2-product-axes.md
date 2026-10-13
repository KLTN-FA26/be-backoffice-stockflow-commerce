# SCRUM-45 PR 2 — Product variant axes, options and descriptive attributes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a product choose its **variant axes** (each with the options it offers) and its **descriptive
attributes**, mapped onto `product.product_attributes`, `product_attribute_options` and
`product_descriptive_values` (GitHub #94, Jira SCRUM-58).

**Architecture:** A `ProductAttributeSet` aggregate (one per product) holds the product's axes and
descriptive attributes and every rule of spec D3 and the `pim_integrity` triggers. It is loaded and saved
through a `ProductAttributeSetRepository` port whose adapter merges onto JPA rows with deletes flushed
before inserts. `ProductAttributeService` locks the product row first (the same `findForUpdate` the variant
service uses), so all writes to one product's attributes and variants serialise. One new controller exposes
the three endpoints and is the first to declare the `product-variants` permission resource.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA / Hibernate, PostgreSQL 16 (Testcontainers),
JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-13-scrum-45-variant-sku-design.md` — §3 (tables + triggers),
§4 D3 and D8, §5, §6 "Product attributes". Builds on PR 1 (#93, branch `feature/SCRUM-45-attribute-dictionary`).

## Global Constraints

- **No migration.** Tables and triggers exist: `V20260928001100__pim_products_variants.sql`, `V20260928001300__pim_integrity.sql`.
- Work in the worktree `.worktrees/scrum-45-pr2` on branch `feature/SCRUM-45-product-axes` (stacked on PR 1). Never commit to `feature/SCRUM-45-attribute-dictionary` from here.
- **D3:** adding or removing an **axis** only while the product is `DRAFT` → otherwise `409 PRODUCT_AXES_LOCKED`. Adding an **option**, changing `sortOrder`, and editing descriptive attributes are allowed whenever `Product.acceptsChanges()` (not `PENDING_APPROVAL`, not `DISCONTINUED`) → otherwise `409 INVALID_PRODUCT_STATUS_TRANSITION` (the code `ProductVariantService.requireChangeable` already uses). Removing an option a variant uses → `409 ATTRIBUTE_IN_USE`.
- An axis attribute is `SELECT` or `COLOR` (`AttributeDataType.canBeVariantAxis()`); an axis has at least one option; each option value belongs to the axis's attribute.
- Descriptive values by type: `SELECT`/`COLOR` exactly one dictionary value; `MULTISELECT` one or more; `TEXT` non-blank `text`; `NUMBER` `number`; `BOOLEAN` `bool`. Fields that do not belong to the type must be absent.
- An attribute appears at most once per product, in one role (`uk_product_attributes_pair`; `role` is immutable by trigger). An attribute already used in the other role → `400 VALIDATION_FAILED` naming it ("remove it from … first").
- A deactivated attribute or value may **stay** but may not be **newly** added (spec §6).
- Unknown attribute → `404 ATTRIBUTE_NOT_FOUND`; unknown product → `404 PRODUCT_NOT_FOUND`; every other rule above → `400 VALIDATION_FAILED`.
- New error code: `PRODUCT_AXES_LOCKED("Variant axes can only change while the product is a draft", 409)`, in `ErrorCode`, `messages.properties`, `messages_vi.properties` ("Chỉ được thay đổi trục biến thể khi sản phẩm còn ở trạng thái nháp").
- Permissions (D8): `GET` → `product-variants` READ; `PUT /axes` → `product-variants` UPDATE; `PUT /descriptive-attributes` → `product-products` UPDATE. `product-variants` is seeded with `VIEW_PAGE,READ,CREATE,UPDATE,APPROVE,EXPORT` (`V20260928006000:25`).
- Audit `resourceType = "product-attributes"`, `resourceId = "#productId"`, action `UPDATE`.
- Same house rules as PR 1: `EnumType.STRING`, `Identifiers.newId()`, public proxied/handler methods, javadoc says why, English in code, commits per `.claude/skills/git-commits/SKILL.md` (no attribution trailers). Run `tools/verify.py` as `python -X utf8 tools/verify.py` on Windows.

## Review Focus

1. **Re-sending the same set** (FE saves the form unchanged) — expected: no row deleted or re-inserted, ids stable, no version bump. Pinned in Task 2 (`savingAnUnchangedSetTouchesNoRow`).
2. **Changing a SELECT/COLOR descriptive value from A to B** — trigger 4 refuses a second row, so it must be an in-place update, not delete + insert. Pinned in Task 2 (`aSingleDescriptiveValueIsChangedInPlace`).
3. **MULTISELECT {A,B} → {B,C}**, and swapping an axis option — removed rows must be deleted and flushed before new rows are inserted. Pinned in Task 2 (`removalsAreFlushedBeforeInserts`).
4. **An attribute already descriptive, sent as an axis (or the reverse)** — expected 400 naming the attribute, not a 500 from `uk_product_attributes_pair` / the role trigger. Pinned in Task 1 (`anAttributeCannotBeInBothRoles`).
5. **Two saves of the same product's axes at once** (double-click) — expected: the second waits on the product lock and wins; no 500. Pinned in Task 3 (`concurrentAxisSavesSerialiseOnTheProduct`).

---

## File Structure

Under `src/main/java/com/stockflow/product/internal/` unless stated.

| File | Responsibility |
|---|---|
| `domain/ProductAttributeSet.java` | aggregate: axes + descriptives of one product, D3 and type rules |
| `domain/ProductAxis.java`, `domain/AxisOption.java` | an axis and one of its options |
| `domain/DescriptiveAttribute.java`, `domain/DescriptiveValue.java` | a descriptive attribute and one of its values |
| `domain/AxisSpec.java`, `domain/DescriptiveSpec.java` | records: what a client asks for |
| `domain/ProductAttributeSetRepository.java` | port |
| `domain/AttributeRepository.java` (modify) | add `List<Attribute> findAllById(Collection<UUID> ids)` |
| `entity/ProductAttributeJpaEntity.java`, `entity/ProductAttributeOptionJpaEntity.java`, `entity/ProductDescriptiveValueJpaEntity.java` | table mappings |
| `repository/ProductAttributeJpaRepository.java` | Spring Data + the in-use native query |
| `repository/ProductAttributeSetPersistenceMapper.java`, `repository/ProductAttributeSetRepositoryAdapter.java` | load / two-phase save |
| `repository/AttributeRepositoryAdapter.java` (modify) | implement `findAllById` |
| `service/ProductAttributeService.java` | transaction boundary, product lock, views |
| `controller/ProductAttributeController.java`, `controller/ProductAttributeWebMapper.java`, `controller/dto/…` | REST |
| `controller/ProductResources.java` (modify) | add `VARIANTS = "product-variants"` |
| `common/error/ErrorCode.java`, `resources/i18n/messages*.properties` | `PRODUCT_AXES_LOCKED` |

---

### Task 1: The `ProductAttributeSet` aggregate

**Files:**
- Create: `domain/ProductAttributeSet.java`, `domain/ProductAxis.java`, `domain/AxisOption.java`, `domain/DescriptiveAttribute.java`, `domain/DescriptiveValue.java`, `domain/AxisSpec.java`, `domain/DescriptiveSpec.java`, `domain/ProductAttributeSetRepository.java`
- Modify: `domain/AttributeRepository.java`, `repository/AttributeRepositoryAdapter.java` (implement `findAllById` with `AttributeJpaRepository.findWithValuesByIdIn`), `common/error/ErrorCode.java`, both i18n bundles
- Test: `src/test/java/com/stockflow/product/internal/domain/ProductAttributeSetTest.java`

**Interfaces:**
- Consumes: `Attribute` (`id() code() dataType() active() value(UUID)` → `AttributeValue` with `active()`), `AttributeDataType.canBeVariantAxis()`, `ProductStatus` (`product.api`), `Product.acceptsChanges()` semantics (re-stated on the set from the status).
- Produces:
  - `record AxisSpec(UUID attributeId, int sortOrder, List<UUID> valueIds)`
  - `record DescriptiveSpec(UUID attributeId, int sortOrder, List<UUID> valueIds, String text, BigDecimal number, Boolean bool)`
  - `record AxisOption(UUID id, UUID valueId)`
  - `final class ProductAxis` — `UUID id()`, `UUID attributeId()`, `int sortOrder()`, `List<AxisOption> options()` (unmodifiable); public rehydration ctor `ProductAxis(UUID id, UUID attributeId, int sortOrder, List<AxisOption> options)`.
  - `record DescriptiveValue(UUID id, UUID valueId, String text, BigDecimal number, Boolean bool)`
  - `final class DescriptiveAttribute` — `id() attributeId() sortOrder() values()`; public rehydration ctor `DescriptiveAttribute(UUID id, UUID attributeId, int sortOrder, List<DescriptiveValue> values)`.
  - `final class ProductAttributeSet extends AggregateRoot`:
    - ctor `ProductAttributeSet(UUID productId, ProductStatus status, List<ProductAxis> axes, List<DescriptiveAttribute> descriptives)`
    - `void replaceAxes(List<AxisSpec> specs, Map<UUID, Attribute> dictionary, Set<UUID> valueIdsUsedByVariants, Supplier<UUID> newId)`
    - `void replaceDescriptives(List<DescriptiveSpec> specs, Map<UUID, Attribute> dictionary, Supplier<UUID> newId)`
    - `productId() status() axes() descriptives()` — axes and descriptives ordered by `sortOrder`; options in the order given.
    - An entry kept from before keeps its `id` (axis, option, descriptive attribute, and — for a value that stays or a scalar/single value that changes — the descriptive value row id). This is what Task 2's in-place updates rely on.
  - `interface ProductAttributeSetRepository { ProductAttributeSet load(UUID productId, ProductStatus status); ProductAttributeSet save(ProductAttributeSet set); Set<UUID> valueIdsUsedByVariants(UUID productId); }`
  - `AttributeRepository.findAllById(Collection<UUID> ids)` — one query, values loaded.
  - `ErrorCode.PRODUCT_AXES_LOCKED` (409) + bundle lines.

- [ ] **Step 1: Write the failing tests** (`ProductAttributeSetTest`, plain JUnit). Build dictionaries in memory with `Attribute.create(...)` + `addValue(...)`; helpers `draft(...)`, `approved(...)` build sets with `ProductStatus.DRAFT` / `APPROVED`.

```java
@Test void aDraftProductGetsItsAxesWithOptionsInOrder() {
    var set = draft();
    set.replaceAxes(List.of(new AxisSpec(CAPACITY.id(), 0, List.of(OZ12, OZ16)),
                            new AxisSpec(COLOR.id(), 1, List.of(CLEAR))), dictionary(), Set.of(), ids());
    assertThat(set.axes()).extracting(ProductAxis::attributeId).containsExactly(CAPACITY.id(), COLOR.id());
    assertThat(set.axes().getFirst().options()).extracting(AxisOption::valueId).containsExactly(OZ12, OZ16);
}
@Test void addingOrRemovingAnAxisIsLockedOnceTheProductLeftDraft() {
    var set = approved(axis(CAPACITY, OZ12));
    assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ12), spec(COLOR, CLEAR)), dictionary(), Set.of(), ids()), PRODUCT_AXES_LOCKED);
    assertCode(() -> set.replaceAxes(List.of(), dictionary(), Set.of(), ids()), PRODUCT_AXES_LOCKED);
}
@Test void anApprovedProductMayGainAnOptionAndReorder() {
    var set = approved(axis(CAPACITY, OZ12));
    set.replaceAxes(List.of(new AxisSpec(CAPACITY.id(), 5, List.of(OZ12, OZ16))), dictionary(), Set.of(), ids());
    assertThat(set.axes().getFirst().options()).extracting(AxisOption::valueId).containsExactly(OZ12, OZ16);
    assertThat(set.axes().getFirst().sortOrder()).isEqualTo(5);
}
@Test void aPendingOrDiscontinuedProductAcceptsNoChange() { /* PENDING_APPROVAL and DISCONTINUED → INVALID_PRODUCT_STATUS_TRANSITION for both replace methods */ }
@Test void removingAnOptionAVariantUsesIsRefused() {
    var set = draft(axis(CAPACITY, OZ12, OZ16));
    assertCode(() -> set.replaceAxes(List.of(spec(CAPACITY, OZ16)), dictionary(), Set.of(OZ12), ids()), ATTRIBUTE_IN_USE);
}
@Test void axisRules() {
    /* each → VALIDATION_FAILED: TEXT attribute as axis; MULTISELECT as axis; axis with no options;
       same attribute twice; same value twice in one axis; value of another attribute;
       newly added inactive attribute; newly added inactive value */
}
@Test void anInactiveAttributeOrValueMayStay() { /* set with CAPACITY/OZ12 kept after both deactivated → no exception */ }
@Test void anAttributeCannotBeInBothRoles() {
    var set = draft();
    set.replaceDescriptives(List.of(new DescriptiveSpec(MATERIAL.id(), 0, List.of(PET), null, null, null)), dictionary(), ids());
    assertThatThrownBy(() -> set.replaceAxes(List.of(spec(MATERIAL, PET)), dictionary(), Set.of(), ids()))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(e.getMessage()).contains("MATERIAL");
            });
    var other = draft(axis(CAPACITY, OZ12));
    assertCode(() -> other.replaceDescriptives(List.of(new DescriptiveSpec(CAPACITY.id(), 0, List.of(OZ12), null, null, null)),
            dictionary(), ids()), VALIDATION_FAILED);
}
@Test void descriptiveValuesFollowTheType() {
    /* SELECT: one valueId ok, two → VALIDATION_FAILED; MULTISELECT: two ok; COLOR: one;
       TEXT: blank → VALIDATION_FAILED; NUMBER without number → VALIDATION_FAILED;
       BOOLEAN with valueIds → VALIDATION_FAILED; any type with a foreign field set → VALIDATION_FAILED */
}
@Test void keptEntriesKeepTheirIds() {
    var set = draft(axis(CAPACITY, OZ12));
    UUID axisId = set.axes().getFirst().id(); UUID optionId = set.axes().getFirst().options().getFirst().id();
    set.replaceAxes(List.of(spec(CAPACITY, OZ12, OZ16)), dictionary(), Set.of(), ids());
    assertThat(set.axes().getFirst().id()).isEqualTo(axisId);
    assertThat(set.axes().getFirst().options().getFirst().id()).isEqualTo(optionId);
    /* and: a SELECT descriptive changed from FABRIC to OAK keeps its DescriptiveValue id; a TEXT changed keeps its id */
}
```

- [ ] **Step 2: Run to verify it fails**

Run (in the worktree): `mvn -q test -Dtest=ProductAttributeSetTest`
Expected: compilation failure — `ProductAttributeSet` not found.

- [ ] **Step 3: Implement the types listed under Interfaces.** The aggregate's javadoc names the two triggers each rule re-states (`check_product_attribute`, `check_product_attribute_option`, `check_descriptive_value`) and why axes freeze at DRAFT (spec D3: live stock would have no value on a new axis). `findAllById` on the adapter maps with the existing `AttributePersistenceMapper.toDomain`.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='ProductAttributeSetTest,AttributeTest,MessageBundleTest,ArchitectureTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockflow/product/internal/domain/ src/main/java/com/stockflow/product/internal/repository/AttributeRepositoryAdapter.java src/main/java/com/stockflow/common/error/ErrorCode.java src/main/resources/i18n/ src/test/java/com/stockflow/product/internal/domain/ProductAttributeSetTest.java
git commit -m "feat(product): product attribute set with axis and descriptive rules (SCRUM-58)"
```

---

### Task 2: Persistence for a product's attribute set

**Files:**
- Create: `entity/ProductAttributeJpaEntity.java`, `entity/ProductAttributeOptionJpaEntity.java`, `entity/ProductDescriptiveValueJpaEntity.java`, `repository/ProductAttributeJpaRepository.java`, `repository/ProductAttributeSetPersistenceMapper.java`, `repository/ProductAttributeSetRepositoryAdapter.java`
- Test: `src/test/java/com/stockflow/product/internal/repository/ProductAttributeSetRepositoryAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1's aggregate and port; `AttributeRole` (PR 1); `ConstraintViolations` (PR 1, package-private in `repository`).
- Produces:
  - `ProductAttributeJpaEntity extends BaseEntity` → `product.product_attributes`; `productId`, `attributeId`, `role` (`AttributeRole`, STRING) all `updatable = false`; `sortOrder`; `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true)` `options` and `descriptiveValues`.
  - `ProductAttributeOptionJpaEntity` → `product_attribute_options` (`@ManyToOne(LAZY)` parent, `attributeValueId`).
  - `ProductDescriptiveValueJpaEntity` → `product_descriptive_values` (`attributeValueId`, `valueText`, `valueNumber`, `valueBoolean`, all updatable).
  - `ProductAttributeJpaRepository extends BaseJpaRepository<ProductAttributeJpaEntity>`:
    - `@Query("select distinct pa from ProductAttributeJpaEntity pa left join fetch pa.options where pa.productId = :productId") List<…> findWithOptionsByProductId(UUID productId)`
    - `@Query("select distinct pa from ProductAttributeJpaEntity pa left join fetch pa.descriptiveValues where pa.productId = :productId") List<…> findWithDescriptiveValuesByProductId(UUID productId)` — two queries, not one, because fetching two bags at once is a `MultipleBagFetchException`.
    - native `valueIdsUsedByVariants(productId)`: `select o.attribute_value_id from product.product_attribute_options o join product.product_attributes pa on pa.id = o.product_attribute_id where pa.product_id = :productId and exists (select 1 from product.variant_attribute_values v where v.product_attribute_option_id = o.id)`.
  - `ProductAttributeSetRepositoryAdapter implements ProductAttributeSetRepository` (`@Repository`, package-private). **`save` is two-phase:** (1) remove every row the aggregate no longer has (whole product attributes, options, descriptive values) and `jpa.flush()`; (2) update kept rows in place (`sortOrder`, a changed single/scalar descriptive value on its existing row) and add new rows, then `jpa.flush()`. Flushes go through the Spring Data repository proxy so exceptions are translated; a FK violation (23503, an option a variant took meanwhile) becomes `ATTRIBUTE_IN_USE`. Returns `load(...)` of the saved state.

- [ ] **Step 1: Write the failing integration test** (`@IntegrationTest @Import(PostgresContainer.class) @Transactional`). Create a DRAFT product with `ProductService.create(...)` as `ProductPimIntegrationTest.create` does, and dictionary attributes with `AttributeDictionaryService` (PR 1). Demo product `SOFA-3S` (`DemoData.PRODUCT_SOFA`, PUBLISHED) already has axis COLOR {GREY, used by variant SOFA-3S-GREY} and descriptive MATERIAL {FABRIC}.

```java
@Test void theDemoSofaLoadsWithItsAxisAndDescriptive() {
    var set = repository.load(PRODUCT_SOFA, ProductStatus.PUBLISHED);
    assertThat(set.axes()).singleElement().satisfies(a -> assertThat(a.attributeId()).isEqualTo(demoId("demo:attr:COLOR")));
    assertThat(set.descriptives()).singleElement().satisfies(d -> assertThat(d.values()).extracting(DescriptiveValue::valueId)
            .containsExactly(demoId("demo:val:MATERIAL:FABRIC")));
    assertThat(repository.valueIdsUsedByVariants(PRODUCT_SOFA)).containsExactly(demoId("demo:val:COLOR:GREY"));
}
@Test void aSetRoundTrips() { /* draft product: replaceAxes + replaceDescriptives (SELECT, MULTISELECT, TEXT, NUMBER, BOOLEAN), save, flush+clear, load → equal content */ }
@Test void savingAnUnchangedSetTouchesNoRow() {
    /* save once; record ids + versions from jdbc; load, replaceAxes/replaceDescriptives with the same specs, save, flush;
       ids and versions in product_attributes / options / descriptive_values unchanged */
}
@Test void aSingleDescriptiveValueIsChangedInPlace() {
    /* SELECT descriptive FABRIC → OAK: the product_descriptive_values row id is unchanged and its attribute_value_id is OAK */
}
@Test void removalsAreFlushedBeforeInserts() {
    /* MULTISELECT {A,B} → {B,C} and axis options {12OZ,16OZ} → {16OZ,22OZ} in one save: succeeds; rows are exactly {B,C} / {16OZ,22OZ} */
}
@Test void loadingIsTwoQueries() { /* Hibernate Statistics around load(): prepareStatementCount == 2 */ }
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=ProductAttributeSetRepositoryAdapterIntegrationTest`
Expected: compilation failure / no `ProductAttributeSetRepository` bean.

- [ ] **Step 3: Implement entities, repository, mapper (`static ProductAttributeSet toDomain(UUID productId, ProductStatus status, List<ProductAttributeJpaEntity> rows)`; merge helpers) and the adapter.**

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='ProductAttributeSetRepositoryAdapterIntegrationTest,ArchitectureTest,ModularityTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockflow/product/internal/entity/ProductAttribute*.java src/main/java/com/stockflow/product/internal/entity/ProductDescriptiveValueJpaEntity.java src/main/java/com/stockflow/product/internal/repository/ProductAttribute*.java src/test/java/com/stockflow/product/internal/repository/ProductAttributeSetRepositoryAdapterIntegrationTest.java
git commit -m "feat(product): persist a product's axes, options and descriptive values (SCRUM-58)"
```

---

### Task 3: `ProductAttributeService`

**Files:**
- Create: `service/ProductAttributeService.java`
- Test: `src/test/java/com/stockflow/product/internal/service/ProductAttributeIntegrationTest.java`, `src/test/java/com/stockflow/product/internal/service/ProductAttributeConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductRepository.findForUpdate(ProductId)` / `findById` / `existsById` (existing), `ProductAttributeSetRepository` (Task 1/2), `AttributeRepository.findAllById` (Task 1).
- Produces (`@Service @Transactional public class ProductAttributeService`):
  - `record ProductAttributesView(UUID productId, List<AxisView> axes, List<DescriptiveView> descriptives)`
  - `record AxisView(UUID attributeId, String code, String name, AttributeDataType dataType, int sortOrder, List<OptionView> options)`
  - `record OptionView(UUID valueId, String code, String label, String hexColor, BigDecimal valueNumber, boolean active, boolean usedByVariant)`
  - `record DescriptiveView(UUID attributeId, String code, String name, AttributeDataType dataType, String unit, int sortOrder, List<ValueRef> values, String text, BigDecimal number, Boolean bool)` with `record ValueRef(UUID valueId, String code, String label, boolean active)`
  - `@Transactional(readOnly = true) ProductAttributesView get(UUID productId)` — `PRODUCT_NOT_FOUND`; dictionary for every attribute in the set in one `findAllById`.
  - `@Auditable(action = UPDATE, resourceType = "product-attributes", resourceId = "#productId") ProductAttributesView replaceAxes(UUID productId, List<AxisSpec> axes)`
  - `@Auditable(… same …) ProductAttributesView replaceDescriptives(UUID productId, List<DescriptiveSpec> descriptives)`
  - Write order, both methods: `products.findForUpdate` (→ `PRODUCT_NOT_FOUND`) → `findAllById` on the requested attribute ids (missing → `ATTRIBUTE_NOT_FOUND`) plus the set's current ones → `load(productId, status)` → aggregate method → `save` → view.

- [ ] **Step 1: Write the failing tests.**

`ProductAttributeIntegrationTest` (`@Transactional`):
```java
@Test void getShowsTheDemoSofa() { /* axes: COLOR with option GREY usedByVariant=true; descriptives: MATERIAL FABRIC */ }
@Test void aDraftProductTakesAxesAndDescriptives() { /* create product, replaceAxes + replaceDescriptives, get → as sent */ }
@Test void theSofaCanGainAnOptionButNotAnAxis() {
    /* add a value BLUE to COLOR via AttributeDictionaryService; replaceAxes(SOFA, [COLOR {GREY, BLUE}]) ok;
       replaceAxes(SOFA, [COLOR {GREY}, LENGTH {160}]) → PRODUCT_AXES_LOCKED */
}
@Test void removingTheSofasUsedOptionIsRefused() { /* replaceAxes(SOFA, [COLOR {BLUE}]) → ATTRIBUTE_IN_USE */ }
@Test void unknownProductOrAttributeIsNotFound() { /* PRODUCT_NOT_FOUND, ATTRIBUTE_NOT_FOUND */ }
@Test void aPendingProductAcceptsNoChange() { /* submit a draft product (ProductService.submit), replaceDescriptives → INVALID_PRODUCT_STATUS_TRANSITION */ }
```
`ProductAttributeConcurrencyIntegrationTest` (no class-level `@Transactional`; pattern: PR 1's `CategoryAttributeConcurrencyIntegrationTest`): `concurrentAxisSavesSerialiseOnTheProduct` — two threads with `TransactionTemplate`, the first holds the product lock after a latch, both replace a draft product's axes with different option sets; neither throws; the final options equal the second request; cleanup deletes what was committed.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='ProductAttributeIntegrationTest,ProductAttributeConcurrencyIntegrationTest'`
Expected: compilation failure (`ProductAttributeService` unknown).

- [ ] **Step 3: Implement `ProductAttributeService`.** Views are built from the aggregate plus the dictionary map — one path, as `AttributeDictionaryService` does.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest='ProductAttributeIntegrationTest,ProductAttributeConcurrencyIntegrationTest,ProductPimIntegrationTest'`
Expected: PASS (`ProductPimIntegrationTest` guards that the variant/product flows are untouched).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockflow/product/internal/service/ProductAttributeService.java src/test/java/com/stockflow/product/internal/service/ProductAttribute*IntegrationTest.java
git commit -m "feat(product): product axes and descriptive attributes service (SCRUM-58)"
```

---

### Task 4: `ProductAttributeController`, permissions, and the full build

**Files:**
- Create: `controller/ProductAttributeController.java`, `controller/ProductAttributeWebMapper.java`, `controller/dto/ReplaceAxesRequest.java`, `controller/dto/ReplaceDescriptiveAttributesRequest.java`, `controller/dto/ProductAttributesResponse.java`
- Modify: `controller/ProductResources.java` (`VARIANTS = "product-variants"`)
- Test: `src/test/java/com/stockflow/product/internal/controller/ProductAttributeControllerHttpTest.java`, `src/test/java/com/stockflow/product/ProductAttributeSecurityIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 service, views and specs.
- Produces (base `/api/v1/products/{productId}`):

| Handler | Mapping | Permission | Body |
|---|---|---|---|
| `get` | `GET /attributes` | `product-variants` READ | — |
| `replaceAxes` | `PUT /axes` | `product-variants` UPDATE | `ReplaceAxesRequest(@NotNull List<@Valid Axis> axes)`, `Axis(@NotNull UUID attributeId, Integer sortOrder, @NotEmpty List<@NotNull UUID> optionValueIds)` |
| `replaceDescriptives` | `PUT /descriptive-attributes` | `product-products` UPDATE | `ReplaceDescriptiveAttributesRequest(@NotNull List<@Valid Entry> attributes)`, `Entry(@NotNull UUID attributeId, Integer sortOrder, List<UUID> valueIds, @Size(max = 2000) String text, @Digits(integer = 14, fraction = 4) BigDecimal number, Boolean bool)` |

  - `ProductAttributesResponse` mirrors `ProductAttributesView` (enums as `name()`); `sortOrder` defaults to the request's position in the list when null.
  - `@PermissionResource(code = ProductResources.VARIANTS, group = "Product", label = "Product variants", route = "/admin/products", apiPath = "/api/v1/products", actions = {VIEW_PAGE, READ, CREATE, UPDATE, APPROVE, EXPORT})` on this controller — the first controller guarded by `product-variants`; match the seed exactly (check `PermissionResource`'s attributes and `ProductController`'s declaration for the field set).

- [ ] **Step 1: Write the failing tests.**

`ProductAttributeControllerHttpTest` (standalone MockMvc, mocked service, like PR 1's `AttributeControllerHttpTest`): a PUT `/axes` reaches `replaceAxes` with mapped `AxisSpec`s (null `sortOrder` → list position); an axis with an empty `optionValueIds` is 400 with no service call; a descriptive `number` with 15 integer digits is 400; every public handler carries `@RequiresPermission` with the resource in the table above.

`ProductAttributeSecurityIntegrationTest` (harness of PR 1's `AttributeSecurityIntegrationTest`): `product-variants:READ` can GET the sofa's attributes but gets 403 on PUT `/axes`; `product-products:UPDATE` alone gets 403 on PUT `/axes` and is allowed past security on PUT `/descriptive-attributes` (status is not 401/403); anonymous → 401.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='ProductAttributeControllerHttpTest,ProductAttributeSecurityIntegrationTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement controller, DTOs, mapper and the `VARIANTS` constant.** Handlers public.

- [ ] **Step 4: Run to verify it passes, then the full build**

Run: `mvn -q test -Dtest='ProductAttributeControllerHttpTest,ProductAttributeSecurityIntegrationTest'` → PASS.
Run: `python -X utf8 tools/verify.py` → all checks pass.
Run: `mvn verify` → BUILD SUCCESS. A failure outside the product module is reported, not "fixed" by disabling a rule.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockflow/product/internal/controller/ src/test/java/com/stockflow/product/
git commit -m "feat(product): product axes and descriptive attributes REST API (SCRUM-58)"
```
