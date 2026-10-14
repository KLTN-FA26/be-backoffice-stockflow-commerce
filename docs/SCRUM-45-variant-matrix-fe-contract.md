# SCRUM-45 → SCRUM-373: the variant matrix contract for the FE

What the backend of SCRUM-45 (PRs #98, #100, #103 and this one) gives the variant matrix screen.
The design and its reasons are in
[`superpowers/specs/2026-10-13-scrum-45-variant-sku-design.md`](superpowers/specs/2026-10-13-scrum-45-variant-sku-design.md).
All bodies use the usual `ApiResponse` envelope.

## 1. The dictionary (`product-attributes`)

| | |
|---|---|
| `GET /api/v1/attributes?q=&dataType=&active=` | attributes with their values. Show only `active` values when the user picks new options |
| `GET /api/v1/categories/{id}/attributes` | the category's suggested attributes, to pre-fill the form. A suggestion only: nothing is enforced |

Only `SELECT` and `COLOR` attributes can be variant axes.

## 2. Creating a product with its matrix in one call

`POST /api/v1/products` takes the usual fields plus an optional `axes`:

```json
{
  "code": "CUP12",
  "name": "Ly giấy 12oz", "nameEn": "Paper cup 12oz", "taxClass": "STANDARD",
  "axes": [
    { "attributeId": "<CAPACITY>", "optionValueIds": ["<12OZ>", "<16OZ>"] },
    { "attributeId": "<COLOR>", "sortOrder": 1, "optionValueIds": ["<CLEAR>", "<RED>"] }
  ]
}
```

- **Without `axes`:** you get the old behaviour. The product has one default variant, and its SKU is the code.
- **With `axes`:** one transaction creates the axes and **every combination** of their options. Each
  combination gets a SKU `CODE-VALUE-VALUE` (`CUP12-12OZ-CLEAR`, …) in axis order. The first combination
  (each axis's first value in dictionary order) is the default variant. No variant ever has the bare code
  as its SKU.
- `sortOrder` may be left out. The axis's position in the list is then used.
- **Errors:** if anything is refused, no product is created.
  - 409 `VARIANT_SKU_ALREADY_EXISTS`: a SKU is taken, or two combinations would build the same SKU (value codes containing `-`).
  - 400 `VALIDATION_FAILED`: a built SKU is longer than 64 characters. This endpoint takes no SKUs, so shorten the product or value codes.
  - 400 `VARIANT_MATRIX_TOO_LARGE`: more than 100 combinations.
  - 400 `VALIDATION_FAILED`: a value is not on that attribute, a value is deactivated, or the attribute cannot be an axis.
  - 404 `ATTRIBUTE_NOT_FOUND`: the attribute does not exist.
- The response is the product summary. To show the matrix, read the variants (§4).

## 3. Changing axes later (`product-variants`)

| | |
|---|---|
| `GET /api/v1/products/{id}/attributes` | axes (options in dictionary order) and descriptive attributes |
| `PUT /api/v1/products/{id}/axes` | the whole axis list, same shape as `axes` above |
| `PUT /api/v1/products/{id}/descriptive-attributes` | non-axis attributes (material, …) |

You can add or remove an axis only while the product is DRAFT (otherwise 409 `PRODUCT_AXES_LOCKED`).
Adding an axis once a variant uses options is also 409 `PRODUCT_AXES_LOCKED`. Removing an axis or an
option that a variant uses is 409 `ATTRIBUTE_IN_USE`. So a product created with `axes` has its axis
set fixed from birth: every variant uses a value on every axis. You can add an option at any time the
product accepts changes.

## 4. Variants

| | |
|---|---|
| `GET /api/v1/products/{id}/variants` | each variant has `sku`, `name`, `status`, `defaultVariant`, `attributeSignature`, `position`, and `options[]` (`attributeCode`, `valueCode`, `label`) |
| `POST /api/v1/products/{id}/variants/generation` | body `{ "optionValueIds": { "<attributeId>": ["<valueId>", …] } }` or `{}` (all). Creates only the missing combinations, max 100. Answers `{ created[], skipped }` |
| `POST /api/v1/products/{id}/variants` | one combination: `{ "optionValueIds": [...], "sku"?, "name"?, "position"? }`. `sku` and `name` default from the rule |
| `PUT /api/v1/products/{id}/variants/{variantId}` | rename the SKU or change options while the variant is DRAFT |

- **Removed field:** variant requests no longer carry `attributeSignature`. The server derives it.
- **SKU format:** 1–64 characters from `A–Z 0–9 . _ -`, starting with a letter or digit. The server upper-cases it.
