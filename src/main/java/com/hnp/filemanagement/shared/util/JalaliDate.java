package com.hnp.filemanagement.shared.util;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Gregorian to Jalali (Solar Hijri) conversion, for the dates the pages show.
 *
 * <p>The database and every DTO stay Gregorian; only the rendering changes. The templates reach
 * this as {@code ${@jalali.format(...)}}, so a page that shows a date says which calendar it is in
 * at the point of use rather than through a converter registered somewhere else.
 *
 * <p>The arithmetic is the algorithm of <i>jalaali-js</i> (Behrang Noruzi Niya, based on Kazimierz
 * Borkowski's 33-year-cycle breaks), which is exact for the years 1178 to 3177 AP — it does not
 * use the 2820-year rule, which drifts from the observed Nowruz in this century. The explorer page
 * gets the same result from the browser's {@code Intl.DateTimeFormat("fa-IR")}; this exists for
 * the server-rendered pages, which have no browser to ask.
 */
@Component("jalali")
public class JalaliDate {

    private static final int[] BREAKS = {-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
            1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178};

    private static final char[] PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹".toCharArray();

    /** {@code ۱۴۰۵/۰۶/۲۴ ۰۷:۲۷}, or an empty string for a missing value. */
    public String format(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return persianDigits(String.format("%s %02d:%02d",
                date(value.toLocalDate()), value.getHour(), value.getMinute()));
    }

    /** {@code ۱۴۰۵/۰۶/۲۴}, or an empty string for a missing value. */
    public String format(LocalDate value) {
        return value == null ? "" : persianDigits(date(value));
    }

    private static String date(LocalDate value) {
        int[] jalali = toJalali(value);
        return String.format("%04d/%02d/%02d", jalali[0], jalali[1], jalali[2]);
    }

    // ---------------------------------------------------------------- the arithmetic

    /** {@code {year, month, day}} in the Jalali calendar. */
    static int[] toJalali(LocalDate value) {
        int jdn = toJulianDayNumber(value.getYear(), value.getMonthValue(), value.getDayOfMonth());
        int gy = fromJulianDayNumber(jdn)[0];
        int jy = gy - 621;
        int[] cal = jalaliCalendar(jy);
        int leap = cal[0];
        int march = cal[2];

        int k = jdn - toJulianDayNumber(gy, 3, march);
        int jm;
        int jd;
        if (k >= 0) {
            if (k <= 185) {
                jm = 1 + k / 31;
                jd = k % 31 + 1;
                return new int[]{jy, jm, jd};
            }
            k -= 186;
        } else {
            jy -= 1;
            k += 179;
            if (leap == 1) {
                k += 1;
            }
        }
        jm = 7 + k / 30;
        jd = k % 30 + 1;
        return new int[]{jy, jm, jd};
    }

    /** {@code {leap, gregorianYear, marchDay}}: the Gregorian day in March on which this Jalali year starts. */
    private static int[] jalaliCalendar(int jy) {
        int gy = jy + 621;
        int leapJ = -14;
        int jp = BREAKS[0];
        if (jy < jp || jy >= BREAKS[BREAKS.length - 1]) {
            throw new IllegalArgumentException("Jalali year out of range: " + jy);
        }
        int jump = 0;
        for (int i = 1; i < BREAKS.length; i++) {
            int jm = BREAKS[i];
            jump = jm - jp;
            if (jy < jm) {
                break;
            }
            leapJ += (jump / 33) * 8 + (jump % 33) / 4;
            jp = jm;
        }
        int n = jy - jp;
        leapJ += (n / 33) * 8 + (n % 33 + 3) / 4;
        if (jump % 33 == 4 && jump - n == 4) {
            leapJ += 1;
        }
        int leapG = gy / 4 - ((gy / 100 + 1) * 3) / 4 - 150;
        int march = 20 + leapJ - leapG;

        if (jump - n < 6) {
            n = n - jump + ((jump + 4) / 33) * 33;
        }
        int leap = ((n + 1) % 33 - 1) % 4;
        if (leap == -1) {
            leap = 4;
        }
        return new int[]{leap, gy, march};
    }

    private static int toJulianDayNumber(int gy, int gm, int gd) {
        int d = ((gy + (gm - 8) / 6 + 100100) * 1461) / 4 + (153 * ((gm + 9) % 12) + 2) / 5 + gd - 34840408;
        return d - (((gy + 100100 + (gm - 8) / 6) / 100) * 3) / 4 + 752;
    }

    private static int[] fromJulianDayNumber(int jdn) {
        int j = 4 * jdn + 139361631;
        j = j + (((4 * jdn + 183187720) / 146097) * 3) / 4 * 4 - 3908;
        int i = ((j % 1461) / 4) * 5 + 308;
        int gd = (i % 153) / 5 + 1;
        int gm = (i / 153) % 12 + 1;
        int gy = j / 1461 - 100100 + (8 - gm) / 6;
        return new int[]{gy, gm, gd};
    }

    private static String persianDigits(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(c >= '0' && c <= '9' ? PERSIAN_DIGITS[c - '0'] : c);
        }
        return out.toString();
    }
}
