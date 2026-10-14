package com.stockflow.warehouse.internal.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The fonts embedded in location labels: Noto Sans Regular and Bold, SIL OFL 1.1 (licence in
 * {@code fonts/OFL.txt}).
 *
 * <p>Embedded because the 14 standard PDF fonts are WinAnsi only and cannot draw Vietnamese -
 * a warehouse name like {@code Kho Hồ Chí Minh} would fail to render. Read once here, at startup,
 * so a missing font file stops the application instead of the first print request.</p>
 */
@Component
class LabelFonts {

    private final byte[] regular;
    private final byte[] bold;

    LabelFonts() {
        this.regular = read("fonts/NotoSans-Regular.ttf");
        this.bold = read("fonts/NotoSans-Bold.ttf");
    }

    byte[] regular() {
        return regular;
    }

    byte[] bold() {
        return bold;
    }

    private static byte[] read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Label font " + path + " is missing from the classpath", e);
        }
    }
}
