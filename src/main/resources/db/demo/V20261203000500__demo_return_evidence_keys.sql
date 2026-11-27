-- =============================================================================
-- Demo: the claim photos of the demo returns get storage keys the storage layer accepts. Demo only (db/demo).
--
-- V20261128000200 stored them as 'returns/demo/<RMA>-<n>.jpg'. 'returns' is no file category (StorageKeys,
-- FileCategory: return photos live under 'return-evidence/'), so reading any of those returns failed: the detail signs a
-- thumbnail for each photo, StorageKeys.evidenceCopyOf rejected the key, and GET /api/v1/returns/{id} answered 503
-- STORAGE_ERROR - the back office return detail showed "Không tải được dữ liệu" for every demo return.
--
-- The photos keep their names and dates; only the category prefix changes. No object exists behind either key (demo
-- data), so a thumbnail shows as a broken image and the page falls back to the photo's own link, as for any photo
-- stored before thumbnails were made (FileTransfers.evidenceThumbnailLink).
--
-- The table is append-only (tg_return_request_evidence_append_only); its trigger stands aside for this one statement,
-- which renames demo keys and adds or removes no photo.
-- =============================================================================

ALTER TABLE ordering.return_request_evidence DISABLE TRIGGER tg_return_request_evidence_append_only;

UPDATE ordering.return_request_evidence
   SET file_key = 'return-evidence/demo/' || substring(file_key FROM length('returns/demo/') + 1)
 WHERE file_key LIKE 'returns/demo/%';

ALTER TABLE ordering.return_request_evidence ENABLE TRIGGER tg_return_request_evidence_append_only;
