package org.leagueplan.planr.scheduler;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record Slot(LocalDate date, UUID fieldId, String fieldName, LocalTime startTime) {
  int startMinutes() {
    return startTime.getHour() * 60 + startTime.getMinute();
  }
}
