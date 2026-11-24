package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The reader of the XML an accountant hands in from the provider's portal. The file is uploaded by a person, so besides
 * reading a real invoice it must finish quickly on a file built to make its patterns backtrack (CodeQL
 * java/polynomial-redos on the PR that introduced it).
 */
@DisplayName("PortalInvoiceXml: reading an invoice XML handed in from the portal")
class PortalInvoiceXmlTest {

    private static final String INVOICE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <HDon><DLHDon Id="data"><TTChung>
              <KHMSHDon> 1 </KHMSHDon><KHHDon>C26TAA</KHHDon><SHDon>42</SHDon><NLap>2026-11-23</NLap>
            </TTChung><NDHDon>
              <NBan Id="seller"><Ten>Công ty demo</Ten><MST>0312345678</MST></NBan>
              <NMua><Ten>Khách</Ten><MST>0109999999</MST></NMua>
            </NDHDon></DLHDon><MCCQT Id="id1">00ABCDEF</MCCQT>
            <DSCKS><NBan><Signature><Object><SignatureProperties><SignatureProperty>
              <SigningTime>2026-11-23T10:05:00</SigningTime>
            </SignatureProperty></SignatureProperties></Object></Signature></NBan></DSCKS></HDon>
            """;

    @Test
    void readsTheInvoiceFieldsTrimmedAndTheSellersTaxCodeNotTheBuyers() {
        PortalInvoiceXml xml = PortalInvoiceXml.read(INVOICE.getBytes(StandardCharsets.UTF_8));

        assertThat(xml.templateCode()).isEqualTo("1");
        assertThat(xml.symbol()).isEqualTo("C26TAA");
        assertThat(xml.number()).isEqualTo(42);
        assertThat(xml.issueDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(xml.sellerTaxCode()).isEqualTo("0312345678");
        assertThat(xml.taxAuthorityCode()).isEqualTo("00ABCDEF");
        assertThat(xml.signedAt()).isEqualTo(Instant.parse("2026-11-23T03:05:00Z"));
    }

    @Test
    void aFileOfManyUnclosedTagsIsRefusedQuicklyRatherThanScannedOncePerTag() {
        byte[] hostile = ("<KHMSHDon>1</KHMSHDon><KHHDon>C26TAA</KHHDon><SHDon>1</SHDon>"
                + "<NBan a=\"".repeat(30_000) + "<KHMSHDon x=\"".repeat(30_000)).getBytes(StandardCharsets.UTF_8);

        PortalInvoiceXml xml = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> PortalInvoiceXml.read(hostile));
        assertThat(xml.sellerTaxCode()).isNull();

        byte[] noNumber = "<SHDon x=\"".repeat(60_000).getBytes(StandardCharsets.UTF_8);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> PortalInvoiceXml.read(noNumber)).isInstanceOf(BusinessException.class));
    }
}
