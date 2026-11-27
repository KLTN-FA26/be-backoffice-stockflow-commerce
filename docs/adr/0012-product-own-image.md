# ADR-0012 — A product has one image of its own; images are published without a second person

* **Status:** Accepted — amends decision D5 of the product module ("images belong to a variant, not to the product")
  and drops the four-eyes rule on publishing a variant's image
* **Date:** 2026-11-27
* **Deciders:** repository owner (product owner's request, 2026-11-27)
* **Project:** StockFlowCommerce (FA26SE029)

---

## Context

Decision D5 put every image on a variant (`product.media`, at most 20 per variant, published by a second person). The
storefront card of a product had no image of its own: `ProductPublication.cards` picked the lead image across the
variants' published images.

The storefront groups products (category pages, search and list pages), and there the card stands for the product as
a whole, not for one of its variants. The owner wants to choose that picture directly, while the product page keeps
showing what each variant looks like.

## Decision

1. **One image per product**, on the product row: the `image_*` columns of `product.products` (V20270106000100). "At
   most one" is the shape of the schema; `ck_products_image` says "all of the image or none of it". The variants'
   images stay many per variant, published by hand, the product page's gallery (but see 6).
2. **Where it shows:** the storefront card (`PublishedProductCard.primaryImageUrl`), i.e. every list and category
   page. A product without its own image falls back to the variants' lead image, as before. The product page
   (`publishedImages`, the public gallery) still shows the variants' images only.
3. **No second person.** The image is public as soon as its product is published: its renditions are copied to the
   CDN prefix by the upload of a published product's image, or by the product's publication, whichever comes second.
   A draft's image is never copied there.
4. **Uploading replaces.** `PUT /api/v1/products/{id}/image` (multipart, optional `altText`) swaps the image; `PATCH`
   edits the alt text; `DELETE` takes it off. Permission: `product-products` UPDATE (READ for `GET`), like the
   variants' images. A product that is PENDING_APPROVAL or DISCONTINUED takes no change, like its variants' images.
5. **Not part of the product's version.** The columns are not mapped on `ProductJpaEntity` and are written with plain
   SQL (`ProductImageRepository`), so an edit of the product's details cannot write a stale image back, and changing
   the image does not make a details edit in progress fail on its version - the same as a variant's image.
6. **No four-eyes on a variant's image either** (owner decision 2026-11-27, same day). The uploader may publish
   their own image: `ProductMediaService.publish` no longer refuses it (`SELF_APPROVAL_NOT_ALLOWED`), and
   `ProductPublication` no longer hides an image published by its uploader. Publishing still takes
   `product-products:APPROVE` and an approved product; the step itself, and withdrawal, stay.

## Consequences

* One person holding `product-products:APPROVE` can now put an image on the storefront unseen by anyone else; the
  audit trail (`product-media` APPROVE entries, `published_by`) is what remains to answer who did it.
* The upload pipeline is the variants' (`ProductImageProcessor`, virus scan, renditions, objects written with no
  transaction open, attached under the product row lock), so the same limits and errors apply.
* The orphaned-file sweep counts `products.image_key` as a reference (`ProductMediaFileReferences`); deleting a draft
  product deletes its image's objects with the variants' (`ProductMediaService.storedKeysOf`).
* A replaced or removed image's private objects are deleted after the commit; its published copies stay, as a deleted
  variant image's do, because a CDN may still serve them for its cache lifetime.
