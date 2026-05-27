package org.leagueplan.planr.model;

import java.util.List;
import java.util.UUID;

public record PracticeSchedule(UUID divisionId, PracticeState state, List<PracticeSlot> slots) {
  public PracticeSchedule {
    slots = (slots == null) ? List.of() : slots;
  }

  public PracticeSchedule withSlots(List<PracticeSlot> newSlots) {
    return new PracticeSchedule(divisionId, state, newSlots);
  }

  public PracticeSchedule withState(PracticeState newState) {
    return new PracticeSchedule(divisionId, newState, slots);
  }
}
