package org.leagueplan.planr.scheduler;

import java.util.UUID;

public record PracticeFixture(UUID slotId, UUID teamId, UUID divisionId, int durationMinutes) {}
