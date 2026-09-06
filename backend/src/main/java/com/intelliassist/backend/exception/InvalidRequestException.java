package com.intelliassist.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Input that is well-formed but semantically wrong.
 *
 * <p>Distinct from Bean Validation, which handles shape — length, pattern,
 * presence. This is for rules that need context to check, such as whether a
 * string parses into a usable hostname.
 */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
