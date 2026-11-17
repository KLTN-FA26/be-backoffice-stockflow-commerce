package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.json.CanonicalJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a saved layout must be (kltn-docs 12 §4.2, BR-04), and the draft rules of a customer's design (BR-03, BR-16,
 * B37).
 */
class DesignDocumentRulesTest {

    private static final String ASSET = "0190f3a1-0000-7000-8000-000000000001";
    private static final DesignDocumentRules.Frame BAG = new DesignDocumentRules.Frame("BAG-4", "BAG-M",
            List.of("FRONT", "BACK", "LEFT", "RIGHT"), Set.of(ASSET));

    private static Object parsed(String json) {
        return CanonicalJson.parse(json);
    }

    private static String layout(String surfaces) {
        return "{\"schemaVersion\":1,\"templateCode\":\"BAG-4\",\"sku\":\"BAG-M\",\"surfaces\":{" + surfaces + "}}";
    }

    private static String surface(String code, String elements) {
        return "\"" + code + "\":{\"background\":{\"kind\":\"none\"},\"elements\":[" + elements + "]}";
    }

    @Test
    void aNewDesignStartsWithOneEmptySurfacePerPrintAreaAsTheStudioDoes() {
        String empty = CanonicalJson.canonical(DesignDocumentRules.empty(BAG));

        assertThat(empty).isEqualTo("{\"schemaVersion\":1,\"sku\":\"BAG-M\",\"surfaces\":{"
                + "\"BACK\":{\"background\":{\"kind\":\"none\"},\"elements\":[]},"
                + "\"FRONT\":{\"background\":{\"kind\":\"none\"},\"elements\":[]},"
                + "\"LEFT\":{\"background\":{\"kind\":\"none\"},\"elements\":[]},"
                + "\"RIGHT\":{\"background\":{\"kind\":\"none\"},\"elements\":[]}},\"templateCode\":\"BAG-4\"}");
        assertThat(DesignDocumentRules.problem(parsed(empty), BAG)).isNull();
    }

