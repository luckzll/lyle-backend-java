package com.example.aiapp.mission.application;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MissionScheduleCalculator {

    public LocalDateTime nextFire(
            String type,
            Map<String, Object> config,
            String timezone,
            LocalDateTime afterUtc
    ) {
        ZoneId zone = zone(timezone);
        ZonedDateTime after = afterUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone);
        String normalizedType = text(type).toUpperCase(Locale.ROOT);
        if ("PROJECT_STATE".equals(normalizedType) || "WEBHOOK_ALLOWLIST".equals(normalizedType)) {
            return null;
        }
        int intervalMinutes = integer(config.get("intervalMinutes"), 0);
        if (intervalMinutes > 0) {
            if (intervalMinutes < 5 || intervalMinutes > 43_200) {
                throw new IllegalArgumentException("intervalMinutes 只支持 5～43200。");
            }
            return utc(after.plusMinutes(intervalMinutes).withSecond(0).withNano(0));
        }
        LocalTime time = parseTime(textOr(config.get("time"), "09:00"));
        String frequency = textOr(config.get("frequency"), "DAILY").toUpperCase(Locale.ROOT);
        ZonedDateTime candidate = ZonedDateTime.of(after.toLocalDate(), time, zone);
        if (!candidate.isAfter(after)) candidate = candidate.plusDays(1);
        candidate = switch (frequency) {
            case "WEEKDAYS" -> nextAllowedDay(candidate, List.of(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            ));
            case "WEEKLY" -> nextAllowedDay(
                    candidate,
                    List.of(parseDay(textOr(config.get("dayOfWeek"), "MONDAY")))
            );
            case "DAILY" -> candidate;
            default -> throw new IllegalArgumentException("频率只支持 DAILY、WEEKDAYS 或 WEEKLY。");
        };
        return utc(candidate);
    }

    public void validate(String type, Map<String, Object> config, String timezone, String misfirePolicy) {
        String normalizedType = text(type).toUpperCase(Locale.ROOT);
        if (!List.of("FIXED_TIME", "CRON_SUBSET", "PROJECT_STATE").contains(normalizedType)) {
            throw new IllegalArgumentException("首版触发类型只支持 FIXED_TIME、CRON_SUBSET 或 PROJECT_STATE。");
        }
        zone(timezone);
        String misfire = text(misfirePolicy).toUpperCase(Locale.ROOT);
        if (!List.of("SKIP", "RUN_ONCE").contains(misfire)) {
            throw new IllegalArgumentException("错过触发策略只支持 SKIP 或 RUN_ONCE。");
        }
        if ("PROJECT_STATE".equals(normalizedType)) {
            String event = text(config.get("event"));
            if (!List.of("project_stale", "task_due", "test_status_changed", "connection_recovered").contains(event)) {
                throw new IllegalArgumentException("项目状态事件不在首版白名单中。");
            }
            return;
        }
        nextFire(normalizedType, config, timezone, LocalDateTime.now(ZoneOffset.UTC));
    }

    private static ZonedDateTime nextAllowedDay(
            ZonedDateTime candidate,
            List<DayOfWeek> allowed
    ) {
        ZonedDateTime value = candidate;
        for (int index = 0; index < 8 && !allowed.contains(value.getDayOfWeek()); index++) {
            value = value.plusDays(1);
        }
        return value;
    }

    private static DayOfWeek parseDay(String value) {
        try {
            return DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exc) {
            throw new IllegalArgumentException("dayOfWeek 必须是 MONDAY～SUNDAY。", exc);
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("触发时间必须使用 HH:mm 格式。", exc);
        }
    }

    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(textOr(timezone, "Asia/Shanghai"));
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("timezone 必须是有效 IANA 时区。", exc);
        }
    }

    private static LocalDateTime utc(ZonedDateTime value) {
        return value.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? fallback : result;
    }
}
