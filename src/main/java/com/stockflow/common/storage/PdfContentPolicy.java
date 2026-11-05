package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a PDF that can do something when it is opened - the thing a malware scanner misses, because nothing in it
 * has to be a known signature. The people who open these files are the print shop (design files) and the accountants
 * (invoice scans), on their own machines.
 *
 * <h2>What is refused</h2>
 *
 * <ul>
 *   <li>any action that is not plainly navigational. An action - wherever it is: the document's open action and
 *       additional actions, every page's, annotation's, form field's and bookmark's action and additional actions, and
 *       every action chained after one of those through {@code /Next} - may only go to a place in this document
 *       ({@code GoTo}, {@code GoToDp}), turn a page ({@code Named} NextPage, PrevPage, FirstPage, LastPage), show or
 *       hide a layer or an annotation, follow an article of this document, or clear a form. Everything else is
 *       refused, including the actions nobody has thought of yet: JavaScript, Launch, GoToR and GoToE (open another
 *       file - on Windows a UNC path hands the reader's NTLM hash to that host), SubmitForm and ImportData (send or
 *       load data), media, the reader's own menu items. An allowed action that names a file ({@code /F}, as a
 *       {@code Thread} may) is refused like GoToR;</li>
 *   <li>a web link ({@code URI}) that runs by itself - from the open action or an additional action other than a
 *       mouse press or release - and one to anything but {@code http}, {@code https} or {@code mailto}. A web link the
 *       reader clicks passes: an invoice may link to its e-invoice lookup page;</li>
 *   <li>a chain of actions deeper than any real document builds: refused, not cut short, as what follows the cut is
 *       what would run;</li>
 *   <li>RichMedia/Screen/Movie/Sound/3D annotations, XFA forms;</li>
 *   <li>a PDF that needs a password to open: it cannot be inspected, and a print shop cannot print it;</li>
 *   <li>for design files only, attached files - in the names tree or as file-attachment annotations - and any
 *       encryption at all: a print file carries the artwork and nothing else. An invoice may legitimately attach its
 *       e-invoice XML and come permission-protected, so those pass; no allowed action can open them.</li>
 * </ul>
 *
 * <p>Actions are read at the level of the PDF's own dictionaries ({@code /S /JavaScript}), not through PDFBox's typed
 * wrappers, so a trigger the wrappers do not name is still seen. Chains are followed with a visited set per kind of
 * trigger: a PDF whose {@code /Next} points back at itself is read, not looped over, and an action reached first from a
 * click is still checked when an automatic trigger reaches it too. A PDF PDFBox cannot make sense of is refused as
 * unreadable rather than failing the request.</p>
 *
 * <p>The PDF is not rewritten: rasterising it would cost a print file its vectors and its colour. It is read from disk
 * with temp-file caching, so a large or hostile file does not land in the heap.</p>
 */
final class PdfContentPolicy {

    private static final Set<String> ACTIVE_ANNOTATIONS = Set.of("RichMedia", "Screen", "Movie", "Sound", "3D");
    /** Actions that move the reader around this document, or clear its form, and do nothing else. */
    private static final Set<COSName> NAVIGATION = Set.of(COSName.getPDFName("GoTo"), COSName.getPDFName("GoToDp"),
            COSName.getPDFName("Hide"), COSName.getPDFName("SetOCGState"), COSName.getPDFName("Thread"),
            COSName.getPDFName("Trans"), COSName.getPDFName("ResetForm"));
    private static final COSName NAMED = COSName.getPDFName("Named");
    /** The named actions every reader knows (ISO 32000-1 §12.6.4.11); the rest are a reader's own menu items. */
    private static final Set<String> PAGE_TURNS = Set.of("NextPage", "PrevPage", "FirstPage", "LastPage");
    /** Additional-action triggers that are the reader pressing or releasing the mouse on an annotation. */
    private static final Set<COSName> MOUSE = Set.of(COSName.D, COSName.getPDFName("U"));
    private static final COSName JAVASCRIPT = COSName.getPDFName("JavaScript");
    private static final COSName LAUNCH = COSName.getPDFName("Launch");
    private static final COSName URI = COSName.getPDFName("URI");
    private static final COSName NEXT = COSName.getPDFName("Next");
    private static final COSName OPEN_ACTION = COSName.getPDFName("OpenAction");
    private static final COSName OUTLINES = COSName.getPDFName("Outlines");
    private static final COSName FIRST = COSName.getPDFName("First");
    /** Deeper than any chain a real document builds; a deeper one is refused. */
    private static final int MAX_CHAIN = 64;
    /** More bookmarks than any real document has; past this the outline is refused rather than walked for ever. */
    private static final int MAX_BOOKMARKS = 50_000;

