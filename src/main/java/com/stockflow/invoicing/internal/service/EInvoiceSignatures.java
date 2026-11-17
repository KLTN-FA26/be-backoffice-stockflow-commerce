package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StorageException;
import com.stockflow.invoicing.internal.domain.EInvoice;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Who signed an e-invoice and whether the signature holds ({@link InvoiceXmlSignature}): for an issued invoice, from
 * the XML the system keeps; and for any XML an accountant hands in - a buyer's copy, or one changed on purpose to show
 * that a changed invoice no longer passes.
 *
 * <p>Read on demand, not stored: the XML is the invoice in law and is kept unchangeable, so checking it again always
 * answers the same, and nothing about the answer needs a column.</p>
 */
@Service
public class EInvoiceSignatures {

    private final EInvoiceQueryService queries;
    private final FileStorage storage;

    EInvoiceSignatures(EInvoiceQueryService queries, FileStorage storage) {
        this.queries = queries;
        this.storage = storage;
    }

    /**
     * The signature on an issued invoice's XML.
     *
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code EINVOICE_NOT_ISSUED} for one that has no XML yet
     */
    public InvoiceXmlSignature.Check ofInvoice(UUID id) {
        EInvoice.Snapshot invoice = queries.get(id);
        if (!invoice.status().wasIssued()) {
            throw new BusinessException(ErrorCode.EINVOICE_NOT_ISSUED,
                    "Invoice %s is %s and has no XML".formatted(invoice.code(), invoice.status()));
        }
        try (InputStream xml = storage.download(invoice.xmlFileKey())) {
            return InvoiceXmlSignature.check(xml.readNBytes(InvoiceXmlSignature.MAX_BYTES + 1));
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the invoice's XML", unreadable);
        }
    }

    /**
     * The signature on an XML handed in.
     *
     * @throws BusinessException 413 {@code PAYLOAD_TOO_LARGE} above {@link InvoiceXmlSignature#MAX_BYTES}
     */
    public InvoiceXmlSignature.Check ofXml(InputStream xml) {
        byte[] bytes;
        try {
            bytes = xml.readNBytes(InvoiceXmlSignature.MAX_BYTES + 1);
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the uploaded XML", unreadable);
        }
        if (bytes.length > InvoiceXmlSignature.MAX_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "An invoice's XML is checked up to %d MB".formatted(InvoiceXmlSignature.MAX_BYTES / 1024 / 1024));
        }
        return InvoiceXmlSignature.check(bytes);
    }
}
