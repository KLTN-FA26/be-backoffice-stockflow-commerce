package com.stockflow.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the signed-in {@link CurrentUser} straight into a controller method.
 *
 * <pre>
 * &#64;GetMapping("/me/orders")
 * &#64;RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
 * public ApiResponse&lt;PageResponse&lt;OrderResponse&gt;&gt; myOrders(&#64;AuthenticatedUser CurrentUser user) {
 *     ...
 * }
 * </pre>
 *
 * <p>The alternative is injecting {@code CurrentUserProvider} into the controller and unwrapping an
 * {@code Optional} at the top of every method. That is three lines of the same ceremony per
 * endpoint, and each one is a chance to write {@code .orElse(null)} and hand a null user to a
 * service that does not expect one.</p>
 *
 * <p>Declare the parameter as {@code CurrentUser} to require authentication — the resolver throws
 * 401 if there is none — or as {@code Optional&lt;CurrentUser&gt;} on an endpoint that legitimately
 * serves anonymous callers. The type is the declaration of intent, so an endpoint cannot silently
 * accept anonymous access because somebody forgot a null check.</p>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedUser {
}
