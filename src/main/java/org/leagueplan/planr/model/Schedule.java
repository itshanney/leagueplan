package org.leagueplan.planr.model;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

public record Schedule(
    ScheduleStatus status,
    LocalDate seasonStart,
    LocalDate seasonEnd,
    List<ScheduledGame> games
) {
    public Schedule withStatus(ScheduleStatus newStatus) {
        return new Schedule(newStatus, seasonStart, seasonEnd, games);
    }

    public Schedule withGameReplaced(int zeroBasedIndex, ScheduledGame replacement) {
        List<ScheduledGame> updated = Stream.iterate(0, i -> i + 1)
            .limit(games.size())
            .map(i -> i == zeroBasedIndex ? replacement : games.get(i))
            .toList();
        return new Schedule(status, seasonStart, seasonEnd, updated);
    }
}
