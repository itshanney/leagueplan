package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.util.UUID;

public record FieldDivisionLock(UUID divisionId, LocalDate startDate, LocalDate endDate) {}
