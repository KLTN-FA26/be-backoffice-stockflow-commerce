package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * Resolves {@link AuthenticatedUser} parameters.
 *
 * <p>Two shapes, and the difference is the whole point:</p>
 * <ul>
 *   <li>{@code CurrentUser} — required. No authenticated caller means 401, thrown before the method
 *       body runs, so the parameter is never null.</li>
 *   <li>{@code Optional<CurrentUser>} — optional. For an endpoint that genuinely serves anonymous
 *       callers, such as a public catalogue that personalises when it can.</li>
 * </ul>
 *
 * <p>Making the requirement part of the parameter's <b>type</b> rather than a flag on the annotation
 * means it cannot be got wrong quietly: an endpoint that meant to require a user and wrote
 * {@code Optional} is visible in the signature, and one that wrote {@code CurrentUser} can never
 * receive null.</p>
 */
@Component
public class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final Logger log =
            LoggerFactory.getLogger(AuthenticatedUserArgumentResolver.class);

    private final CurrentUserProvider currentUserProvider;

    public AuthenticatedUserArgumentResolver(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(AuthenticatedUser.class)) {
            return false;
        }
        // nestedIfOptional() unwraps Optional<CurrentUser> to CurrentUser, so @AuthenticatedUser
        // on an Optional<String> is declined here rather than accepted and then failing with a
        // ClassCastException deep inside the dispatcher.
        return CurrentUser.class.equals(parameter.nestedIfOptional().getNestedParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Optional<CurrentUser> user = currentUserProvider.current();

        if (Optional.class.equals(parameter.getParameterType())) {
            return user;
        }
        return user.orElseThrow(() -> {
            // The explanation goes to the log, for whoever is writing the endpoint. The caller gets
            // the generic translated 401 - telling an anonymous client how our parameter binding
            // works is telling an attacker about the shape of the application.
            log.warn("{}.{} declares @AuthenticatedUser CurrentUser but no user is authenticated. "
                            + "Declare it as Optional<CurrentUser> if the endpoint should also "
                            + "serve anonymous callers.",
                    parameter.getMethod() == null ? "?"
                            : parameter.getMethod().getDeclaringClass().getSimpleName(),
                    parameter.getMethod() == null ? "?" : parameter.getMethod().getName());
            return new BusinessException(ErrorCode.UNAUTHORIZED);
        });
    }
}
