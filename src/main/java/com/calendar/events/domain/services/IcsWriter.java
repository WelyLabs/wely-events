package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One event as an iCalendar file (RFC 5545), the same as wely-web's {@code calendar-export.ts}:
 * times in UTC, the UID stable per event so a new import replaces the old copy, the organizer's
 * words then the way back to the event in the description.
 */
public final class IcsWriter {

    static final String PRODUCT_ID = "-//WelyLabs//Wely//FR";
    /** The UID's domain: the same event gives the same UID, whatever the environment. */
    static final String UID_DOMAIN = "welylabs.app";
    /** RFC 5545 §3.1: lines longer than 75 octets are folded. */
    static final int MAX_LINE_OCTETS = 75;
    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private IcsWriter() {
    }

    /** The event as an .ics file, {@code now} being when it is exported; {@code pageUrl} may be null. */
    public static String write(Event event, String pageUrl, Instant now) {
        List<String> lines = new ArrayList<>(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:" + PRODUCT_ID,
                "CALSCALE:GREGORIAN",
                "METHOD:PUBLISH",
                "BEGIN:VEVENT",
                "UID:" + event.getId() + "@" + UID_DOMAIN,
                "DTSTAMP:" + utcStamp(now),
                dateLine("DTSTART", event, event.getStartDate()),
                dateLine("DTEND", event, event.effectiveEnd()),
                "SUMMARY:" + escapeText(event.getTitle())));
        if (event.getLocation() != null && !event.getLocation().isBlank()) {
            lines.add("LOCATION:" + escapeText(event.getLocation()));
        }
        String description = descriptionOf(event, pageUrl);
        if (!description.isEmpty()) {
            lines.add("DESCRIPTION:" + escapeText(description));
        }
        if (pageUrl != null) {
            lines.add("URL:" + pageUrl);
        }
        if (event.getCoordinates() != null) {
            lines.add("GEO:" + event.getCoordinates().latitude() + ";" + event.getCoordinates().longitude());
        }
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");
        StringBuilder ics = new StringBuilder();
        lines.forEach(line -> ics.append(foldLine(line)).append(CRLF));
        return ics.toString();
    }

    /** 2026-10-17T18:30:00Z → « 20261017T183000Z ». */
    /** An all-day event takes plain dates (its UTC days, the end exclusive, as in RFC 5545); any other, UTC times. */
    static String dateLine(String property, Event event, Instant instant) {
        return event.isAllDay()
                ? property + ";VALUE=DATE:" + DateTimeFormatter.BASIC_ISO_DATE.format(instant.atZone(ZoneOffset.UTC).toLocalDate())
                : property + ":" + utcStamp(instant);
    }

    static String utcStamp(Instant instant) {
        return UTC_STAMP.format(instant);
    }

    /** A TEXT value: backslash, semicolon, comma and line breaks escaped. */
    static String escapeText(String value) {
        return value
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replaceAll("\r?\n", "\\\\n");
    }

    /** A content line folded every 75 octets, never inside a character. */
    static String foldLine(String line) {
        StringBuilder folded = new StringBuilder();
        int octets = 0;
        boolean firstLine = true;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            // The continuation lines start with a space, which counts.
            int limit = firstLine ? MAX_LINE_OCTETS : MAX_LINE_OCTETS - 1;
            if (octets + size > limit) {
                folded.append(CRLF).append(' ');
                octets = 0;
                firstLine = false;
            }
            folded.append(character);
            octets += size;
            i += Character.charCount(codePoint);
        }
        return folded.toString();
    }

    private static String descriptionOf(Event event, String pageUrl) {
        String words = event.getDescription() == null ? "" : event.getDescription();
        if (pageUrl == null) {
            return words;
        }
        return words.isEmpty() ? pageUrl : words + "\n\n" + pageUrl;
    }
}
