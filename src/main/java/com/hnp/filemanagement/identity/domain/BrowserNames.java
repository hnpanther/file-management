package com.hnp.filemanagement.identity.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A browser's description of itself ({@code User-Agent}) as a person reads it - "Chrome 141 · Windows" -
 * for the page of who is signed in (2.16.0). A guess, as every reading of that header is: the page shows
 * the header itself beside it. Only the families people here use are named; anything else is its first
 * word.
 */
public final class BrowserNames {

    /** Checked in this order: Edge and Opera say "Chrome" too, and Chrome says "Safari". */
    private static final String[][] BROWSERS = {
            {"Edg/", "Edge"}, {"Edge/", "Edge"}, {"OPR/", "Opera"}, {"Firefox/", "Firefox"},
            {"Chrome/", "Chrome"}, {"Version/", "Safari"},
    };

    private static final Pattern FIRST_WORD = Pattern.compile("^([A-Za-z][A-Za-z0-9._-]*)");

    private BrowserNames() {
    }

    /** "Chrome 141 · Windows", "Firefox 140", a first word, or {@code null} for no header. */
    public static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String browser = null;
        for (String[] candidate : BROWSERS) {
            int at = userAgent.indexOf(candidate[0]);
            if (at >= 0 && (!candidate[1].equals("Safari") || userAgent.contains("Safari/"))) {
                browser = candidate[1] + majorVersionAt(userAgent, at + candidate[0].length());
                break;
            }
        }
        if (browser == null) {
            Matcher first = FIRST_WORD.matcher(userAgent.strip());
            browser = first.find() ? first.group(1) : "?";
        }
        String system = systemOf(userAgent);
        return system == null ? browser : browser + " · " + system;
    }

    private static String majorVersionAt(String userAgent, int from) {
        int end = from;
        while (end < userAgent.length() && Character.isDigit(userAgent.charAt(end))) {
            end++;
        }
        return end == from ? "" : " " + userAgent.substring(from, end);
    }

    private static String systemOf(String userAgent) {
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return null;
    }
}
