package org.apache.seatunnel.web.core.time;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the maximum time between two executions of a batch schedule.
 *
 * <p>The value is used as the bounded incremental window. Keeping this logic
 * independent from Quartz means preview/build and runtime use the same result.</p>
 */
public final class IncrementalScheduleIntervalResolver {

    public static final int DEFAULT_WINDOW_SECONDS = 1800;

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE;
    private static final int SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR;
    private static final int SECONDS_PER_WEEK = 7 * SECONDS_PER_DAY;

    private IncrementalScheduleIntervalResolver() {
    }

    public static int resolveSeconds(JobScheduleConfig schedule) {
        int fallback = fallbackWindow(schedule);
        if (schedule == null || StringUtils.isBlank(schedule.getScheduleType())) {
            return fallback;
        }

        String scheduleType = schedule.getScheduleType().trim().toLowerCase(Locale.ROOT);
        int resolved;
        switch (scheduleType) {
            case "minute":
                resolved = positiveInt(value(schedule.getMinuteValue(), "intervalMinute"), 5)
                        * SECONDS_PER_MINUTE;
                break;
            case "hour":
                resolved = resolveHourlySeconds(schedule);
                break;
            case "day":
                resolved = SECONDS_PER_DAY;
                break;
            case "week":
                resolved = resolveWeeklySeconds(schedule.getWeeklyValue());
                break;
            default:
                resolved = fallback;
                break;
        }
        return resolved > 0 ? resolved : fallback;
    }

    private static int resolveHourlySeconds(JobScheduleConfig schedule) {
        String hourMode = StringUtils.defaultIfBlank(schedule.getHourMode(), "range")
                .trim().toLowerCase(Locale.ROOT);
        if ("appoint".equals(hourMode)) {
            return maxCyclicGap(
                    numbers(schedule.getHourlyAppointValue(), "hours"),
                    24
            ) * SECONDS_PER_HOUR;
        }

        Map<String, Object> range = schedule.getHourlyRangeValue();
        int startHour = parseHour(value(range, "startTime"));
        int endHour = parseHour(value(range, "endTime"));
        int intervalHour = positiveInt(value(range, "intervalHour"), 1);
        if (endHour < startHour) {
            return SECONDS_PER_DAY;
        }

        List<Integer> executionHours = new ArrayList<>();
        for (int hour = startHour; hour <= endHour; hour += intervalHour) {
            executionHours.add(hour);
            if (hour > Integer.MAX_VALUE - intervalHour) {
                break;
            }
        }
        if (executionHours.isEmpty()) {
            return SECONDS_PER_DAY;
        }
        return maxCyclicGap(executionHours, 24) * SECONDS_PER_HOUR;
    }

    private static int resolveWeeklySeconds(Map<String, Object> weeklyValue) {
        List<Integer> weekdays = new ArrayList<>();
        for (Object rawDay : values(weeklyValue, "weekdays")) {
            int day = weekday(rawDay);
            if (day >= 0 && !weekdays.contains(day)) {
                weekdays.add(day);
            }
        }
        if (weekdays.isEmpty()) {
            return SECONDS_PER_WEEK;
        }
        return maxCyclicGap(weekdays, 7) * SECONDS_PER_DAY;
    }

    private static int maxCyclicGap(List<Integer> values, int cycle) {
        if (values == null || values.isEmpty()) {
            return cycle;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int maxGap = 0;
        for (int index = 1; index < sorted.size(); index++) {
            maxGap = Math.max(maxGap, sorted.get(index) - sorted.get(index - 1));
        }
        maxGap = Math.max(maxGap, cycle - sorted.get(sorted.size() - 1) + sorted.get(0));
        return maxGap > 0 ? maxGap : cycle;
    }

    private static int fallbackWindow(JobScheduleConfig schedule) {
        if (schedule != null
                && schedule.getIncremental() != null
                && schedule.getIncremental().getMaxWindowSeconds() != null
                && schedule.getIncremental().getMaxWindowSeconds() > 0) {
            return schedule.getIncremental().getMaxWindowSeconds();
        }
        return DEFAULT_WINDOW_SECONDS;
    }

    private static int parseHour(String value) {
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        try {
            int hour = Integer.parseInt(value.trim().split(":")[0]);
            return Math.max(0, Math.min(23, hour));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int weekday(Object rawDay) {
        if (rawDay instanceof Number) {
            int value = ((Number) rawDay).intValue();
            if (value >= 1 && value <= 7) {
                return value - 1;
            }
        }
        String value = rawDay == null ? "" : String.valueOf(rawDay).trim().toUpperCase(Locale.ROOT);
        switch (value) {
            case "MON":
            case "MONDAY":
                return 0;
            case "TUE":
            case "TUESDAY":
                return 1;
            case "WED":
            case "WEDNESDAY":
                return 2;
            case "THU":
            case "THURSDAY":
                return 3;
            case "FRI":
            case "FRIDAY":
                return 4;
            case "SAT":
            case "SATURDAY":
                return 5;
            case "SUN":
            case "SUNDAY":
                return 6;
            default:
                return -1;
        }
    }

    private static List<Integer> numbers(Map<String, Object> map, String key) {
        List<Integer> result = new ArrayList<>();
        for (Object value : values(map, key)) {
            if (value instanceof Number) {
                result.add(((Number) value).intValue());
            } else {
                try {
                    result.add(Integer.parseInt(String.valueOf(value)));
                } catch (RuntimeException ignored) {
                    // Ignore malformed UI values and use the safe fallback.
                }
            }
        }
        return result;
    }

    private static List<Object> values(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) {
            return Collections.emptyList();
        }
        Object raw = map.get(key);
        if (raw instanceof Iterable) {
            List<Object> result = new ArrayList<>();
            for (Object value : (Iterable<?>) raw) {
                result.add(value);
            }
            return result;
        }
        return Collections.singletonList(raw);
    }

    private static String value(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) {
            return "";
        }
        return String.valueOf(map.get(key));
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
