package com.stockflow.common.document;

import com.stockflow.common.config.SellerProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * The seller's letterhead ({@code stockflow.seller.*}) ready to print, its logo read once.
 *
 * <p>The logo is read at startup, not at the first print: a path mistyped in a deployment's environment should stop
 * the deployment, in front of whoever made it, rather than turn every quotation into a 500 a customer sees. A blank
 * location is not a mistake - the head is then drawn without a logo.</p>
 */
@Component
public class SellerLetterhead {

    /** A logo is a few kilobytes; anything near this is a photograph put there by mistake, and goes into every PDF. */
    private static final int LOGO_MAX_BYTES = 512 * 1024;

    private final SellerProperties seller;
    private final byte[] logo;

    public SellerLetterhead(SellerProperties seller, ResourceLoader resources) {
        this.seller = seller;
        this.logo = blank(seller.logo()) ? null : read(resources.getResource(seller.logo().strip()), seller.logo());
    }

    public Letterhead get() {
        return new Letterhead(seller.name(), seller.taxCode(), seller.address(), clean(seller.phone()),
                clean(seller.email()), clean(seller.website()), clean(seller.bankAccountNumber()),
                clean(seller.bankName()), clean(seller.bankBranch()), clean(seller.city()), logo);
    }

    /**
     * Who signs the company's papers, and as what ("Giám đốc"): the name and title a signature block is headed by.
     * Beside the letterhead rather than in it - a letterhead is the company, this is a person, and a paper signed by
     * someone else (an accountant's voucher) takes the letterhead without them.
     */
    public String representative() {
        return clean(seller.representative());
    }

    public String representativeTitle() {
        return clean(seller.representativeTitle());
    }

    private static byte[] read(Resource resource, String location) {
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readNBytes(LOGO_MAX_BYTES + 1);
            if (bytes.length > LOGO_MAX_BYTES) {
                throw new IllegalStateException("stockflow.seller.logo (" + location + ") is larger than "
                        + LOGO_MAX_BYTES / 1024 + " KB");
            }
            // The PNG signature: PDFBox would otherwise fail on a JPEG or an SVG at the first quotation, not here.
            if (bytes.length < 8 || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N'
                    || bytes[3] != 'G') {
                throw new IllegalStateException("stockflow.seller.logo (" + location + ") is not a PNG image");
            }
            return bytes;
        } catch (IOException ex) {
            throw new IllegalStateException("stockflow.seller.logo (" + location + ") cannot be read", ex);
        }
    }

    private static String clean(String value) {
        return blank(value) ? null : value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
