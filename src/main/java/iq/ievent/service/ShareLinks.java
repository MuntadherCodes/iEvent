package iq.ievent.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * One-tap "share this event" links (R43), pre-filled with the event title and its
 * public URL. One place builds them for every surface that offers sharing: the
 * Share button on an organizer's event page and the Marketing page's Social tab.
 *
 * url       the public event page (absolute)
 * whatsapp  wa.me with "title · url" as the message
 * telegram  t.me share with the url and the title as its text
 * facebook  the Facebook sharer for the url
 * x         an X (Twitter) post intent with the title and the url
 */
public record ShareLinks(String url, String whatsapp, String telegram, String facebook, String x) {

    public static ShareLinks of(String title, String url) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanUrl = url == null ? "" : url.trim();
        String encUrl = enc(cleanUrl);
        String encTitle = enc(cleanTitle);
        String encBoth = enc(cleanTitle.isEmpty() ? cleanUrl : cleanTitle + " · " + cleanUrl);
        return new ShareLinks(cleanUrl,
                "https://wa.me/?text=" + encBoth,
                "https://t.me/share/url?url=" + encUrl + "&text=" + encTitle,
                "https://www.facebook.com/sharer/sharer.php?u=" + encUrl,
                "https://twitter.com/intent/tweet?text=" + encTitle + "&url=" + encUrl);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
