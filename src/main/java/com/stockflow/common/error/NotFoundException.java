package com.stockflow.common.error;

/**
 * The requested thing does not exist, or the caller may not know that it does.
 *
 * <p>Carrying the type and id as fields rather than only inside the message lets
 * {@code GlobalExceptionHandler} put them in a structured response, and lets a caller react
 * programmatically instead of parsing English.</p>
 *
 * <p>Note the second sentence of the summary. For a resource the caller is not allowed to see,
 * returning 404 rather than 403 is usually the right answer: a 403 confirms the row exists, which
 * is itself information — "order SO-20260907-000431 exists but is not yours" tells a competitor
 * how many orders you took today.</p>
 */
public class NotFoundException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String resourceType;
    private final String resourceId;

    public NotFoundException(String resourceType, Object resourceId) {
        super(ErrorCode.NOT_FOUND, "%s %s not found".formatted(resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = String.valueOf(resourceId);
    }

    public String resourceType() {
        return resourceType;
    }

    public String resourceId() {
        return resourceId;
    }
}
