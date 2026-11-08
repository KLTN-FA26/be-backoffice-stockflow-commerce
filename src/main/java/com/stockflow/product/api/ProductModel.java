package com.stockflow.product.api;

/**
 * The shape a customer designs on (kltn-docs 01 §4.10, open gap G11): a parametric cup or bag, described by the sizes
 * a Sales Manager reads off the physical product, in millimetres. The print areas are computed from it on the server -
 * a cup's unrolled band in particular - so the browser, the preflight and the producer all work from one geometry.
 *
 * <p>Two kinds, closed: a glTF model is a later port (open question B36) and is refused until it exists.</p>
 */
public sealed interface ProductModel permits CupModel, BagModel {

    /** The discriminator written to {@code model_params_json} and sent to the storefront. */
    String kind();
}
