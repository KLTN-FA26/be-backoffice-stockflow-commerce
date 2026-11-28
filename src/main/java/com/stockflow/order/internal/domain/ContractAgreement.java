package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * What a contract says beyond the parties, the goods, the schedule and the payment plan: the article "Thoả thuận khác"
 * (special terms), the three standing terms - penalty, tolerance of a printed run's quantity, warranty - when this
 * contract's differ from the configured ones, and who signs for each side. All of it is optional, and a blank text is no
 * text: {@code null} means "the configured default" for a number and "none" for the rest.
 *
 * <p>The caps are the database's too ({@code ck_sales_contract_penalty_percent} and its siblings): a penalty above 8%
 * would be struck down by a court (Luật Thương mại 2005 Điều 301), a tolerance above 10% is a typing mistake (as the
 * configured one is refused above it), and no warranty runs past three years.</p>
 *
 * @param specialTerms                at most 4000 characters
 * @param penaltyPercent              0 to 8; null = the configured default
 * @param quantityTolerancePercent    0 to 10; null = the configured default
 * @param warrantyMonths              0 to 36; null = the configured default
 */
public record ContractAgreement(String specialTerms, Integer penaltyPercent, Integer quantityTolerancePercent,
                                Integer warrantyMonths, SellerSigner seller, BuyerSigner buyer) {

    /** Nothing agreed beyond the standard terms. */
    public static final ContractAgreement NONE = new ContractAgreement(null, null, null, null, SellerSigner.NONE,
            BuyerSigner.NONE);

    /** The seller's signer: their name and title, and the authorization they sign under when they are not the legal representative. */
    public record SellerSigner(String name, String title, String authorizationRef) {

        public static final SellerSigner NONE = new SellerSigner(null, null, null);

        public SellerSigner {
            name = text(name, 200, "refusal.contract.signer", "Signer name");
            title = text(title, 200, "refusal.contract.signer", "Signer title");
            authorizationRef = text(authorizationRef, 100, "refusal.contract.signerAuthorization",
                    "Authorization reference");
            if (name == null && (title != null || authorizationRef != null)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A signer's title or authorization is "
                        + "recorded with the signer's name").localized("refusal.contract.signerName");
            }
        }

        public boolean isNone() {
            return name == null;
        }
    }

    /** The buyer's signer: their name and title. */
    public record BuyerSigner(String name, String title) {

        public static final BuyerSigner NONE = new BuyerSigner(null, null);

        public BuyerSigner {
            name = text(name, 200, "refusal.contract.signer", "Signer name");
            title = text(title, 200, "refusal.contract.signer", "Signer title");
            if (name == null && title != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A signer's title is recorded with the "
                        + "signer's name").localized("refusal.contract.signerName");
            }
        }

        public boolean isNone() {
            return name == null;
        }
    }

    /** The configured terms a contract starts from, which an override is measured against. */
    public record Standard(int penaltyPercent, int quantityTolerancePercent, int warrantyMonths) { }

    public ContractAgreement {
        specialTerms = text(specialTerms, 4000, "refusal.contract.specialTerms", "Special terms");
        penaltyPercent = within(penaltyPercent, 0, 8, "refusal.contract.penaltyPercent", "Penalty");
        quantityTolerancePercent = within(quantityTolerancePercent, 0, 10, "refusal.contract.tolerancePercent",
                "Quantity tolerance");
        warrantyMonths = within(warrantyMonths, 0, 36, "refusal.contract.warrantyMonths", "Warranty");
        seller = seller == null ? SellerSigner.NONE : seller;
        buyer = buyer == null ? BuyerSigner.NONE : buyer;
    }

    /**
     * Whether it deviates from the standard terms (QĐ-4): special terms always do, an override only when it differs from
     * the configured default - writing the default down changes nothing.
     */
    public boolean deviates(Standard standard) {
        return specialTerms != null
                || (penaltyPercent != null && penaltyPercent != standard.penaltyPercent())
                || (quantityTolerancePercent != null
                && quantityTolerancePercent != standard.quantityTolerancePercent())
                || (warrantyMonths != null && warrantyMonths != standard.warrantyMonths());
    }

    /** The text stripped; null when blank. @throws BusinessException {@code VALIDATION_FAILED} past {@code max} */
    static String text(String value, int max, String key, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "%s: at most %d characters".formatted(what, max))
                    .localized(key, max);
        }
        return stripped;
    }

    private static Integer within(Integer value, int min, int max, String key, String what) {
        if (value != null && (value < min || value > max)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "%s: a whole number, %d to %d".formatted(what, min,
                    max)).localized(key, min, max);
        }
        return value;
    }
}
