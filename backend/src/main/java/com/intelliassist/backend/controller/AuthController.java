package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.auth.AuthResponse;
import com.intelliassist.backend.dto.auth.CurrentUserResponse;
import com.intelliassist.backend.dto.auth.LoginRequest;
import com.intelliassist.backend.dto.auth.RegisterRequest;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>Thin by design: validate, delegate, translate the result to HTTP. All
 * decisions live in {@link AuthService}, which is what makes them unit-testable
 * without a web layer.
 *
 * <p>{@code /register} and {@code /login} are the only unauthenticated routes in
 * the dashboard API; everything else is default-deny.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Creates a user, an organization and an OWNER membership, then signs in. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * The caller's identity, organization and role.
     *
     * <p>The principal is resolved by the JWT filter, which confirms the
     * membership against the database on every request — so the role returned
     * here is current, not whatever was true when the token was issued.
     */
    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(authService.currentUser(principal));
    }
}
