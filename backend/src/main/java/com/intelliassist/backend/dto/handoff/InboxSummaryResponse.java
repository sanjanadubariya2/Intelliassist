package com.intelliassist.backend.dto.handoff;

/**
 * Headline numbers for the handoff inbox.
 *
 * <p>{@code medianResolutionMinutes} is null when nothing was resolved in the
 * window: no handoffs is not the same as instant handoffs.
 */
public record InboxSummaryResponse(
        long open,
        long mine,
        long unassigned,
        long resolvedLast30Days,
        Long medianResolutionMinutes) {}
