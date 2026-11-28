package com.stockflow.product.internal.repository;

import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintAreaShape;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.internal.domain.PrintConfigRules;
import com.stockflow.product.internal.entity.CustomizationTemplateJpaEntity;
import com.stockflow.product.internal.entity.PrintAreaJpaEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A stored conical cup band that carries no numbers (WF4 UX audit S08-2, BE#479): the demo seeds write
 * {@code {"type": "annular-sector"}} alone, and reading such a row used to throw "Stored JSON lacks the number
 * innerRadiusMm", which reached the storefront as a 400 on {@code GET /catalog/products/{slug}/customization}.
 */
class PrintConfigPersistenceMapperTest {

    private static final CupModel CONE = new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white");
    private static final CupModel CYLINDER = new CupModel(80, 80, 110, 3, 8, 6, 8, "kraft");

    @Test
    void aConicalCupBandStoredWithNoNumbersIsReadAsTheModelComputesIt() {
        PrintConfig config = PrintConfigPersistenceMapper.toConfig(template(CONE), UUID.randomUUID(), "LY-12OZ",
                List.of(row(Map.of("type", "annular-sector"))));

        PrintAreaShape.AnnularSector read = (PrintAreaShape.AnnularSector) config.printAreas().get(0).shape();
        // What PrintConfigRules computes when the template is written is what is read when it holds no numbers.
        PrintArea computed = PrintConfigRules
                .areas(CONE, List.of(new PrintConfigRules.AreaSpec("BODY", "Thân ly", 3, 2))).get(0);
        assertThat(read).isEqualTo(computed.shape());
        assertThat(read.innerRadiusMm()).isPositive();
        assertThat(read.outerRadiusMm()).isGreaterThan(read.innerRadiusMm());
        assertThat(read.seamMm()).isEqualTo(CONE.seamMm());
    }

    @Test
    void numbersThatAreStoredWinOverTheModel() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("type", "annular-sector");
        stored.put("innerRadiusMm", 70.5);
        stored.put("outerRadiusMm", 160.0);
        stored.put("sweepRad", 1.9);
        stored.put("seamMm", 8.0);

        PrintConfig config = PrintConfigPersistenceMapper.toConfig(template(CONE), UUID.randomUUID(), "LY-12OZ",
                List.of(row(stored)));

        assertThat(config.printAreas().get(0).shape()).isEqualTo(new PrintAreaShape.AnnularSector(70.5, 160.0, 1.9, 8.0));
    }

    @Test
    void aBandWithSomeNumbersOnlyOrNoConicalModelIsStillCorrupt() {
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("type", "annular-sector");
        partial.put("innerRadiusMm", 70.5);
        assertThatThrownBy(() -> PrintConfigPersistenceMapper.toConfig(template(CONE), UUID.randomUUID(), "LY-12OZ",
                List.of(row(partial)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outerRadiusMm");
        // A cylinder is unrolled as a rectangle: an annular sector on it was never computed, so nothing derives one.
        assertThatThrownBy(() -> PrintConfigPersistenceMapper.toConfig(template(CYLINDER), UUID.randomUUID(), "LY-12OZ",
                List.of(row(Map.of("type", "annular-sector"))))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRectangleNeedsNoModel() {
        PrintConfig config = PrintConfigPersistenceMapper.toConfig(template(CONE), UUID.randomUUID(), "LY-12OZ",
                List.of(row(Map.of("type", "rect"))));

        assertThat(config.printAreas().get(0).shape()).isInstanceOf(PrintAreaShape.Rect.class);
    }

    private static CustomizationTemplateJpaEntity template(CupModel model) {
        CustomizationTemplateJpaEntity template = new CustomizationTemplateJpaEntity(UUID.randomUUID(),
                UUID.randomUUID(), "STD");
        template.describe("Mẫu in ly giấy", 300, "CMYK", new String[]{"image/png"}, 20, 4, null,
                PrintConfigPersistenceMapper.modelParams(model));
        return template;
    }

    private static PrintAreaJpaEntity row(Map<String, Object> shape) {
        return new PrintAreaJpaEntity(UUID.randomUUID(), UUID.randomUUID(), "BODY", "Thân ly", BigDecimal.TEN,
                BigDecimal.TEN, new BigDecimal("200"), new BigDecimal("80"), new BigDecimal("3"), new BigDecimal("2"),
                shape, Map.of("meshPart", "body"), 0);
    }
}
