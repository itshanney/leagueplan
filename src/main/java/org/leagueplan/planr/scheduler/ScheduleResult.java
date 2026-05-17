package org.leagueplan.planr.scheduler;

import org.leagueplan.planr.model.ScheduledGame;

import java.util.List;

public sealed interface ScheduleResult permits ScheduleResult.Success, ScheduleResult.Failure {

    record Success(List<ScheduledGame> games, boolean optimal) implements ScheduleResult {}

    record Failure(String message) implements ScheduleResult {}

    static Success success(List<ScheduledGame> games, boolean optimal) {
        return new Success(games, optimal);
    }

    static Failure failure(String message) {
        return new Failure(message);
    }
}
