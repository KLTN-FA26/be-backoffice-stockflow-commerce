package com.stockflow.common.security;

/**
 * The closed set of actions a permission can grant on a resource.
 *
 * <p>Deliberately an enum and not free text. A free-text action column looks flexible for a week
 * and then contains {@code "edit"}, {@code "update"} and {@code "modify"} meaning the same thing,
 * at which point no permission matrix can be rendered or audited. A closed set also lets the admin
 * UI draw one chip per action and show "1/6 granted" without guessing.</p>
 *
 * <p>Not every resource supports every action - a read-only config page has only VIEW_PAGE.
 * Which actions exist for a resource is declared by {@link PermissionResource}.</p>
 */
public enum Action {

    /** Open the screen at all: controls the menu entry and the route guard. */
    VIEW_PAGE("Open page", false),

    /** Read the data behind the screen. Separate from VIEW_PAGE on purpose - see the javadoc below. */
    READ("Read data", false),

    CREATE("Create", false),
    UPDATE("Update", false),

    /** Destructive: shown with a lock in the admin UI and never included by "select all". */
    DELETE("Delete", true),

    /** Business sign-off (approve a PO, a payroll run, a refund). Destructive-class. */
    APPROVE("Approve", true),

    /** Bulk data egress. Sensitive even when READ is granted: one click takes the whole table out. */
    EXPORT("Export", true);

    private final String label;
    private final boolean sensitive;

    Action(String label, boolean sensitive) {
        this.label = label;
        this.sensitive = sensitive;
    }

    public String label() {
        return label;
    }

    /** Sensitive actions require an explicit tick; a "grant all" gesture must skip them. */
    public boolean isSensitive() {
        return sensitive;
    }
}
