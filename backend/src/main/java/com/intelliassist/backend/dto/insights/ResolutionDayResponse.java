package com.intelliassist.backend.dto.insights;

import java.time.LocalDate;

/** Conversations started on one UTC day, by how they currently stand. */
public record ResolutionDayResponse(
        LocalDate date, int started, int resolved, int escalated, int open) {}
