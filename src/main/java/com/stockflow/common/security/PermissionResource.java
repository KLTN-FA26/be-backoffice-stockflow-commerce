package com.stockflow.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one row of the permission catalog, on the controller that owns it.
 *
 * <p>This is what keeps the matrix from rotting. In a system where the 382 permission rows live
 * only in a database table, the catalog drifts the moment somebody ships a screen and forgets the
 * seed script: the endpoint exists, no permission guards it, and nobody notices until an audit.
 * Declaring the resource next to the code that serves it means the catalog is generated from the
 * truth, and {@link PermissionCatalogValidator} fails startup when the two disagree.</p>
 *
 * <pre>
 * &#64;RestController
 * &#64;PermissionResource(
 *         code = "stock-items",
 *         group = "Inventory",
 *         label = "Stock on hand",
 *         route = "/inventory/stock",
 *         apiPath = "/api/v1/inventory/stock-items",
 *         actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.EXPORT})
 * public class StockController { ... }
 * </pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PermissionResource {

    /** Stable code used in permission strings. Never a frontend route. */
    String code();

    /** Tab the resource appears under in the admin screen ("Payroll", "Warehouse", ...). */
    String group();

    /** Human label shown on the permission card. */
    String label();

    /** Frontend route, for the menu and the route guard. Display metadata only. */
    String route() default "";

    /** API base path, shown in the admin UI so an administrator can see what a grant unlocks. */
    String apiPath() default "";

    /** Which actions this resource actually supports - a read-only page has only VIEW_PAGE. */
    Action[] actions();
}
