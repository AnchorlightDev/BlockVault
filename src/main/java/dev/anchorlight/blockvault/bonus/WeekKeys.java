package dev.anchorlight.blockvault.bonus;

import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;

/**
 * The identity of a payout week: the ISO week-based year and week number in the
 * configured timezone, e.g. {@code 2026-W46}. Computed with {@link WeekFields#ISO}
 * so it is locale-independent (Monday-start, min 4 days), and so the week-based
 * year is correct across the new-year boundary. This string is the primary key
 * that makes a payout idempotent.
 */
public final class WeekKeys {

    private WeekKeys() {}

    public static String of(ZonedDateTime when) {
        int year = when.get(WeekFields.ISO.weekBasedYear());
        int week = when.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("%04d-W%02d", year, week);
    }

    /** The key of the week after {@code when} - next week's featured block lands here. */
    public static String next(ZonedDateTime when) {
        return of(when.plusWeeks(1));
    }
}
