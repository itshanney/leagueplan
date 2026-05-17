package org.leagueplan.planr.scheduler;

import java.util.UUID;

public record Fixture(
    UUID homeTeamId,
    UUID awayTeamId,
    UUID divisionId,
    int gameDurationMinutes
) {}
