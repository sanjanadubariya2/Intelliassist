package com.intelliassist.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How a provider sign-up names the new user and their workspace. */
class AuthServiceNamingTest {

    @Test
    void usesTheProvidersName() {
        assertThat(AuthService.displayName("  Ada Lovelace ", "ada@example.com"))
                .isEqualTo("Ada Lovelace");
    }

    @Test
    void fallsBackToTheAddressWhenTheProviderHasNoName() {
        assertThat(AuthService.displayName(null, "ada.l@example.com")).isEqualTo("ada.l");
        assertThat(AuthService.displayName("   ", "ada.l@example.com")).isEqualTo("ada.l");
    }

    @Test
    void namesTheWorkspaceAfterTheFirstName() {
        assertThat(AuthService.workspaceName("Ada Lovelace")).isEqualTo("Ada's workspace");
        assertThat(AuthService.workspaceName("ada")).isEqualTo("ada's workspace");
    }

    @Test
    void neverExceedsTheColumnLength() {
        String longName = "A".repeat(250);
        assertThat(AuthService.displayName(longName, "a@b.c")).hasSize(200);
        assertThat(AuthService.workspaceName(longName)).hasSizeLessThanOrEqualTo(200);
    }
}
