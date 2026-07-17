package com.example.aiapp.mission;

import com.example.aiapp.mission.application.MissionScheduleCalculator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MissionScheduleCalculatorTest {

    private final MissionScheduleCalculator calculator = new MissionScheduleCalculator();

    @Test
    void fixedTimeUsesIanaTimezoneAndStoresUtc() {
        LocalDateTime next = calculator.nextFire(
                "FIXED_TIME",
                Map.of("frequency", "DAILY", "time", "09:00"),
                "Asia/Shanghai",
                LocalDateTime.of(2026, 7, 16, 0, 30)
        );

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 16, 1, 0));
    }

    @Test
    void weeklyAndWeekdaySchedulesSkipDisallowedDays() {
        LocalDateTime weekly = calculator.nextFire(
                "FIXED_TIME",
                Map.of("frequency", "WEEKLY", "dayOfWeek", "MONDAY", "time", "09:00"),
                "Asia/Shanghai",
                LocalDateTime.of(2026, 7, 17, 2, 0)
        );
        LocalDateTime weekday = calculator.nextFire(
                "CRON_SUBSET",
                Map.of("frequency", "WEEKDAYS", "time", "09:00"),
                "Asia/Shanghai",
                LocalDateTime.of(2026, 7, 17, 2, 0)
        );

        assertThat(weekly).isEqualTo(LocalDateTime.of(2026, 7, 20, 1, 0));
        assertThat(weekday).isEqualTo(LocalDateTime.of(2026, 7, 20, 1, 0));
    }

    @Test
    void projectStateHasNoClockFireAndRejectsUnknownEvents() {
        assertThat(calculator.nextFire(
                "PROJECT_STATE",
                Map.of("event", "task_due"),
                "Asia/Shanghai",
                LocalDateTime.of(2026, 7, 16, 0, 0)
        )).isNull();

        assertThatThrownBy(() -> calculator.validate(
                "PROJECT_STATE",
                Map.of("event", "delete_everything"),
                "Asia/Shanghai",
                "RUN_ONCE"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
