package com.stockflow.fulfillment.internal.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A carrier's refusal in words a dispatcher can act on (WF4 UX audit S17-2): which codes are read out of the described
 * refusal, and that each code the carrier is known to send has an answer in both bundles that says what to do and who
 * does it.
 */
class CarrierRefusalsTest {

    private static final ResourceBundleMessageSource BUNDLE = bundle();

    @Test
    void theCodeIsReadOutOfTheDescribedRefusalAndNothingElse() {
        assertThat(CarrierRefusals.codeOf("400 INVALID_LOCATION: Failed")).isEqualTo("INVALID_LOCATION");
        assertThat(CarrierRefusals.codeOf("406 INVALID_SERVICE_HOUR: Booking time not valid"))
                .isEqualTo("INVALID_SERVICE_HOUR");
        assertThat(CarrierRefusals.codeOf("422 SERVICE_NOT_VALID_AT_PICKUP")).isEqualTo("SERVICE_NOT_VALID_AT_PICKUP");
        // Not a described refusal: no status, no code, the server's own sentence, a missing text.
        assertThat(CarrierRefusals.codeOf("Ahamove did not answer")).isNull();
        assertThat(CarrierRefusals.codeOf("400: Failed")).isNull();
        assertThat(CarrierRefusals.codeOf("400 failed: lower case is a sentence")).isNull();
        assertThat(CarrierRefusals.codeOf(null)).isNull();
    }

    @Test
    void aKeyIsBuiltOnlyFromWhatLooksLikeAnErrorCode() {
        assertThat(CarrierRefusals.keyOf("INVALID_LOCATION")).isEqualTo("carrier.refusal.INVALID_LOCATION");
        assertThat(CarrierRefusals.keyOf(null)).isNull();
        assertThat(CarrierRefusals.keyOf("not a code")).isNull();
        assertThat(CarrierRefusals.keyOf("A")).isNull();
    }

    @Test
    void everyKnownCodeSaysWhatToDoAndWhoDoesItInBothLanguages() {
        for (String code : new String[]{"INVALID_LOCATION", "INVALID_SERVICE_HOUR", "SERVICE_NOT_VALID_AT_PICKUP"}) {
            String key = CarrierRefusals.keyOf(code);
            String vietnamese = BUNDLE.getMessage(key, null, new Locale("vi"));
            String english = BUNDLE.getMessage(key, null, Locale.ENGLISH);
            assertThat(vietnamese).as(key).doesNotContain(code).doesNotContain("400").doesNotContain("406");
            assertThat(english).as(key).doesNotContain(code);
        }
    }

    /**
     * Who acts follows the permissions: the pickup point's address and the vehicle types' service codes are the carrier
     * setup (fulfillment-carriers:UPDATE, Quản lý kho only, kltn-docs 09 §2); pressing "Tạo đơn ở hãng" again and
     * choosing another pickup time are the booking (fulfillment-dispatch:UPDATE, warehouse staff too).
     */
    @Test
    void theCarrierSetupIsTheWarehouseManagersAndTheBookingIsWarehouseStaffs() {
        Locale vi = new Locale("vi");
        String location = BUNDLE.getMessage("carrier.refusal.INVALID_LOCATION", null, vi);
        assertThat(location).contains("Quản lý kho kiểm lại địa chỉ điểm lấy hàng").contains("(cấu hình hãng)")
                .contains("Nhân viên Sale sửa").contains("nhân viên kho bấm “Tạo đơn ở hãng” lại")
                .doesNotContain("Nhân viên kho kiểm");
        String service = BUNDLE.getMessage("carrier.refusal.SERVICE_NOT_VALID_AT_PICKUP", null, vi);
        assertThat(service).contains("Quản lý kho kiểm lại mã dịch vụ").contains("cấu hình hãng")
                .doesNotContain("Nhân viên kho");
        String hour = BUNDLE.getMessage("carrier.refusal.INVALID_SERVICE_HOUR", null, vi);
        assertThat(hour).contains("Nhân viên kho chọn giờ hẹn khác").contains("Giờ hẹn lấy hàng")
                .contains("Tạo đơn ở hãng");

        assertThat(BUNDLE.getMessage("carrier.refusal.INVALID_LOCATION", null, Locale.ENGLISH))
                .contains("The Warehouse Manager checks the carrier's pickup point address")
                .contains("warehouse staff press \"Create order at carrier\" again");
        assertThat(BUNDLE.getMessage("carrier.refusal.SERVICE_NOT_VALID_AT_PICKUP", null, Locale.ENGLISH))
                .contains("The Warehouse Manager checks the vehicle type's service code").doesNotContain("staff");
        assertThat(BUNDLE.getMessage("carrier.refusal.INVALID_SERVICE_HOUR", null, Locale.ENGLISH))
                .contains("Warehouse staff choose another time");
    }

    private static ResourceBundleMessageSource bundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
