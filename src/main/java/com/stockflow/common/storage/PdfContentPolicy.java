package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Refuses a PDF that can do something when it is opened - the thing a malware scanner misses, because nothing in it
 * has to be a known signature. The people who open these files are the print shop (design files) and the accountants
 * (invoice scans), on their own machines.
 *
 * <h2>What is refused</h2>
 *
 * <ul>
 *   <li>JavaScript anywhere: the document's named scripts, its open action and additional actions, a page's actions,
 *       a link's or a form field's action;</li>
 *   <li>a Launch action (open a file or a program), RichMedia/Screen/Movie annotations, XFA forms;</li>
 *   <li>a PDF that needs a password to open: it cannot be inspected, and a print shop cannot print it;</li>
 *   <li>for design files only, attached files and any encryption at all: a print file carries the artwork and nothing
 *       else. An invoice may legitimately attach its e-invoice XML and come permission-protected, so those pass.</li>
 * </ul>
 *
 * <p>The PDF is not rewritten: rasterising it would cost a print file its vectors and its colour. It is read from
 * disk with temp-file caching, so a large or hostile file does not land in the heap.</p>
 */
final class PdfContentPolicy {

    private static final Set<String> ACTIVE_ANNOTATIONS = Set.of("RichMedia", "Screen", "Movie", "3D");

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
            if (catalog.getOpenAction() instanceof PDAction open) {
                requireInert(open);
            }
            var documentActions = catalog.getActions();
            if (documentActions != null) {
                for (PDAction action : new PDAction[] {documentActions.getWC(), documentActions.getWS(),
                        documentActions.getDS(), documentActions.getWP(), documentActions.getDP()}) {
                    requireInert(action);
                }
            }
            if (catalog.getAcroForm() != null && catalog.getAcroForm().hasXFA()) {
                refuse("The PDF is an XFA form");
            }
            for (PDPage page : document.getPages()) {
                var pageActions = page.getActions();
                if (pageActions != null) {
                    requireInert(pageActions.getO());
                    requireInert(pageActions.getC());
                }
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (ACTIVE_ANNOTATIONS.contains(annotation.getSubtype())) {
                        refuse("The PDF carries embedded media");
                    }
                    if (annotation instanceof PDAnnotationLink link) {
                        requireInert(link.getAction());
                    } else if (annotation instanceof PDAnnotationWidget widget) {
                        requireInert(widget.getAction());
                        var widgetActions = widget.getActions();
                        if (widgetActions != null) {
                            for (PDAction action : new PDAction[] {widgetActions.getE(), widgetActions.getX(),
                                    widgetActions.getD(), widgetActions.getU(), widgetActions.getFo(),
                                    widgetActions.getBl(), widgetActions.getPO(), widgetActions.getPC(),
                                    widgetActions.getPV(), widgetActions.getPI()}) {
                                requireInert(action);
                            }
                        }
                    }
                }
            }
        } catch (InvalidPasswordException locked) {
            refuse("The PDF needs a password to open and cannot be inspected");
        } catch (IOException unreadable) {
            refuse("The PDF cannot be read");
        }
    }

    private static void requireInert(PDAction action) {
        if (action instanceof PDActionJavaScript) {
            refuse("The PDF carries JavaScript");
        }
        if (action instanceof PDActionLaunch) {
            refuse("The PDF launches another file or program");
        }
    }

    private static void refuse(String reason) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
    }
}
