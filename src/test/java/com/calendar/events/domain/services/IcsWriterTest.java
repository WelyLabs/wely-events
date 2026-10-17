package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoPoint;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IcsWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final Instant START = Instant.parse("2026-10-24T17:30:00Z");
    private static final String PAGE = "https://web.welylabs.app/event/e1";

    private static Event.EventBuilder event() {
        return Event.builder().id("e1").title("Apéro chez Léa").organizerId("org")
                .startDate(START).endDate(Instant.parse("2026-10-24T21:00:00Z"))
                .location("Lyon").description("");
    }

    /** The content lines back from their 75-octet folds. */
    private static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }

    @Test
    void write_shouldWriteOneEventInUtc() {
        String ics = unfold(IcsWriter.write(event().description("Ramène des chips, et du pain").build(), PAGE, NOW));

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//WelyLabs//Wely//FR\r\n");
        assertThat(ics).endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n");
        assertThat(ics).contains("\r\nUID:e1@welylabs.app\r\n");
        assertThat(ics).contains("\r\nDTSTAMP:20261017T100000Z\r\n");
        assertThat(ics).contains("\r\nDTSTART:20261024T173000Z\r\n");
        assertThat(ics).contains("\r\nDTEND:20261024T210000Z\r\n");
        assertThat(ics).contains("\r\nSUMMARY:Apéro chez Léa\r\n");
        assertThat(ics).contains("\r\nLOCATION:Lyon\r\n");
        assertThat(ics).contains("\r\nDESCRIPTION:Ramène des chips\\, et du pain\\n\\n" + PAGE + "\r\n");
        assertThat(ics).contains("\r\nURL:" + PAGE + "\r\n");
        assertThat(ics).doesNotContain("GEO:");
    }

    @Test
    void write_shouldLastTheDefaultHour_withoutAnEnd() {
        String ics = IcsWriter.write(event().endDate(null).build(), PAGE, NOW);

        assertThat(ics).contains("\r\nDTEND:20261024T183000Z\r\n");
    }

    @Test
    void write_shouldLinkBackToThePage_withoutDescription() {
        String ics = unfold(IcsWriter.write(event().build(), PAGE, NOW));

        assertThat(ics).contains("\r\nDESCRIPTION:" + PAGE + "\r\n");
    }

    @Test
    void write_shouldLeaveOutWhatItDoesNotKnow() {
        String ics = IcsWriter.write(event().location(" ").description(null).build(), null, NOW);

        assertThat(ics).doesNotContain("LOCATION:", "DESCRIPTION:", "URL:");
    }

    @Test
    void write_shouldPlaceAPickedLocation() {
        String ics = IcsWriter.write(event().coordinates(new GeoPoint(45.764, 4.8357)).build(), PAGE, NOW);

        assertThat(ics).contains("\r\nGEO:45.764;4.8357\r\n");
    }

    @Test
    void utcStamp_shouldWriteTheBasicUtcForm() {
        assertThat(IcsWriter.utcStamp(Instant.parse("2026-10-17T08:05:09.123Z"))).isEqualTo("20261017T080509Z");
    }

    @Test
    void escapeText_shouldEscapeWhatTextValuesReserve() {
        assertThat(IcsWriter.escapeText("a\\b;c,d\ne\r\nf")).isEqualTo("a\\\\b\\;c\\,d\\ne\\nf");
    }

    @Test
    void foldLine_shouldLeaveShortLinesAlone() {
        assertThat(IcsWriter.foldLine("SUMMARY:Apéro")).isEqualTo("SUMMARY:Apéro");
    }

    @Test
    void foldLine_shouldFoldEvery75OctetsWithoutSplittingACharacter() {
        String line = "DESCRIPTION:" + "é".repeat(100) + "🎉";

        String[] lines = IcsWriter.foldLine(line).split("\r\n");

        assertThat(lines).hasSizeGreaterThan(1);
        assertThat(lines).allSatisfy(part -> assertThat(part.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75));
        assertThat(Arrays.stream(lines).skip(1)).allSatisfy(part -> assertThat(part).startsWith(" "));
        assertThat(lines[0] + Arrays.stream(lines).skip(1).map(part -> part.substring(1)).collect(Collectors.joining()))
                .isEqualTo(line);
    }

    @Test
    void write_shouldGiveAnAllDayEventPlainDates_theEndExclusive() {
        String ics = IcsWriter.write(event().allDay(true).startDate(java.time.Instant.parse("2026-10-24T00:00:00Z"))
                .endDate(java.time.Instant.parse("2026-10-26T00:00:00Z")).build(), PAGE, NOW);

        assertThat(ics).contains("\r\nDTSTART;VALUE=DATE:20261024\r\n");
        assertThat(ics).contains("\r\nDTEND;VALUE=DATE:20261026\r\n");
    }

    @Test
    void dateLine_shouldWriteAUtcTime_forAnEventWithTimes() {
        assertThat(IcsWriter.dateLine("DTSTART", event().build(), java.time.Instant.parse("2026-10-24T17:30:00Z")))
                .isEqualTo("DTSTART:20261024T173000Z");
    }
}
