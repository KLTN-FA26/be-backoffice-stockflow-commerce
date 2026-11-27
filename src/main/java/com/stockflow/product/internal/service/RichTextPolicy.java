package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.storage.FileCategory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one place a description written in the back office's rich-text editor (Tiptap: StarterKit and Image) becomes
 * HTML the system stores and the storefront shows (ADR-0013).
 *
 * <p><b>Why on the server.</b> The editor and the storefront's DOMPurify are conveniences, not a defence: anyone with
 * the permission can send the API any HTML, and the storefront shows it to every customer. So the HTML is cleaned to
 * a fixed allow-list here, before it is stored, and what is stored is what is served.</p>
 *
 * <p>The allow-list is what StarterKit and Image produce, nothing more: paragraphs, headings 1-3, bold, italic,
 * underline, strike, inline code and code blocks, quotes, lists, rules, line breaks, links and images. No attribute
 * but a link's {@code href}/{@code title} and an image's {@code src}/{@code alt}/{@code title}; no {@code style},
 * {@code class}, event handler or {@code script}. A link goes to {@code http}, {@code https} or {@code mailto} only,
 * and is marked {@code nofollow noopener noreferrer}. An image must be one of ours: an object under the CDN's
 * {@code product-images/} prefix ({@code ProductMediaProperties}); any other {@code <img>} is dropped, so a description
 * cannot load a tracking pixel or another site's picture into a customer's browser.</p>
 */
@Component
public class RichTextPolicy {

    /** What a description may weigh, as written: about forty pages of text, far above any real description. */
    public static final int MAX_LENGTH = 100_000;

    private static final Safelist ALLOWED = Safelist.none()
            .addTags("p", "br", "h1", "h2", "h3", "strong", "b", "em", "i", "u", "s", "code", "pre", "blockquote",
                    "ul", "ol", "li", "hr", "a", "img")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
            .addAttributes("img", "src", "alt", "title")
            .addProtocols("img", "src", "https");

    private final ProductMediaProperties delivery;

    public RichTextPolicy(ProductMediaProperties delivery) {
        this.delivery = delivery;
    }

    /**
     * {@code html} cleaned to the allow-list, or null when nothing readable is left (an empty editor sends
     * {@code <p></p>}).
     *
     * @param field the request field, named in the refusal of a description too long to store
     * @throws BusinessException {@code VALIDATION_FAILED} when {@code html} is longer than {@link #MAX_LENGTH}
     */
    public String clean(String html, String field) {
        if (html == null || html.isBlank()) {
            return null;
        }
        if (html.length() > MAX_LENGTH) {
            String message = "%s must be at most %d characters".formatted(field, MAX_LENGTH);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                    List.of(new FieldError(field, message, "Size")));
        }
        Document dirty = Jsoup.parseBodyFragment(html);
        Document clean = new Cleaner(ALLOWED).clean(dirty);
        for (Element image : clean.select("img")) {
            if (!isOurImage(image.attr("src"))) {
                image.remove();
            }
        }
        clean.outputSettings().prettyPrint(false);
        boolean readable = !clean.body().text().isBlank() || !clean.body().select("img, hr").isEmpty();
        return readable ? clean.body().html() : null;
    }

    /** The text of a stored description, tags gone: what search indexes and a plain preview shows. */
    public static String text(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = Jsoup.parseBodyFragment(html).body().text().strip();
        return text.isEmpty() ? null : text;
    }

    private boolean isOurImage(String src) {
        String base = delivery.cloudFrontBaseUrl();
        return !base.isEmpty() && src.startsWith(base + "/" + FileCategory.PRODUCT_IMAGE.prefix() + "/")
                && !src.contains("..");
    }
}
