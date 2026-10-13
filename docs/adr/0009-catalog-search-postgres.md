# ADR-0009: Catalog search on PostgreSQL, not Elasticsearch

Status: accepted (2026-10-13, plan decision §6 #9). SCRUM-170, 181, 182; docs 13 §4.2 and §6.

## Context

The B2B portal needs a search box on the catalogue (docs 13 §4.2: signed-in customers only, published
products only, sort by newest / popular / price). SCRUM-170 was written for Elasticsearch, which the
code never used: it appears only in the development `docker-compose.yml`, and the production compose
already drops it. The catalogue is a wholesale range of cups and packaging: hundreds of products, not
millions, with titles and descriptions in Vietnamese typed by customers with or without diacritics.

## Decision

Search runs in PostgreSQL 16, in the `catalog` schema, with two extensions that ship in the
`postgres:16-alpine` image (and are "trusted", so the database owner may create them):

- `unaccent`, pinned in `catalog.immutable_unaccent(text)` so it is IMMUTABLE and may sit in an index
  or a generated column. "ban go" finds "Bàn gỗ"; `đ` folds to `d`.
- `pg_trgm`, for typo tolerance (trigram similarity) and for SKU "starts with" lookups.

`catalog.product_listing.search_document` is a **generated** tsvector (`simple` configuration, title
weight A, description C) with a GIN index. Being generated, it cannot disagree with the published
title; docs 13's "index differs from source" edge case has no way to arise for text, and no re-index
event or job exists. Ranking is

    score = ts_rank_cd * w1 + similarity(title, query) * w2 + [exact SKU] * w3 + [available to promise > 0] * w4

with the weights in `stockflow.catalog.search` (SCRUM-182). Visibility (enabled, published title, at
least one published entry) is applied in SQL **before** anything is cut, and the product module is
asked again for the candidates, so a page is full, the total is true and a just-discontinued product
never appears.

## Consequences

- Stock lives in the `inventory` schema and a cross-schema JOIN is forbidden, so the in-stock bonus is
  applied in memory over the best `ranking-window` (default 500) matches. The page total is the size of
  that window. Revisit if the catalogue ever approaches it.
- Sorting by price is not offered until SCRUM-428 (price per customer); an unknown `sort` is
  `UNSUPPORTED_PARAMETER`.
- Search text is customer free text: never logged, never echoed in an error.
- No new infrastructure, no new failure mode, nothing to back up or keep in sync. If search ever has to
  scale past one database, an engine can be added behind `CatalogSearchRepository` without touching the
  API.
