package com.hnp.filemanagement.report;

import com.hnp.filemanagement.shared.util.JalaliDate;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.Locale;

/**
 * How the reports show a value (roadmap 12.9) - {@code ${@reportFormat...}} in the template - and how
 * their CSV writes it. On the page numbers are in Persian digits with Persian separators, a size in
 * the largest unit that keeps it above one, a month by its name; in a CSV a number is a number a
 * spreadsheet can sum (bytes as bytes), and a date or a month in Latin digits.
 */
@Component("reportFormat")
public class ReportFormat {

    private static final Locale PERSIAN = Locale.forLanguageTag("fa-IR");
    static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private final UiMessages messages;
    private final JalaliDate jalali;

    public ReportFormat(UiMessages messages, JalaliDate jalali) {
        this.messages = messages;
        this.jalali = jalali;
    }

    /** A sentence of {@code messages.properties}, its arguments already formatted. */
    public String label(String code, Object... arguments) {
        return messages.get(code, arguments);
    }

    /** {@code ۱۲٬۳۴۵}. */
    public String count(Object value) {
        return value == null ? "" : NumberFormat.getIntegerInstance(PERSIAN).format(((Number) value).longValue());
    }

    /** {@code ۱٫۲ گیگابایت}. */
    public String bytes(Object value) {
        if (value == null) {
            return "";
        }
        double amount = ((Number) value).doubleValue();
        int unit = 0;
        while (Math.abs(amount) >= 1024 && unit < UNITS.length - 1) {
            amount /= 1024;
            unit++;
        }
        NumberFormat format = NumberFormat.getNumberInstance(PERSIAN);
        format.setMaximumFractionDigits(unit == 0 ? 0 : 1);
        String unitKey = "report.unit." + UNITS[unit];
        return format.format(amount) + " " + messages.get(unitKey);
    }

    /** {@code ۴۵٫۲٪}. */
    public String percent(Object value) {
        if (value == null) {
            return "";
        }
        NumberFormat format = NumberFormat.getNumberInstance(PERSIAN);
        format.setMaximumFractionDigits(1);
        return messages.get("report.percent", format.format(((Number) value).doubleValue()));
    }

    /** {@code مهر ۱۴۰۵}. */
    public String month(Object value) {
        if (!(value instanceof JalaliDate.JalaliMonth month)) {
            return "";
        }
        String monthKey = "jalali.month." + month.month();
        return messages.get(monthKey) + " " + NumberFormat.getIntegerInstance(PERSIAN).format(month.year())
                .replace(String.valueOf(new DecimalFormatSymbols(PERSIAN).getGroupingSeparator()), "");
    }

    /** {@code ۱۴۰۵/۰۷/۲۱ ۱۰:۲۰}. */
    public String date(Object value) {
        return value instanceof Instant instant ? jalali.format(instant) : "";
    }

    /** A value as the page shows it, by its column's kind - a folder and a file are links the template draws. */
    public String shown(ReportView.Kind kind, Object value) {
        if (value == null) {
            return "";
        }
        return switch (kind) {
            case TEXT -> String.valueOf(value);
            case LABEL -> messages.get(String.valueOf(value));
            case COUNT -> count(value);
            case BYTES -> bytes(value);
            case PERCENT -> percent(value);
            case MONTH -> month(value);
            case DATE -> date(value);
            case FOLDER -> ((ReportView.FolderCell) value).label();
            case FILE -> ((ReportView.FileCell) value).name();
        };
    }

    /** A value as a CSV writes it: a number a spreadsheet can sum, a date and a month in Latin digits. */
    public String written(ReportView.Kind kind, Object value) {
        if (value == null) {
            return "";
        }
        return switch (kind) {
            case COUNT, BYTES -> String.valueOf(((Number) value).longValue());
            case PERCENT -> new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(((Number) value).doubleValue());
            case MONTH -> ((JalaliDate.JalaliMonth) value).iso();
            case DATE -> latinDigits(jalali.format((Instant) value));
            case FOLDER -> ((ReportView.FolderCell) value).label();
            case FILE -> {
                ReportView.FileCell file = (ReportView.FileCell) value;
                yield file.trail() == null || file.trail().isEmpty() ? file.name() : file.trail() + " / " + file.name();
            }
            case TEXT -> String.valueOf(value);
            case LABEL -> messages.get(String.valueOf(value));
        };
    }

    static String latinDigits(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            out.append(c >= '۰' && c <= '۹' ? (char) ('0' + (c - '۰')) : c);
        }
        return out.toString();
    }
}
