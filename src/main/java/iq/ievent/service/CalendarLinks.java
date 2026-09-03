package iq.ievent.service;

import iq.ievent.domain.Event;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * "Add to calendar" deep links (R31 #11). The .ics download stays for Apple
 * Calendar and desktop Outlook; Google Calendar and Outlook.com open a
 * pre-filled event in the browser, which is what most phones expect.
 * Month-only and date-TBA schedules have no real timestamp, so callers must
 * not offer any of these for them (see ExtrasController#eventIcs).
 */
public final class CalendarLinks {

    private static final DateTimeFormatter GOOGLE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter GOOGLE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private CalendarLinks() {}

    public static boolean available(Event e) {
        return e != null && !"TBA".equals(e.getDatePrecision()) && !"MONTH".equals(e.getDatePrecision());
    }

    private static OffsetDateTime end(Event e) {
        return e.getEndsAt() == null ? e.getStartsAt().plusHours(3) : e.getEndsAt();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String location(Event e) {
        if ("ONLINE".equals(e.getLocationType())) return "Online";
        if ("TBA".equals(e.getLocationType())) return e.getCity() == null ? "" : e.getCity();
        return (e.getVenueName() == null ? "" : e.getVenueName() + ", ") + (e.getCity() == null ? "" : e.getCity());
    }

    public static String google(Event e, String eventUrl) {
        String dates;
        if (e.isHasStartTime()) {
            dates = GOOGLE_STAMP.format(e.getStartsAt().withOffsetSameInstant(ZoneOffset.UTC)) + "/"
                    + GOOGLE_STAMP.format(end(e).withOffsetSameInstant(ZoneOffset.UTC));
        } else {
            // all-day: Google wants the day AFTER the last day as the exclusive end
            var startDay = e.getStartsAt().atZoneSameInstant(Format.BAGHDAD).toLocalDate();
            var endDay = (e.getEndsAt() == null ? startDay : e.getEndsAt().atZoneSameInstant(Format.BAGHDAD).toLocalDate()).plusDays(1);
            dates = GOOGLE_DAY.format(startDay) + "/" + GOOGLE_DAY.format(endDay);
        }
        return "https://calendar.google.com/calendar/render?action=TEMPLATE"
                + "&text=" + enc(e.getTitle())
                + "&dates=" + dates
                + "&ctz=Asia/Baghdad"
                + "&details=" + enc("Tickets & details: " + eventUrl)
                + "&location=" + enc(location(e));
    }

    public static String outlook(Event e, String eventUrl) {
        var start = e.getStartsAt().atZoneSameInstant(Format.BAGHDAD).toLocalDateTime();
        var stop = end(e).atZoneSameInstant(Format.BAGHDAD).toLocalDateTime();
        return "https://outlook.live.com/calendar/0/action/compose?rru=addevent&path=%2Fcalendar%2Faction%2Fcompose"
                + "&subject=" + enc(e.getTitle())
                + "&startdt=" + enc(start.toString())
                + "&enddt=" + enc(stop.toString())
                + (e.isHasStartTime() ? "" : "&allday=true")
                + "&body=" + enc("Tickets & details: " + eventUrl)
                + "&location=" + enc(location(e));
    }
}
