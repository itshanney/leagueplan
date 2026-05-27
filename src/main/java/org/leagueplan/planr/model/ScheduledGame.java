package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record ScheduledGame(
    UUID id,
    LocalDate date,
    LocalTime startTime,
    UUID fieldId,
    String fieldName,
    UUID homeTeamId,
    String homeTeamName,
    UUID awayTeamId,
    String awayTeamName,
    UUID divisionId,
    String divisionName,
    int gameDurationMinutes,
    boolean overridden) {
  public ScheduledGame withOverride(
      LocalDate newDate,
      LocalTime newStartTime,
      UUID newFieldId,
      String newFieldName,
      UUID newHomeTeamId,
      String newHomeTeamName,
      UUID newAwayTeamId,
      String newAwayTeamName) {
    return new ScheduledGame(
        id,
        newDate != null ? newDate : date,
        newStartTime != null ? newStartTime : startTime,
        newFieldId != null ? newFieldId : fieldId,
        newFieldName != null ? newFieldName : fieldName,
        newHomeTeamId != null ? newHomeTeamId : homeTeamId,
        newHomeTeamName != null ? newHomeTeamName : homeTeamName,
        newAwayTeamId != null ? newAwayTeamId : awayTeamId,
        newAwayTeamName != null ? newAwayTeamName : awayTeamName,
        divisionId,
        divisionName,
        gameDurationMinutes,
        true);
  }
}
