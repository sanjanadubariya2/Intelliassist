package com.intelliassist.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Authenticated, but not allowed to touch this resource.
 *
 * <p>Used for cross-tenant access and for insufficient role. The message never
 * says whether the resource exists, since that would let one organization probe
 * another's id space.
 */
public class AccessDeniedException extends ApiException {

    public AccessDeniedException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static AccessDeniedException notAMember() {
        return new AccessDeniedException("You do not have access to this organization");
    }

    public static AccessDeniedException insufficientRole() {
        return new AccessDeniedException("Your role does not permit this action");
    }
}
