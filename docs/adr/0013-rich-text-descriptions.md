# ADR-0013 — Descriptions are rich text, cleaned on the server; the storefront page shows the product's facts

* **Status:** Accepted
* **Date:** 2026-11-27
* **Deciders:** repository owner (2026-11-27)
* **Project:** StockFlowCommerce (FA26SE029)

---

## Context

The storefront shows a product page per published product, and a variant (quy cách) is what a customer actually looks
at and designs on. Until now a description was plain text on the product only, and the page showed little else: a
title, that text, SEO, and each variant's SKU and name. Brand, category and descriptive attributes were kept but never
shown.

The back office writes with Tiptap (`@tiptap/react`, StarterKit and Image), which produces HTML, and the storefront
renders it with DOMPurify.

## Decision

1. **A description is the editor's HTML, cleaned on the server before it is stored** (`RichTextPolicy`, jsoup). The
   allow-list is what StarterKit and Image write: `p`, `br`, `h1`-`h3`, `strong`/`b`, `em`/`i`, `u`, `s`, `code`,
   `pre`, `blockquote`, `ul`, `ol`, `li`, `hr`, `a`, `img`. Links keep `href`/`title`, go to `http`, `https` or
   `mailto` only, and get `rel="nofollow noopener noreferrer"`. Images keep `src`/`alt`/`title`, and only an image under
   the system's CDN prefix `product-images/` is kept. Everything else (`script`, `style`, `class`, event handlers,
   `iframe`, `svg`, other sites' images, `data:` URLs) is removed. An empty editor is no description. At most 100 000
   characters (`VALIDATION_FAILED`; `ck_variants_description` says it again for variants).
   The client's DOMPurify is a second layer, not the defence: anyone with the permission can call the API directly.
2. **Both the product and each variant have a description.** The product's stays `product.products.description`, now
   HTML. A variant's own is `product.variants.description` (V20270106000300), edited with
   `PUT /api/v1/products/{id}/variants/{variantId}/description` (`product-variants:UPDATE`) in any variant status while
   the product takes changes. On the storefront a variant without its own shows the product's.
3. **Images inside a description are uploaded on their own** (`POST /api/v1/products/{id}/description-images`,
   `product-products:UPDATE`): scanned, resized, and public on the CDN at once, because the editor must show the image
   while the text is still being written and a description has no publish step. Only the re-encoded renditions are
   kept, so the photograph's metadata never reaches a customer. No row lists them: the HTML that names an image is
   what it belongs to.
4. **Search indexes text, the page shows HTML.** `PublicationProduct.description` is the description's text (tags
   gone), which the catalog copies into its listing and its tsvector, so a search for "strong" does not match every
   product. The page reads the HTML live from the product (`ProductPublication.storefront`): a published product's
   description cannot change (it is edited only while DRAFT), so the live HTML is the published one.
5. **The storefront product page shows the product's facts.** `GET /api/v1/catalog/products/{slug}` adds `brand`,
   `category` (the primary one), `attributes` (the descriptive attributes and their values) and, per ACTIVE variant,
   `variantId`, `options` (axis and value) and `descriptionHtml` with `ownDescription`. `description` is now HTML.

## Consequences

* A storefront must render `description` and `descriptionHtml` as HTML (through its own sanitizer), not as text.
* A description image that no description names any more stays on the CDN; the rendition prefix is not swept, like a
  replaced gallery image's published copy.
* A variant's description shows on the storefront as soon as it is saved, with no approval, like a variant image since
  ADR-0012.
* One more dependency, jsoup, pinned in `pom.xml`.
