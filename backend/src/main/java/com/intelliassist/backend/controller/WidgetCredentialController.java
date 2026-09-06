package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.publicapi.AddAllowedDomainRequest;
import com.intelliassist.backend.dto.publicapi.AllowedDomainResponse;
import com.intelliassist.backend.dto.publicapi.ApiKeyResponse;
import com.intelliassist.backend.dto.publicapi.CreateApiKeyRequest;
import com.intelliassist.backend.dto.publicapi.CreatedApiKeyResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.WidgetCredentialService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard management of widget credentials.
 *
 * <p>These are JWT routes, not public ones: a widget can use a key but must
 * never be able to create, list or revoke one.
 */
@RestController
@RequestMapping("/api/v1")
public class WidgetCredentialController {

    private final WidgetCredentialService widgetCredentialService;

    public WidgetCredentialController(WidgetCredentialService widgetCredentialService) {
        this.widgetCredentialService = widgetCredentialService;
    }

    /** The response contains the raw key. It is never retrievable again. */
    @PostMapping("/bots/{botId}/api-keys")
    public ResponseEntity<CreatedApiKeyResponse> createApiKey(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @Valid @RequestBody CreateApiKeyRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(widgetCredentialService.createApiKey(caller, botId, request.name()));
    }

    @GetMapping("/bots/{botId}/api-keys")
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID botId) {
        return ResponseEntity.ok(widgetCredentialService.listApiKeys(caller, botId));
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ResponseEntity<Void> revokeApiKey(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID keyId) {
        widgetCredentialService.revokeApiKey(caller, keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bots/{botId}/domains")
    public ResponseEntity<AllowedDomainResponse> addDomain(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @Valid @RequestBody AddAllowedDomainRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(widgetCredentialService.addDomain(caller, botId, request.domain()));
    }

    @GetMapping("/bots/{botId}/domains")
    public ResponseEntity<List<AllowedDomainResponse>> listDomains(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID botId) {
        return ResponseEntity.ok(widgetCredentialService.listDomains(caller, botId));
    }

    @DeleteMapping("/domains/{domainId}")
    public ResponseEntity<Void> removeDomain(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID domainId) {
        widgetCredentialService.removeDomain(caller, domainId);
        return ResponseEntity.noContent().build();
    }
}
