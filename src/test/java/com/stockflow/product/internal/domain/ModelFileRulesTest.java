package com.stockflow.product.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.BagModel;
import com.stockflow.product.api.CupModel;
import com.stockflow.product.api.TemplateModel3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which template values only a new {@code .glb} may change, and what makes the mesh map of a template with a file
 * complete (project owner, 2026-11-15).
 */
class ModelFileRulesTest {

    private static final CupModel CUP = new CupModel(90, 60, 110, 3, 8, 6, 8, "paper-white");
    private static final BagModel BAG = new BagModel(260, 120, 320, 40, "twisted", "kraft-brown");
    private static final List<TemplateModel3d.Surface> BODY = List.of(surface("BODY", "print:BODY"));

    private static TemplateModel3d.Surface surface(String code, String mesh) {
        return new TemplateModel3d.Surface(code, mesh);
    }

    @Test
    void everyCupMeasurementTheFileDeterminesCountsAndTheFreeOnesDoNot() {
        assertThat(ModelFileRules.changedMeasurements(CUP, new CupModel(91, 61, 111, 3, 8, 7, 9, "paper-white")))
                .containsExactly("model.topDiameterMm", "model.bottomDiameterMm", "model.heightMm",
                        "model.bandTopOffsetMm", "model.bandBottomOffsetMm");
        assertThat(ModelFileRules.changedMeasurements(CUP, new CupModel(90, 60, 110, 5, 12, 6, 8, "kraft")))
                .isEmpty();
    }

    @Test
    void numbersAreComparedToTheHundredthAsStored() {
        assertThat(ModelFileRules.changedMeasurements(CUP, new CupModel(90.001, 59.996, 110.004, 3, 8, 6, 8,
                "paper-white"))).isEmpty();
        assertThat(ModelFileRules.changedMeasurements(CUP, new CupModel(90.01, 60, 110, 3, 8, 6, 8, "paper-white")))
                .containsExactly("model.topDiameterMm");
    }

    @Test
    void aBagsTopFoldMayMoveOnlyWithItsHeightKeepingThePrintedHeight() {
        assertThat(ModelFileRules.changedMeasurements(BAG, new BagModel(260, 120, 330, 50, "flat", "kraft-white")))
                .isEmpty();
        assertThat(ModelFileRules.changedMeasurements(BAG, new BagModel(260, 120, 320, 50, "twisted", "kraft-brown")))
                .containsExactly("model.heightMm - model.topFoldMm");
        assertThat(ModelFileRules.changedMeasurements(BAG, new BagModel(270, 130, 320, 40, "twisted", "kraft-brown")))
                .containsExactly("model.widthMm", "model.gussetMm");
    }

    @Test
    void anotherKindOrNoStoredModelIsAChange() {
        assertThat(ModelFileRules.changedMeasurements(CUP, BAG)).containsExactly("model.kind");
        assertThat(ModelFileRules.changedMeasurements(null, CUP)).containsExactly("model.kind");
    }

    @Test
    void theMeshMapIsComparedAsASetAndAnOmittedMapKeepsTheStoredOne() {
        var sides = List.of(surface("FRONT", "print:FRONT"), surface("BACK", "print:BACK"));
        assertThat(ModelFileRules.sameSurfaces(sides, List.of(surface("BACK", "print:BACK"),
                surface("FRONT", " print:FRONT ")))).isTrue();
        assertThat(ModelFileRules.sameSurfaces(sides, List.of(surface("FRONT", "print:FRONT")))).isFalse();

        assertThatCode(() -> ModelFileRules.requireUnchangedWithoutFile(CUP, BODY, CUP, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ModelFileRules.requireUnchangedWithoutFile(CUP, BODY, CUP, BODY))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ModelFileRules.requireUnchangedWithoutFile(CUP, BODY, CUP,
                List.of(surface("BODY", "print:BODY-2"))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.PRINT_CONFIG_MODEL_FILE_REQUIRED);
                    assertThat(ex.getMessage()).contains("model3d.surfaces");
                });
        assertThatThrownBy(() -> ModelFileRules.requireUnchangedWithoutFile(CUP, BODY,
                new CupModel(95, 60, 110, 3, 8, 6, 8, "paper-white"), null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.PRINT_CONFIG_MODEL_FILE_REQUIRED);
                    assertThat(ex.getMessage()).contains("model.topDiameterMm");
                });
    }

    @Test
    void aCompleteMapNamesEveryAreaOnceOnItsPrintMesh() {
        List<String> sides = List.of("FRONT", "BACK", "LEFT", "RIGHT");
        assertThat(ModelFileRules.incompleteSurfaces(List.of(surface("FRONT", "print:FRONT"),
                surface("BACK", "print:BACK"), surface("LEFT", "print:LEFT"), surface("RIGHT", "print:RIGHT")),
                sides)).isEmpty();

        List<FieldError> errors = ModelFileRules.incompleteSurfaces(List.of(surface("FRONT", "print:FRONT"),
                surface("BACK", "Back_Mesh")), sides);

        assertThat(errors).extracting(FieldError::field, FieldError::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple("model3d.surfaces[1].meshName", "MeshNameMismatch"),
                org.assertj.core.groups.Tuple.tuple("model3d.surfaces", "PrintAreaMissing"),
                org.assertj.core.groups.Tuple.tuple("model3d.surfaces", "PrintAreaMissing"));
        assertThat(errors.get(1).message()).contains("LEFT", "print:LEFT");
        assertThat(ModelFileRules.incompleteSurfaces(List.of(), List.of("BODY")))
                .extracting(FieldError::code).containsExactly("PrintAreaMissing");
    }
}
