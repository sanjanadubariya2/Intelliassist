package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.bot.BotResponse;
import com.intelliassist.backend.dto.bot.CreateBotRequest;
import com.intelliassist.backend.dto.bot.UpdateBotRequest;
import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.BotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.intelliassist.backend.dto.bot.BotAppearanceRequest;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PatchMapping;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bot CRUD for the dashboard.
 *
 * <p>Notice what these signatures do <em>not</em> take: an organization id. It
 * comes from the authenticated principal every time. There is no request shape
 * in which a client can name the tenant it wants to operate on, which is what
 * makes cross-tenant access unreachable rather than merely guarded.
 */
@RestController
@RequestMapping("/api/v1/bots")
@Validated
public class BotController {

    /** Bounded so a caller cannot ask for an unbounded page. */
    private static final int MAX_PAGE_SIZE = 100;

    private final BotService botService;

    public BotController(BotService botService) {
        this.botService = botService;
    }

    @PostMapping
    public ResponseEntity<BotResponse> create(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody CreateBotRequest request) {

        BotResponse created = botService.create(caller, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<PageResponse<BotResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        // Newest first: the dashboard's default view is "what did I just make".
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(botService.list(caller, pageable));
    }

    @GetMapping("/{botId}")
    public ResponseEntity<BotResponse> get(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID botId) {
        return ResponseEntity.ok(botService.get(caller, botId));
    }

    @PutMapping("/{botId}")
    public ResponseEntity<BotResponse> update(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @Valid @RequestBody UpdateBotRequest request) {
        return ResponseEntity.ok(botService.update(caller, botId, request));
    }

    @DeleteMapping("/{botId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID botId) {
        botService.delete(caller, botId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The Customization screen's save.
     *
     * <p>PATCH, not PUT: the screen saves one section at a time, and a null
     * field means "leave it alone" rather than "blank it".
     */
    @Operation(
            summary = "Update a bot's widget appearance",
            description =
                    "Colour, position, launcher icon and the welcome and fallback wording. "
                            + "Every field is optional; omitted fields are left unchanged.")
    @PatchMapping("/{botId}/appearance")
    public ResponseEntity<BotResponse> updateAppearance(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @Valid @RequestBody BotAppearanceRequest request) {

        return ResponseEntity.ok(botService.updateAppearance(caller, botId, request));
    }
}