    @Test
    void aLayoutOfThisDesignWithItsOwnImagesMayBeSaved() {
        String image = "{\"id\":\"logo\",\"type\":\"image\",\"assetId\":\"" + ASSET + "\",\"widthMm\":40}";
        String text = "{\"id\":\"name\",\"type\":\"text\",\"content\":\"Cà phê A\"}";
        String drawing = "{\"id\":\"pen\",\"type\":\"drawing\",\"strokes\":[]}";

        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", image + "," + text) + ","
                + surface("BACK", drawing))), BAG)).isNull();
        // A surface may be left out; the same element id on two surfaces is two elements.
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", text) + "," + surface("LEFT", text))),
                BAG)).isNull();
    }

    @Test
    void aLayoutOfAnotherTemplateOrVersionIsRefused() {
        assertThat(DesignDocumentRules.problem(parsed("[]"), BAG)).contains("JSON object");
        assertThat(DesignDocumentRules.problem(parsed(layout("").replace("\"schemaVersion\":1",
                "\"schemaVersion\":2")), BAG)).contains("schemaVersion");
        assertThat(DesignDocumentRules.problem(parsed(layout("").replace("\"schemaVersion\":1",
                "\"schemaVersion\":\"1\"")), BAG)).contains("schemaVersion");
        assertThat(DesignDocumentRules.problem(parsed(layout("").replace("BAG-4", "CUP-BODY")), BAG))
                .contains("template");
        assertThat(DesignDocumentRules.problem(parsed(layout("").replace("BAG-M", "BAG-L")), BAG))
                .contains("template");
        assertThat(DesignDocumentRules.problem(parsed(layout("").replace("\"surfaces\":{}", "\"surfaces\":[]")),
                BAG)).contains("surfaces");
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("BODY", ""))), BAG))
                .contains("'BODY' is not a print area");
    }

    @Test
    void aSurfaceMustHoldIdentifiedTypedElementsAndAtMostTwoHundred() {
        assertThat(DesignDocumentRules.problem(parsed(layout("\"FRONT\":{\"background\":{\"kind\":\"none\"}}")), BAG))
                .contains("elements array");
        assertThat(DesignDocumentRules.problem(parsed(layout("\"FRONT\":{\"elements\":[]}")), BAG))
                .contains("background");
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", "{\"type\":\"text\"}"))), BAG))
                .contains("needs an id");
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", "{\"id\":\"\",\"type\":\"text\"}"))),
                BAG)).contains("needs an id");
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT",
                "{\"id\":\"a\",\"type\":\"text\"},{\"id\":\"a\",\"type\":\"text\"}"))), BAG)).contains("used twice");
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", "{\"id\":\"a\",\"type\":\"video\"}"))),
                BAG)).contains("no known type");

        StringBuilder full = new StringBuilder();
        for (int i = 0; i < DesignDocumentRules.MAX_ELEMENTS_PER_SURFACE; i++) {
            full.append(i == 0 ? "" : ",").append("{\"id\":\"e").append(i).append("\",\"type\":\"text\"}");
        }
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", full.toString()))), BAG)).isNull();
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT",
                full + ",{\"id\":\"last\",\"type\":\"text\"}"))), BAG)).contains("more than 200");
    }

    @Test
    void anImageMustBeOneUploadedIntoThisDesign() {
        String other = "{\"id\":\"logo\",\"type\":\"image\",\"assetId\":\"" + UUID.randomUUID() + "\"}";
        String none = "{\"id\":\"logo\",\"type\":\"image\"}";

        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", other))), BAG))
                .contains("not uploaded into this design");
        assertThat(DesignDocumentRules.problem(parsed(layout(surface("FRONT", none))), BAG))
                .contains("not uploaded into this design");
    }

    @Test
    void aHostileNameIsCutShortInTheMessage() {
        String name = "X".repeat(39) + "😀" + "Y".repeat(5000);

        String problem = DesignDocumentRules.problem(parsed(layout(surface(name, ""))), BAG);

        assertThat(problem).hasSizeLessThan(120).contains("...");
        assertThat(problem.chars().noneMatch(c -> Character.isHighSurrogate((char) c))).isTrue();
    }

    @Test
    void onlyTheOwnerSeesADesignAndNobodyAnArchivedOne() {
        UUID owner = UUID.randomUUID();

        assertThatCode(() -> CustomerDesignRules.requireOwner(owner, owner, DesignStatus.APPROVED))
                .doesNotThrowAnyException();
        for (UUID actor : new UUID[] {UUID.randomUUID(), null}) {
            assertThatThrownBy(() -> CustomerDesignRules.requireOwner(actor, owner, DesignStatus.DRAFT))
                    .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        assertThatThrownBy(() -> CustomerDesignRules.requireOwner(owner, owner, DesignStatus.ARCHIVED))
                .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void onlyADraftChangesAndACustomerHoldsAtMostFiftyDrafts() {
        assertThatCode(() -> CustomerDesignRules.requireDraft(DesignStatus.DRAFT)).doesNotThrowAnyException();
        for (DesignStatus status : List.of(DesignStatus.PENDING_REVIEW, DesignStatus.APPROVED, DesignStatus.ARCHIVED)) {
            assertThatThrownBy(() -> CustomerDesignRules.requireDraft(status))
                    .extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.DESIGN_NOT_DRAFT);
        }

        assertThatCode(() -> CustomerDesignRules.requireRoomForDraft(49)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CustomerDesignRules.requireRoomForDraft(50))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.DESIGN_DRAFT_LIMIT_REACHED);
    }

    @Test
    void theLayoutIsCheckedOnPlainValuesSoACallerBuiltOneWorksToo() {
        Map<String, Object> built = Map.of("schemaVersion", 1, "templateCode", "BAG-4", "sku", "BAG-M",
                "surfaces", Map.of("FRONT", Map.of("background", Map.of("kind", "none"), "elements", List.of())));

        assertThat(DesignDocumentRules.problem(built, BAG)).isNull();
    }
}
