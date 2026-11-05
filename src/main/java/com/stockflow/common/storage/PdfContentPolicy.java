package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Refuses a PDF that can do something when it is opened - the thing a malware scanner misses, because nothing in it
 * has to be a known signature. The people who open these files are the print shop (design files) and the accountants
 * (invoice scans), on their own machines.
 *
 * <h2>What is refused</h2>
 *
 * <ul>
 *   <li>JavaScript anywhere an action can be: the document's named scripts, its open action and additional actions,
 *       every page's, annotation's and form field's action and additional actions (keystroke, format, validate and
 *       calculate scripts included), and every action chained after one of those through {@code /Next};</li>
 *   <li>a Launch action (open a file or a program), RichMedia/Screen/Movie/3D annotations, XFA forms;</li>
 *   <li>a PDF that needs a password to open: it cannot be inspected, and a print shop cannot print it;</li>
 *   <li>for design files only, attached files - in the names tree or as file-attachment annotations - and any
 *       encryption at all: a print file carries the artwork and nothing else. An invoice may legitimately attach its
 *       e-invoice XML and come permission-protected, so those pass.</li>
 * </ul>
 *
 * <p>Actions are read at the level of the PDF's own dictionaries ({@code /S /JavaScript}), not through PDFBox's typed
 * wrappers, so a trigger the wrappers do not name is still seen. Chains are followed with a visited set: a PDF whose
 * {@code /Next} points back at itself is read, not looped over.</p>
 *
 * <p>The PDF is not rewritten: rasterising it would cost a print file its vectors and its colour. It is read from disk
 * with temp-file caching, so a large or hostile file does not land in the heap.</p>
 */
final class PdfContentPolicy {

    private static final Set<String> ACTIVE_ANNOTATIONS = Set.of("RichMedia", "Screen", "Movie", "3D");
    private static final COSName JAVASCRIPT = COSName.getPDFName("JavaScript");
    private static final COSName LAUNCH = COSName.getPDFName("Launch");
    private static final COSName NEXT = COSName.getPDFName("Next");
    private static final COSName OPEN_ACTION = COSName.getPDFName("OpenAction");
    /** Deeper than any chain a real document builds; a hostile one is cut here rather than walked for ever. */
    private static final int MAX_CHAIN = 64;

    private PdfContentPolicy() {
    }

    static void check(FileCategory category, String contentType, Path file) {
        if (!"application/pdf".equals(ContentTypePolicy.normalise(contentType))) {
            return;
        }
        boolean printFile = category == FileCategory.DESIGN_RENDER;
        try (PDDocument document = Loader.loadPDF(file.toFile(), "", null, null,
                IOUtils.createTempFileOnlyStreamCache())) {
            if (printFile && document.isEncrypted()) {
                refuse("A print file must not be encrypted");
            }
            var catalog = document.getDocumentCatalog();
            var names = catalog.getNames();
            if (names != null && names.getJavaScript() != null) {
                refuse("The PDF carries JavaScript");
            }
            if (printFile && names != null && names.getEmbeddedFiles() != null) {
                refuse("A print file must not carry attached files");
            }
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            COSDictionary root = catalog.getCOSObject();
            requireInert(root.getDictionaryObject(OPEN_ACTION), seen, 0);
            requireInertTriggers(root, seen);
            if (catalog.getAcroForm() != null) {
                if (catalog.getAcroForm().hasXFA()) {
                    refuse("The PDF is an XFA form");
                }
                for (PDField field : catalog.getAcroForm().getFieldTree()) {
                    requireInertTriggers(field.getCOSObject(), seen);
                }
            }
            for (PDPage page : document.getPages()) {
                requireInertTriggers(page.getCOSObject(), seen);
                for (PDAnnotation annotation : page.getAnnotations()) {
                    String subtype = annotation.getSubtype();
                    if (ACTIVE_ANNOTATIONS.contains(subtype)) {
                        refuse("The PDF carries embedded media");
                    }
                    if (printFile && "FileAttachment".equals(subtype)) {
                        refuse("A print file must not carry attached files");
                    }
                    requireInertTriggers(annotation.getCOSObject(), seen);
                }
            }
        } catch (InvalidPasswordException locked) {
            refuse("The PDF needs a password to open and cannot be inspected");
        } catch (IOException unreadable) {
            refuse("The PDF cannot be read");
        }
    }

    /** The action of {@code owner} ({@code /A}) and every action of its additional-actions dictionary ({@code /AA}). */
    private static void requireInertTriggers(COSDictionary owner, Set<COSBase> seen) {
        requireInert(owner.getDictionaryObject(COSName.A), seen, 0);
        if (owner.getDictionaryObject(COSName.AA) instanceof COSDictionary triggers) {
            for (COSName trigger : triggers.keySet()) {
                requireInert(triggers.getDictionaryObject(trigger), seen, 0);
            }
        }
    }

    /** An action dictionary - or an array of them - and everything chained after it through {@code /Next}. */
    private static void requireInert(COSBase action, Set<COSBase> seen, int depth) {
        COSBase value = action instanceof COSObject indirect ? indirect.getObject() : action;
        if (value == null || depth > MAX_CHAIN || !seen.add(value)) {
            return;
        }
        if (value instanceof COSArray actions) {
            for (int i = 0; i < actions.size(); i++) {
                requireInert(actions.getObject(i), seen, depth + 1);
            }
            return;
        }
        if (value instanceof COSDictionary dictionary) {
            COSName type = dictionary.getCOSName(COSName.S);
            if (JAVASCRIPT.equals(type) || dictionary.containsKey(COSName.JS)) {
                refuse("The PDF carries JavaScript");
            }
            if (LAUNCH.equals(type)) {
                refuse("The PDF launches another file or program");
            }
            requireInert(dictionary.getDictionaryObject(NEXT), seen, depth + 1);
        }
    }

    private static void refuse(String reason) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
    }
}
