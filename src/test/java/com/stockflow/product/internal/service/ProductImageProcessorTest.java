package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.storage.FileUpload;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductImageProcessorTest {
    private final ProductImageProcessor processor = new ProductImageProcessor();
    @Test void appliesPhoneRotationWithoutCroppingPixels() {
        var original = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        original.setRGB(0, 0, java.awt.Color.RED.getRGB());
        original.setRGB(2, 1, java.awt.Color.BLUE.getRGB());
        var rotated = ProductImageProcessor.orient(original, 6);
        assertThat(rotated.getWidth()).isEqualTo(2);
        assertThat(rotated.getHeight()).isEqualTo(3);
        assertThat(rotated.getRGB(1, 0)).isEqualTo(java.awt.Color.RED.getRGB());
        assertThat(rotated.getRGB(0, 2)).isEqualTo(java.awt.Color.BLUE.getRGB());
    }
    @Test void preservesAspectRatioTransparencyAndOriginalBytes() throws Exception {
        var original = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "png", bytes);
        var data = bytes.toByteArray();
        var result = processor.prepare(new FileUpload("ảnh.png", "image/png", data.length, new ByteArrayInputStream(data)));
        assertThat(result.original()).isEqualTo(data);
        assertThat(result.renditions()).extracting(ProductImageProcessor.Rendered::width).containsExactly(256, 768, 1200);
        assertThat(result.renditions()).extracting(ProductImageProcessor.Rendered::height).containsExactly(128, 384, 600);
        var decoded = ImageIO.read(new ByteArrayInputStream(result.renditions().getFirst().bytes()));
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
    }
    @Test void largePhotoIsDecodedSubsampledButStillYieldsTheFullSizeRendition() throws Exception {
        var original = new BufferedImage(3200, 3000, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(original, "jpg", bytes);
        var data = bytes.toByteArray();
        var result = processor.prepare(new FileUpload("big.jpg", "image/jpeg", data.length, new ByteArrayInputStream(data)));
        assertThat(result.original()).isEqualTo(data);
        assertThat(result.renditions()).extracting(ProductImageProcessor.Rendered::width).containsExactly(256, 768, 1600);
        assertThat(result.renditions()).extracting(ProductImageProcessor.Rendered::height).containsExactly(240, 720, 1500);
    }
    @Test void subsamplingNeverGoesBelowTheLargestRendition() {
        assertThat(ProductImageProcessor.subsamplingStep(1200, 600)).isEqualTo(1);
        assertThat(ProductImageProcessor.subsamplingStep(3200, 3000)).isEqualTo(2);
        assertThat(ProductImageProcessor.subsamplingStep(5000, 5000)).isEqualTo(2);
        assertThat(ProductImageProcessor.subsamplingStep(20000, 100)).isEqualTo(1);
    }
    @Test void refusesImagesAbove25Megapixels() throws Exception {
        var huge = new BufferedImage(6000, 5000, BufferedImage.TYPE_BYTE_GRAY);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(huge, "png", bytes);
        var data = bytes.toByteArray();
        assertThatThrownBy(() -> processor.prepare(new FileUpload("huge.png", "image/png", data.length, new ByteArrayInputStream(data))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode())
                        .isEqualTo(com.stockflow.common.error.ErrorCode.PAYLOAD_TOO_LARGE));
    }
    @Test void rejectsSignatureOnlyAndMismatchedMediaType() {
        byte[] fake = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        assertThatThrownBy(() -> processor.prepare(new FileUpload("bad.png", "image/png", fake.length, new ByteArrayInputStream(fake))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> processor.prepare(new FileUpload("bad.jpg", "image/jpeg", fake.length, new ByteArrayInputStream(fake))))
                .isInstanceOf(BusinessException.class);
    }
    @Test void checksLimitBeforeReadingUpload() {
        assertThatThrownBy(() -> processor.prepare(new FileUpload("large.png", "image/png", 10 * 1024 * 1024 + 1,
                new ByteArrayInputStream(new byte[0])))).isInstanceOf(BusinessException.class);
    }
}
