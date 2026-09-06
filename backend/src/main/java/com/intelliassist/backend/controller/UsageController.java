package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.usage.BotUsageResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.UsageService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usage reporting for the dashboard.
 *
 * <p>Readable by any member: knowing how busy a bot is does not require the
 * ability to change it.
 */
@RestController
@RequestMapping("/api/v1")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    /**
     * Daily activity, defaulting to the last 30 days.
     *
     * <p>Days with no traffic are omitted rather than returned as zeros.
     */
    @GetMapping("/bots/{botId}/usage")
    public ResponseEntity<BotUsageResponse> getBotUsage(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {

        return ResponseEntity.ok(usageService.getBotUsage(caller, botId, from, to));
    }
}
