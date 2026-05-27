package org.leagueplan.planr.scheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface PracticeScheduleResult
    permits PracticeScheduleResult.Success, PracticeScheduleResult.Failure {

  record Success(
      Map<UUID, Slot> assignmentsBySlotId, boolean optimal, List<DivisionSummary> divisionSummaries)
      implements PracticeScheduleResult {}

  record Failure(String message) implements PracticeScheduleResult {}

  static Success success(
      Map<UUID, Slot> assignmentsBySlotId,
      boolean optimal,
      List<DivisionSummary> divisionSummaries) {
    return new Success(assignmentsBySlotId, optimal, divisionSummaries);
  }

  static Failure failure(String message) {
    return new Failure(message);
  }
}
