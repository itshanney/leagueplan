package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record PracticeSlot(
    UUID slotId,
    UUID teamId,
    int slotNumber,
    LocalDate assignedDate,
    LocalTime assignedStartTime,
    UUID assignedFieldId) {
  public PracticeSlot withAssignment(LocalDate date, LocalTime startTime, UUID fieldId) {
    return new PracticeSlot(slotId, teamId, slotNumber, date, startTime, fieldId);
  }

  public PracticeSlot withAssignmentCleared() {
    return new PracticeSlot(slotId, teamId, slotNumber, null, null, null);
  }
}
