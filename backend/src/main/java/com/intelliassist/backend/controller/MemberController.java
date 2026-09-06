package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.auth.MemberResponse;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people in the caller's organization.
 *
 * <p>Read-only. Inviting and removing members needs an invitation flow, email
 * delivery and a role-change audit trail — a real feature rather than one more
 * endpoint, and shipping half of it would be worse than shipping none.
 */
@Tag(name = "Members", description = "People in the organization")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final OrganizationMemberRepository memberRepository;

    public MemberController(OrganizationMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Operation(
            summary = "List the organization's members",
            description =
                    "Always the caller's own organization — there is no id to pass, so there "
                            + "is nothing to tamper with.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The members"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid token")
    })
    @GetMapping
    public ResponseEntity<List<MemberResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        // The organization comes from the principal, never from the request.
        return ResponseEntity.ok(memberRepository.findMembers(caller.organizationId()));
    }
}
