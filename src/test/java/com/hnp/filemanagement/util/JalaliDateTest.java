package com.hnp.filemanagement.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar conversion, against dates whose Jalali equivalent is a matter of record.
 *
 * <p>Nowruz of several years, both ends of a leap Esfand, and the last day of a 31-day month are
 * the cases an off-by-one would show up in; the rest of the year is a straight count from Nowruz.
 */
class JalaliDateTest {

    private final JalaliDate underTest = new JalaliDate();

    @ParameterizedTest(name = "{0} is {1}")
    @DisplayName("known dates convert to what the almanac says")
    @CsvSource({
            "2024-03-20, 1403/01/01",   // Nowruz 1403
            "2025-03-21, 1404/01/01",   // Nowruz 1404
            "2026-03-21, 1405/01/01",   // Nowruz 1405
            "2021-03-21, 1400/01/01",
            "2025-03-20, 1403/12/30",   // 1403 is a leap year: Esfand has 30 days
            "2026-03-20, 1404/12/29",   // 1404 is not
            "2026-09-15, 1405/06/24",
            "2026-09-22, 1405/06/31",   // the six 31-day months
            "2026-09-23, 1405/07/01",
            "1979-02-11, 1357/11/22",
            "2000-01-01, 1378/10/11",
            "2030-12-31, 1409/10/10",
    })
    void knownDates(String gregorian, String jalali) {
        int[] converted = JalaliDate.toJalali(LocalDate.parse(gregorian));

        assertThat(String.format("%04d/%02d/%02d", converted[0], converted[1], converted[2]))
                .isEqualTo(jalali);
    }

    @Test
    @DisplayName("the rendering carries the time, in Persian digits")
    void renderingWithTime() {
        assertThat(underTest.format(LocalDateTime.of(2026, 9, 15, 7, 27, 33)))
                .isEqualTo("۱۴۰۵/۰۶/۲۴ ۰۷:۲۷");
        assertThat(underTest.format(LocalDate.of(2024, 3, 20)))
                .isEqualTo("۱۴۰۳/۰۱/۰۱");
    }

    @Test
    @DisplayName("a missing value renders as nothing rather than failing the page")
    void nullIsEmpty() {
        assertThat(underTest.format((LocalDateTime) null)).isEmpty();
        assertThat(underTest.format((LocalDate) null)).isEmpty();
    }

    /**
     * Every day for a century, round-tripped through the month lengths: the months must be
     * 31/31/31/31/31/31/30/30/30/30/30 and 29 or 30, and consecutive days must be consecutive.
     */
    @Test
    @DisplayName("a century of consecutive days is consecutive in the other calendar too")
    void aCenturyIsContinuous() {
        LocalDate day = LocalDate.of(1990, 1, 1);
        int[] previous = JalaliDate.toJalali(day);
        for (int i = 0; i < 365 * 100; i++) {
            day = day.plusDays(1);
            int[] current = JalaliDate.toJalali(day);
            boolean sameMonthNextDay = current[0] == previous[0] && current[1] == previous[1]
                    && current[2] == previous[2] + 1;
            boolean nextMonth = current[2] == 1 && (
                    (current[0] == previous[0] && current[1] == previous[1] + 1
                            && previous[2] == (previous[1] <= 6 ? 31 : 30))
                            || (current[0] == previous[0] + 1 && current[1] == 1 && previous[1] == 12
                            && (previous[2] == 29 || previous[2] == 30)));
            assertThat(sameMonthNextDay || nextMonth)
                    .as("%s -> %s after %s", day, java.util.Arrays.toString(current), java.util.Arrays.toString(previous))
                    .isTrue();
            previous = current;
        }
    }
}