    /** How an action runs: by itself, or when the reader clicks. Only a click may follow a web link. */
    private enum Trigger { AUTOMATIC, CLICK }

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
            Actions actions = new Actions();
            COSDictionary root = catalog.getCOSObject();
            actions.requireInert(root.getDictionaryObject(OPEN_ACTION), Trigger.AUTOMATIC);
            actions.requireInertTriggers(root);
            actions.requireInertBookmarks(root.getDictionaryObject(OUTLINES));
            if (catalog.getAcroForm() != null) {
                if (catalog.getAcroForm().hasXFA()) {
                    refuse("The PDF is an XFA form");
                }
                for (PDField field : catalog.getAcroForm().getFieldTree()) {
                    actions.requireInertTriggers(field.getCOSObject());
                }
            }
            for (PDPage page : document.getPages()) {
                actions.requireInertTriggers(page.getCOSObject());
                for (PDAnnotation annotation : page.getAnnotations()) {
                    String subtype = annotation.getSubtype();
                    if (subtype != null && ACTIVE_ANNOTATIONS.contains(subtype)) {
                        refuse("The PDF carries embedded media");
                    }
                    if (printFile && "FileAttachment".equals(subtype)) {
                        refuse("A print file must not carry attached files");
                    }
                    actions.requireInertTriggers(annotation.getCOSObject());
                }
            }
        } catch (BusinessException refused) {
            throw refused;
        } catch (InvalidPasswordException locked) {
            refuse("The PDF needs a password to open and cannot be inspected");
        } catch (IOException | RuntimeException | StackOverflowError unreadable) {
            // A malformed file is the client's problem, not a server fault: whatever PDFBox makes of it, it is refused.
            refuse("The PDF cannot be read");
        }
    }

    /** One walk over a document's actions, remembering what each kind of trigger has already reached. */
    private static final class Actions {

        private final Map<Trigger, Set<COSBase>> seen = new EnumMap<>(Trigger.class);

        Actions() {
            for (Trigger trigger : Trigger.values()) {
                seen.put(trigger, Collections.newSetFromMap(new IdentityHashMap<>()));
            }
        }

        /**
         * The action of {@code owner} ({@code /A}, a click) and its additional actions ({@code /AA}): a mouse press or
         * release is a click, everything else - opening, closing, focus, a keystroke, a calculation - runs by itself.
         */
        void requireInertTriggers(COSDictionary owner) {
            requireInert(owner.getDictionaryObject(COSName.A), Trigger.CLICK);
            if (owner.getDictionaryObject(COSName.AA) instanceof COSDictionary triggers) {
                for (COSName trigger : triggers.keySet()) {
                    requireInert(triggers.getDictionaryObject(trigger),
                            MOUSE.contains(trigger) ? Trigger.CLICK : Trigger.AUTOMATIC);
                }
            }
        }

        /** Every bookmark's action: the outline is a tree of {@code /First} children and {@code /Next} siblings. */
        void requireInertBookmarks(COSBase outlines) {
            Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<COSBase> pending = new ArrayDeque<>();
            if (outlines != null) {
                pending.push(outlines);
            }
            while (!pending.isEmpty()) {
                COSBase value = resolve(pending.pop());
                if (!(value instanceof COSDictionary item) || !visited.add(item)) {
                    continue;
                }
                if (visited.size() > MAX_BOOKMARKS) {
                    refuse("The PDF's bookmarks cannot be read");
                }
                requireInert(item.getDictionaryObject(COSName.A), Trigger.CLICK);
                COSBase child = item.getItem(FIRST);
                COSBase sibling = item.getItem(NEXT);
                if (child != null) {
                    pending.push(child);
                }
                if (sibling != null) {
                    pending.push(sibling);
                }
            }
        }

        /** An action dictionary - or an array of them - and everything chained after it through {@code /Next}. */
        void requireInert(COSBase action, Trigger trigger) {
            requireInert(action, trigger, 0);
        }

        private void requireInert(COSBase action, Trigger trigger, int depth) {
            COSBase value = resolve(action);
            if (value == null || value instanceof COSNull) {
                return;
            }
            if (depth > MAX_CHAIN) {
                refuse("The PDF chains more actions than a document has");
            }
            if (!seen.get(trigger).add(value)) {
                return;
            }
            if (value instanceof COSArray actions) {
                for (int i = 0; i < actions.size(); i++) {
                    requireInert(actions.get(i), trigger, depth + 1);
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
                if (dictionary.containsKey(COSName.F)) {
                    refuse("The PDF carries an action that opens another file");
                }
                boolean allowed = type == null || NAVIGATION.contains(type)
                        || (NAMED.equals(type) && PAGE_TURNS.contains(dictionary.getNameAsString(COSName.N)))
                        || (URI.equals(type) && trigger == Trigger.CLICK && isWebLink(dictionary.getString(URI)));
                if (!allowed) {
                    refuse("The PDF carries " + describe(type) + " action, which may open, send or run something");
                }
                requireInert(dictionary.getDictionaryObject(NEXT), trigger, depth + 1);
            }
        }

        /** {@code http}, {@code https} or {@code mailto}: not {@code file:}, a UNC path or a script. */
        private static boolean isWebLink(String uri) {
            if (uri == null) {
                return false;
            }
            String target = uri.strip().toLowerCase(java.util.Locale.ROOT);
            return target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:");
        }

        /** The action's type for the refusal message: the file chooses the name, so only a plain one is repeated. */
        private static String describe(COSName type) {
            String name = type.getName();
            return name.matches("[A-Za-z0-9]{1,32}") ? "a " + name : "an unknown";
        }

        private static COSBase resolve(COSBase value) {
            return value instanceof COSObject indirect ? indirect.getObject() : value;
        }
    }

    private static void refuse(String reason) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
    }
}
