package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The display copies of an evidence photo - a return photo, a damage photo - written next to the original when it is
 * stored: a thumbnail for lists and a large copy for viewing, both re-encoded without metadata
 * ({@link ImageRenditions}). The original stays private and is downloaded only on purpose.
 *
 * <p>A photo that cannot be rendered (above the decode limit - a 108 MP phone photo - or in a form the decoders do not
 * read), or not now (every decoder busy past its wait), is still kept: the copies are an improvement for viewers, never
 * a gate on evidence. It is then shown as uploaded.</p>
 */
final class EvidenceCopies {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCopies.class);

    static final int THUMBNAIL = 256;
    static final int DISPLAY = 1600;

    private EvidenceCopies() {
    }

    /** The keys of the copies written for {@code originalKey}; none for a category without display copies. */
    static List<String> store(FileStorage storage, ImageRenditions images, FileCategory category, String originalKey,
                              byte[] bytes) {
        if (!StorageKeys.hasDisplayCopies(category)) {
            return List.of();
        }
        List<ImageRenditions.Rendition> rendered;
        try {
            rendered = images.render(bytes, true, THUMBNAIL, DISPLAY);
        } catch (BusinessException unrenderable) {
            if (unrenderable.errorCode() == ErrorCode.RATE_LIMITED) {
                log.warn("No display copies for {}: every image decoder was busy; it is shown as uploaded", originalKey);
            } else {
                log.info("No display copies for {} ({}); it is shown as uploaded", originalKey, unrenderable.getMessage());
            }
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        try {
            for (ImageRenditions.Rendition copy : rendered) {
                String key = StorageKeys.evidenceCopyOf(originalKey, copy.edge());
                storage.storeDerived(key, copy.contentType(), copy.bytes());
                keys.add(key);
            }
        } catch (RuntimeException failure) {
            for (String key : keys) {
                try {
                    storage.delete(key);
                } catch (RuntimeException cleanupFailure) {
                    log.error("Orphaned display copy requires cleanup: {}", key, cleanupFailure);
                }
            }
            throw failure;
        }
        return List.copyOf(keys);
    }
}
