package com.stockflow.order.internal.domain;

/**
 * Why goods that came back are not refunded, or not all of them (WF-05 Q-4; Luật Thương mại 2005 Điều 318, 319): the
 * answer to the customer's complaint, from a fixed list so it is the same words for every customer and stands as
 * evidence if the claim is disputed. The customer sees its label ({@code label.ReturnRefusalReason.*} in the
 * {@code _vi} bundle) and an optional public note; {@code ck_return_request_refusal_reason} holds the same names.
 */
public enum ReturnRefusalReason {

    /** The goods were damaged by how the customer stored or used them. */
    CUSTOMER_DAMAGE,

    /** No defect was found in the goods that came back. */
    NOT_DEFECTIVE,

    /** The claim falls outside the return policy agreed in the contract. */
    OUT_OF_POLICY,

    /** Another reason, which the public note states ({@code ck_return_request_refusal_note}). */
    OTHER
}
