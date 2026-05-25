package org.leagueplan.planr.model;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record DayOfWeekWindow(DayOfWeek day, LocalTime openStart, LocalTime openEnd) {}
