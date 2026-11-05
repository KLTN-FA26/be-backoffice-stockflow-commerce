package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/** PDFs that can do something when opened are refused; ordinary ones pass. */
class PdfContentPolicyTest {

    @TempDir Path directory;

    private Path pdf(String name, Consumer<PDDocument> change) throws Exception {
        Path file = directory.resolve(name);
        try (var document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            change.accept(document);
            document.save(file.toFile());
        }
        return file;
    }

    private static void refused(FileCategory category, Path file, String reason) {
        assertThatThrownBy(() -> PdfContentPolicy.check(category, "application/pdf", file))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).contains(reason);
                });
    }

    @Test
    void anOrdinaryPdfPassesAndOtherTypesAreNotOpened() throws Exception {
        Path plain = pdf("plain.pdf", document -> { });
        assertThatCode(() -> PdfContentPolicy.check(FileCategory.DESIGN_RENDER, "application/pdf", plain))
                .doesNotThrowAnyException();
        Path notAPdf = Files.writeString(directory.resolve("x.png"), "not parsed");
        assertThatCode(() -> PdfContentPolicy.check(FileCategory.DESIGN_RENDER, "image/png", notAPdf))
                .doesNotThrowAnyException();
    }

    @Test
    void javaScriptAnywhereIsRefused() throws Exception {
        refused(FileCategory.SUPPLIER_INVOICE, pdf("open.pdf", document -> document.getDocumentCatalog()
                .setOpenAction(new PDActionJavaScript("app.alert('x')"))), "JavaScript");
        refused(FileCategory.SUPPLIER_INVOICE, pdf("named.pdf", document -> {
            var scripts = new PDJavascriptNameTreeNode();
            scripts.setNames(Map.of("init", new PDActionJavaScript("app.alert('x')")));
            var names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setJavascript(scripts);
            document.getDocumentCatalog().setNames(names);
        }), "JavaScript");
        refused(FileCategory.DESIGN_RENDER, pdf("link.pdf", document -> {
            var link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(10, 10, 50, 50));
            link.setAction(new PDActionJavaScript("app.launchURL('http://evil')"));
            try {
                document.getPage(0).getAnnotations().add(link);
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        }), "JavaScript");
    }

    @Test
    void javaScriptChainedAfterAnInnocentActionIsRefused() throws Exception {
        refused(FileCategory.SUPPLIER_INVOICE, pdf("chained.pdf", document -> {
            var go = new org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo();
            var destination = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination();
            destination.setPage(document.getPage(0));
            go.setDestination(destination);
            var script = new PDActionJavaScript("app.alert('chained')");
            go.setNext(java.util.List.of(script));
            document.getDocumentCatalog().setOpenAction(go);
        }), "JavaScript");
    }

    @Test
    void aSelfReferencingChainIsReadNotLoopedOver() throws Exception {
        Path loop = pdf("loop.pdf", document -> {
            var go = new org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo();
            var destination = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination();
            destination.setPage(document.getPage(0));
            go.setDestination(destination);
            go.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("Next"), go.getCOSObject());
            document.getDocumentCatalog().setOpenAction(go);
        });
        assertThatCode(() -> PdfContentPolicy.check(FileCategory.SUPPLIER_INVOICE, "application/pdf", loop))
                .doesNotThrowAnyException();
    }

    @Test
    void aFormFieldsCalculateScriptIsRefused() throws Exception {
        refused(FileCategory.SUPPLIER_INVOICE, pdf("field.pdf", document -> {
            var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(form);
            var field = new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form);
            field.setPartialName("total");
            var triggers = new org.apache.pdfbox.cos.COSDictionary();
            triggers.setItem(org.apache.pdfbox.cos.COSName.getPDFName("C"),
                    new PDActionJavaScript("event.value = 1").getCOSObject());
            field.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.AA, triggers);
            form.getFields().add(field);
        }), "JavaScript");
    }

    @Test
    void aFileAttachmentAnnotationIsRefusedInAPrintFile() throws Exception {
        Path attachment = pdf("annotation.pdf", document -> {
            var annotation = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment();
            annotation.setRectangle(new PDRectangle(10, 10, 20, 20));
            try {
                document.getPage(0).getAnnotations().add(annotation);
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        });
        refused(FileCategory.DESIGN_RENDER, attachment, "attached files");
        assertThatCode(() -> PdfContentPolicy.check(FileCategory.SUPPLIER_INVOICE, "application/pdf", attachment))
                .doesNotThrowAnyException();
    }

    @Test
    void aLaunchActionIsRefused() throws Exception {
        refused(FileCategory.DOCUMENT, pdf("launch.pdf", document -> {
            var launch = new PDActionLaunch();
            launch.setFile(new org.apache.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification(
                    new org.apache.pdfbox.cos.COSString("cmd.exe")));
            document.getDocumentCatalog().setOpenAction(launch);
        }), "launches");
    }

    @Test
    void anAttachedFileIsRefusedInAPrintFileButNotInAnInvoice() throws Exception {
        Path attached = pdf("attached.pdf", document -> {
            try {
                var spec = new PDComplexFileSpecification();
                spec.setFile("einvoice.xml");
                spec.setEmbeddedFile(new PDEmbeddedFile(document, new ByteArrayInputStream("<hd/>".getBytes())));
                var files = new PDEmbeddedFilesNameTreeNode();
                files.setNames(Map.of("einvoice.xml", spec));
                var names = new PDDocumentNameDictionary(document.getDocumentCatalog());
                names.setEmbeddedFiles(files);
                document.getDocumentCatalog().setNames(names);
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        });
        refused(FileCategory.DESIGN_RENDER, attached, "attached files");
        assertThatCode(() -> PdfContentPolicy.check(FileCategory.SUPPLIER_INVOICE, "application/pdf", attached))
                .doesNotThrowAnyException();
    }

    @Test
    void encryptionIsRefusedInAPrintFileAndAPasswordEverywhere() throws Exception {
        Path permissionsOnly = pdf("protected.pdf", document -> protect(document, ""));
        refused(FileCategory.DESIGN_RENDER, permissionsOnly, "encrypted");
        assertThatCode(() -> PdfContentPolicy.check(FileCategory.SUPPLIER_INVOICE, "application/pdf", permissionsOnly))
                .doesNotThrowAnyException();
        refused(FileCategory.SUPPLIER_INVOICE, pdf("locked.pdf", document -> protect(document, "secret")), "password");
    }

    @Test
    void anUnreadablePdfIsRefused() throws Exception {
        Path broken = Files.write(directory.resolve("broken.pdf"), "%PDF-1.7\nnot really".getBytes());
        refused(FileCategory.SUPPLIER_INVOICE, broken, "cannot be read");
    }

    private static void protect(PDDocument document, String userPassword) {
        try {
            var policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
