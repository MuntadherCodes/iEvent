package iq.ievent.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;

/**
 * Event descriptions are rich text (R13). Hosts author limited HTML in a
 * contenteditable editor; we sanitize on SAVE and again on RENDER (legacy
 * rows and defense in depth). Plain-text rows from before R13 render through
 * the same pipeline via escape + paragraph wrapping.
 */
public final class RichText {

    private static final int MAX_LENGTH = 20_000;

    private static final Safelist SAFELIST = new Safelist()
            .addTags("b", "strong", "i", "em", "u", "s", "p", "br",
                     "ul", "ol", "li", "h3", "h4", "blockquote", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https")
            .addEnforcedAttribute("a", "rel", "noopener nofollow")
            .addEnforcedAttribute("a", "target", "_blank");

    private RichText() { }

    /** Heuristic: was this stored/authored as HTML? */
    public static boolean isHtml(String s) {
        if (s == null) return false;
        String t = s.stripLeading();
        return t.startsWith("<") || t.contains("<p") || t.contains("<ul") || t.contains("<ol")
                || t.contains("<br") || t.contains("<strong") || t.contains("<h3");
    }

    /** Sanitizes host-authored HTML to the allowed subset. */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) return "";
        String limited = html.length() > MAX_LENGTH ? html.substring(0, MAX_LENGTH) : html;
        return unnestHeadings(Jsoup.clean(limited, SAFELIST)).trim();
    }

    /** A heading (h3/h4) that ends up wrapping block-level children — seen
     *  from a contenteditable execCommand('formatBlock') quirk when a host
     *  toggles the "H" button across a selection that already spans several
     *  paragraphs — visually swallows every paragraph after it into bold
     *  heading style, since the heading's bold weight applies to the whole
     *  subtree regardless of any {@code <b>}/{@code <strong>} inside it.
     *  Splits those block children back out as siblings, right after the
     *  heading, so it only ever wraps its own inline text. */
    private static String unnestHeadings(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        Elements headings = doc.body().select("h3, h4");
        for (Element heading : new ArrayList<>(headings)) {
            Elements blockChildren = heading.select("> p, > ul, > ol, > blockquote");
            if (blockChildren.isEmpty()) continue;
            Element insertAfter = heading;
            for (Element child : blockChildren) {
                child.remove();
                insertAfter.after(child);
                insertAfter = child;
            }
        }
        return doc.body().html();
    }

    /** Normalizes a submitted description for storage (sanitized HTML or plain text). */
    public static String forStorage(String submitted) {
        if (submitted == null) return "";
        return isHtml(submitted) ? sanitize(submitted) : submitted.strip();
    }

    /** Re-sanitizes an already-stored HTML value before it's loaded back into
     *  the edit-page's rich-text editor — cheap and idempotent for already-
     *  clean rows, but repairs any row saved before a sanitize-time fix
     *  existed (e.g. unnestHeadings) without needing a data migration.
     *  Legacy plain-text rows pass through untouched, same as forStorage. */
    public static String repairForEdit(String stored) {
        return isHtml(stored) ? sanitize(stored) : (stored == null ? "" : stored);
    }

    /** Safe HTML for rendering: sanitized rich text, or escaped legacy plain text. */
    public static String toDisplayHtml(String stored) {
        if (stored == null || stored.isBlank()) return "";
        if (isHtml(stored)) return sanitize(stored);
        StringBuilder sb = new StringBuilder();
        for (String para : stored.split("\n\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            sb.append("<p>").append(HtmlUtils.htmlEscape(p).replace("\n", "<br>")).append("</p>");
        }
        return sb.toString();
    }
}
