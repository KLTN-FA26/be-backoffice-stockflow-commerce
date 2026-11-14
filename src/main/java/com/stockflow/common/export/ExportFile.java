package com.stockflow.common.export;

/**
 * An export as a service hands it to its controller: the file and the name it is saved under
 * ({@link ExportResponses#xlsx(ExportFile)}).
 */
public record ExportFile(String filename, byte[] content) {
}
