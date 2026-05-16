package org.leagueplan.planr.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

public record AvailabilityWindow(
    UUID id,
    DayOfWeek dayOfWeek,
    LocalTime startTime,
    LocalTime endTime,
    UUID divisionId
) {}
