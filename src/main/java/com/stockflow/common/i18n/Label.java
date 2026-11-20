package com.stockflow.common.i18n;

/**
 * A refusal argument read as its label, like an enum argument ({@link Messages#refusal}): {@code label.<type>.<name>}
 * from the bundles, {@code name} itself when there is none. For a status another module hands over as text - its enum
 * stays internal there (a customer's status reaches order and design as a {@code String}) - so a Vietnamese refusal
 * does not show the raw code "INACTIVE".
 *
 * @param type the label group, the enum's simple name ({@code CustomerStatus})
 * @param name the constant, e.g. {@code INACTIVE}
 */
public record Label(String type, String name) {

    @Override
    public String toString() {
        return name;
    }
}
