package com.stockflow.common.web;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.ScopeViolationException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * The single place where exceptions become HTTP responses. Controllers never write try/catch.
 *
 * <h2>Three rules that shape everything below</h2>
 *
 * <p><b>1. The code is the contract; the message is for humans.</b> Clients branch on
 * {@code errorCode}, which is an {@link ErrorCode} name and never changes. The message is translated
 * through {@link Messages} and may be reworded at any time.</p>
 *
 * <p><b>2. A 4xx is the caller's problem, a 5xx is ours — and the log level says which.</b> Logging
 * a validation failure at {@code ERROR} buries the real faults; logging a genuine bug at
 * {@code WARN} means nobody is paged.</p>
 *
 * <p><b>3. Nothing internal reaches the client.</b> An unexpected exception's message is written for
 * a developer and regularly contains a SQL fragment, a file path or a class name — free
 * reconnaissance for anyone probing the API. The correlation id, which <i>is</i> returned, is how
 * the response and the stack trace are joined up instead.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Messages messages;
    private final MessageSource messageSource;

    public GlobalExceptionHandler(Messages messages, MessageSource messageSource) {
        this.messages = messages;
        this.messageSource = messageSource;
    }

    // ------------------------------------------------------------------ our own exceptions

    /**
     * Every {@link BusinessException} subtype: {@code InsufficientStockException},
     * {@code NotFoundException}, {@code StorageException}, {@code ExternalServiceException}.
     *
     * <p>One handler for the family, because each carries the status it maps to. Adding an exception
     * type does not mean adding a handler — which is the point of putting the code on the exception
     * rather than in a switch here.</p>
     *
     * <p>For a <b>client</b> fault the exception's own message is returned rather than the
     * translated generic one: these are written by us for exactly this purpose, and "requested 6,
     * available 5" is more useful to a caller than "not enough stock". The generic translation is
     * the fallback when the exception carries nothing specific.</p>
     *
     * <h2>Server faults are the exception, and must not return their own message</h2>
     *
     * <p>{@code INTERNAL_ERROR} (500), {@code EXTERNAL_SERVICE_ERROR} (502) and
     * {@code STORAGE_ERROR} (503) are {@code BusinessException}s too, and their messages are not
     * written for a client to read. Concretely, from code in this repository:</p>
     *
     * <ul>
     *   <li>{@code LocalFileStorage}: {@code "Cannot create local storage directory " + root} —
     *       the server's absolute filesystem path;</li>
     *   <li>{@code S3FileStorage}: {@code "No stored file with key " + key} — internal key
     *       layout;</li>
     *   <li>{@code ExternalServiceException}: the upstream's own error text and the internal
     *       service name.</li>
     * </ul>
     *
     * <p>Each is exactly what an attacker maps infrastructure with, and none of it helps the
     * caller: there is nothing a client can do differently about a 503. So for a server fault the
     * client gets the translated generic message and the full detail goes to the log, where it
     * belongs. This is the same rule the class javadoc states for unexpected exceptions; a server
     * fault raised as a {@code BusinessException} is not a different case just because it arrived
     * through a typed exception.</p>
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        boolean serverFault = ex.errorCode().isServerFault();
        if (serverFault) {
            log.error("{} - {}", ex.errorCode(), ex.getMessage(), ex);
        } else {
            log.warn("{} - {}", ex.errorCode(), ex.getMessage());
        }
        String message = serverFault || ex.getMessage() == null || ex.getMessage().isBlank()
                ? messages.forCode(ex.errorCode())
                : ex.getMessage();
        return respond(ex.errorCode(), message, null);
    }

    /**
     * Denied by the permission matrix, or by the data scope.
     *
     * <p>Both are {@code BusinessException}s and would be caught above; they are separated to be
     * logged at a level a security dashboard can filter on. A run of these from one user is what
     * probing looks like, and it should not be buried in ordinary validation noise.</p>
     *
     * <p>The user sees the <b>translated generic</b> message, never the internal one. "No data scope
     * in force — annotate the endpoint with @RequiresPermission" is a message for a developer, and
     * telling an attacker which guard stopped them is telling them where to push.</p>
     */
    @ExceptionHandler({PermissionDeniedException.class, ScopeViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleAuthorisation(BusinessException ex) {
        log.warn("AUTHORISATION DENIED: {}", ex.getMessage());
        return respond(ex.errorCode(), messages.forCode(ex.errorCode()), null);
    }

    // ------------------------------------------------------------------ request validation

    /**
     * Bean Validation on a {@code @RequestBody}.
     *
     * <p>Every field is reported, structured, so a form can put each message under its own input.
     * See {@link FieldError} for why this is not one joined string.</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = new java.util.ArrayList<>();

        ex.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.add(new FieldError(
                error.getField(),
                // Resolved through the MessageSource, not error.getDefaultMessage(): the default is
                // the string Hibernate Validator already interpolated, so it ignores both our
                // bundles and the caller's Accept-Language. Going through the message source is
                // what makes i18n/validation_vi.properties reach the user.
                messageSource.getMessage(error, LocaleContextHolder.getLocale()),
                // The constraint name (NotBlank, Min...), stable across translations.
                error.getCode())));

        // Class-level constraints - "endDate must be after startDate" and friends - produce an
        // ObjectError, which getFieldErrors() does not return. Dropping them means a 400 with an
        // empty fieldErrors list and nothing for the form to display.
        ex.getBindingResult().getGlobalErrors().forEach(error -> fieldErrors.add(new FieldError(
                error.getObjectName(),
                messageSource.getMessage(error, LocaleContextHolder.getLocale()),
                error.getCode())));

        log.warn("Validation failed on {} field(s): {}", fieldErrors.size(), fieldErrors);
        return respond(ErrorCode.VALIDATION_FAILED,
                messages.forCode(ErrorCode.VALIDATION_FAILED), fieldErrors);
    }

    /** Bean Validation on a method parameter or path variable rather than on a body. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> FieldError.of(
                        String.valueOf(violation.getPropertyPath()), violation.getMessage()))
                .toList();
        log.warn("Constraint violation: {}", fieldErrors);
        return respond(ErrorCode.VALIDATION_FAILED,
                messages.forCode(ErrorCode.VALIDATION_FAILED), fieldErrors);
    }

    /**
     * Malformed JSON, or a body that could not be bound.
     *
     * <p>The parser's message is not returned. It carries position information and often a fragment
     * of the payload — which may include whatever secret the client sent, putting it in both a
     * response and a log.</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getClass().getSimpleName());
        return respond(ErrorCode.MALFORMED_REQUEST,
                messages.forCode(ErrorCode.MALFORMED_REQUEST), null);
    }

    /** A path variable or query parameter that will not convert: {@code ?page=abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() == null
                ? "the expected type"
                : ex.getRequiredType().getSimpleName();
        return respond(ErrorCode.UNSUPPORTED_PARAMETER,
                messages.forCode(ErrorCode.UNSUPPORTED_PARAMETER),
                List.of(FieldError.of(ex.getName(), "must be " + expected)));
    }

    /**
     * Domain value objects reject bad input with {@code IllegalArgumentException}; aggregates reject
     * impossible transitions with {@code IllegalStateException}.
     *
     * <p>Without this the client sees a 500 — "the server is broken" when the truth is "the request
     * was" — and genuine faults get buried in the noise.</p>
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleIllegalInput(RuntimeException ex) {
        // ERROR with the stack trace, not WARN with the message. These are thrown by our value
        // objects, and also by Hibernate, Jackson, the AWS SDK and Spring itself - so a share of
        // them are genuine server-side bugs, and reporting those to the client as "your request
        // was wrong" at WARN means nobody is ever paged for them.
        log.error("IllegalArgument/IllegalState reached the exception handler - if this is a "
                + "domain rule, throw a BusinessException instead so it is a deliberate 400", ex);
        // The generic message. A library's message carries class names, SQL fragments and file
        // paths - the reconnaissance rule 3 of this class forbids handing out.
        return respond(ErrorCode.VALIDATION_FAILED,
                messages.forCode(ErrorCode.VALIDATION_FAILED), null);
    }

    // ------------------------------------------------------------------ persistence

    /** Somebody else changed the row first. Retryable, and the message says so. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return respond(ErrorCode.OPTIMISTIC_LOCK, messages.forCode(ErrorCode.OPTIMISTIC_LOCK), null);
    }

    /**
     * A pessimistic lock could not be taken within the timeout.
     *
     * <p>Logged at {@code WARN} with the message: a run of these means a hot row — usually a popular
     * SKU at checkout — which is a capacity signal worth seeing.</p>
     */
    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse<Void>> handleLockTimeout(RuntimeException ex) {
        log.warn("Lock could not be acquired: {}", ex.getMessage());
        return respond(ErrorCode.LOCK_TIMEOUT, messages.forCode(ErrorCode.LOCK_TIMEOUT), null);
    }

    /**
     * A unique or foreign-key constraint refused the write.
     *
     * <p>Logged at {@code ERROR} even though it returns a 4xx: reaching the database with a duplicate
     * means the application-level check was missing or lost a race, and something in the code should
     * have caught it first.</p>
     *
     * <p>The database's message is never returned — it names the constraint, the table and the
     * column, which is a free schema map for anyone probing the API.</p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException ex) {
        log.error("Database constraint violated - the application should have caught this first", ex);
        return respond(ErrorCode.DUPLICATE_KEY, messages.forCode(ErrorCode.DUPLICATE_KEY), null);
    }

    // ------------------------------------------------------------------ framework

    /**
     * Every standard Spring MVC failure: wrong method, unsupported media type, missing parameter,
     * unacceptable {@code Accept} header, a {@code ResponseStatusException} thrown by hand.
     *
     * <h2>Why this handler is not optional</h2>
     *
     * <p>{@code ExceptionHandlerExceptionResolver} runs <b>before</b>
     * {@code DefaultHandlerExceptionResolver}, and this class declares
     * {@code @ExceptionHandler(Exception.class)}. Without a more specific handler, the catch-all
     * matches first and Spring's own resolver — the thing that knows a
     * {@code HttpRequestMethodNotSupportedException} is a 405 — never runs. Every one of those
     * becomes a 500 logged at {@code ERROR} with a full stack trace, so a client sending
     * {@code GET} where the endpoint expects {@code POST} pages whoever is on call.</p>
     *
     * <p>Spring 6 gives all of them a common interface, {@link ErrorResponse}, carrying the status
     * they should have had — but {@code @ExceptionHandler} takes
     * {@code Class<? extends Throwable>}, and {@code ErrorResponse} is an interface that does not
     * extend {@code Throwable}. So the types are listed explicitly and the status is read back off
     * the interface. Listing them is also honest: this is the set the framework raises today, and
     * a new one arriving in a future Spring version should be a deliberate addition here rather
     * than something silently swept into a catch-all.</p>
     *
     * <p>{@code HandlerMethodValidationException} is in the list because since Spring 6.1 a
     * constraint on a controller <b>parameter</b> raises that rather than
     * {@code ConstraintViolationException} — so the handler further up would not have fired.</p>
     *
     * <p>Every omission from this list costs the same thing twice: the client gets 500 instead of
     * the 400/404/503 the request actually deserved, and the server logs a full stack trace at
     * ERROR for a perfectly ordinary bad request — which is how an error dashboard ends up full of
     * noise that nobody can act on and everybody learns to ignore.</p>
     */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            ServletRequestBindingException.class,
            HandlerMethodValidationException.class,
            // BindException is the SUPERCLASS of MethodArgumentNotValidException, which has its own
            // handler above. Listing the subclass does not cover the parent, and the parent is what
            // a failed @Valid @ModelAttribute (query-object binding) raises. Spring picks the most
            // specific handler, so both stay correct.
            BindException.class,
            // The paths that do not produce MethodArgumentTypeMismatchException - binding a value
            // to a nested field, for one.
            TypeMismatchException.class,
            // 503, and reachable: virtual threads are on and any async return type can time out.
            AsyncRequestTimeoutException.class,
            // 404. NoResourceFoundException (a missing static resource) is handled separately; this
            // is the no-mapping case, raised when throw-exception-if-no-handler-found is set.
            NoHandlerFoundException.class,
            ErrorResponseException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleFrameworkError(Exception exception) {
        // Most listed types implement ErrorResponse and carry their own status. BindException and
        // TypeMismatchException do not - they are plain exceptions - so the 400 fallback is what
        // actually serves them, and it is the right answer for both: a failed binding or a value
        // of the wrong type is a bad request.
        int status = exception instanceof ErrorResponse errorResponse
                ? errorResponse.getStatusCode().value()
                : 400;
        ErrorCode code = switch (status) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405, 406, 415 -> ErrorCode.UNSUPPORTED_PARAMETER;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
        // 4xx at WARN, 5xx at ERROR - the rule the whole class follows.
        if (status >= 500) {
            log.error("Framework error {}: {}", status,
                    exception.getClass().getSimpleName(), exception);
        } else {
            log.warn("Framework error {}: {}", status, exception.getClass().getSimpleName());
        }
        // The framework's own status wins over the mapped code's, so a 405 stays a 405.
        return ResponseEntity.status(status)
                .body(ApiResponse.error(code.name(), messages.forCode(code), null,
                        MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    /**
     * {@code AccessDeniedException} raised by <b>method security</b> inside the dispatcher.
     *
     * <p>Not the filter chain: a denial there is handled by {@code ExceptionTranslationFilter} and
     * reaches {@link com.stockflow.common.security.ApiAccessDeniedHandler}, never
     * {@code @RestControllerAdvice}. This covers a {@code @PreAuthorize} that refused after the
     * handler method had been selected.</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("AUTHORISATION DENIED by method security: {}", ex.getMessage());
        return respond(ErrorCode.FORBIDDEN, messages.forCode(ErrorCode.FORBIDDEN), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return respond(ErrorCode.PAYLOAD_TOO_LARGE,
                messages.forCode(ErrorCode.PAYLOAD_TOO_LARGE), null);
    }

    /**
     * No handler matched the URL.
     *
     * <p>Handled so a mistyped path returns the same envelope as everything else, and not at
     * {@code ERROR}: scanners produce a lot of these and they say nothing about the application's
     * health.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return respond(ErrorCode.NOT_FOUND, messages.forCode(ErrorCode.NOT_FOUND), null);
    }

    // ------------------------------------------------------------------ the backstop

    /**
     * Anything not handled above: a real defect.
     *
     * <p>Logged with the full stack trace; the response says nothing beyond the generic message and
     * the correlation id. A user quoting that id leads straight to the trace, without any of it
     * being exposed.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, messages.forCode(ErrorCode.INTERNAL_ERROR), null);
    }

    /**
     * Builds the response, attaching the correlation id from the MDC.
     *
     * <p>Every path goes through here so that no handler can forget the id — which is the one field
     * that turns an unactionable "it said something went wrong" into a searchable incident.</p>
     */
    private ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message,
                                                      List<FieldError> fieldErrors) {
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.name(), message, fieldErrors,
                        MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
