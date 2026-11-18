package iq.ievent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * R44: emails the super-admin writes from the console to an organizer, a buyer or any
 * other address. A new message, not an answer to a contact-form message (that is
 * InquiryService.reply). AdminController shows the form, calls send() and lists what
 * went out; the rows live in `admin_emails` (V30).
 *
 * Like a reply, an email is recorded only after the mail server accepted it, so the
 * list never shows something that was not sent. The person's own answer arrives in the
 * support mailbox (Reply-To), not in the console.
 */
@Service
public class AdminEmailService {

    private static final Logger log = LoggerFactory.getLogger(AdminEmailService.class);

    /** Rows per page on the admin list. */
    public static final int PAGE_SIZE = 50;
    /** Most addresses one email may go to. More than that is a mailing, which this is not. */
    public static final int MAX_RECIPIENTS = 10;
    public static final int MAX_SUBJECT = 150;
    /** Longest message the console sends (the same limit as a reply). */
    public static final int MAX_BODY = 5000;
    private static final int MAX_NAME = 120;

    private static final String SELECT =
            "SELECT e.id, e.to_email, e.to_name, e.subject, e.body, e.lang, e.sent_at FROM admin_emails e";

    /** What may stand between two addresses in the To box: commas and semicolons (the Latin
     *  and the Arabic ones), any white space including the non-breaking kinds, and the
     *  invisible marks that come along when an address is pasted from a chat or a page. */
    private static final java.util.regex.Pattern BETWEEN_ADDRESSES = java.util.regex.Pattern.compile(
            "[,;\\u060C\\u061B\\s\\u00A0\\u2007\\u202F\\u200B\\u200E\\u200F\\u2028\\u2029\\uFEFF]+");

    private final JdbcTemplate jdbc;
    private final MailService mail;

    public AdminEmailService(JdbcTemplate jdbc, MailService mail) {
        this.jdbc = jdbc;
        this.mail = mail;
    }

    /**
     * One email that went out, ready for the admin pages (which are English only).
     *
     * toName     the name used in the greeting ("" when there was none)
     * excerpt    the message on one line, cut to fit a table cell
     * langLabel  the language the frame of the mail was laid out in
     * sentLine   when it went out, Baghdad time ("Nov 18, 2026, 3:20 PM")
     */
    public record Sent(long id, String toEmail, String toName, String subject, String body,
                       String excerpt, String langLabel, String sentLine) {}

    /** One page of the list. number is zero-based; from and to are the one-based positions
     *  of the first and last row shown (both 0 when nothing matches). */
    public record Listing(List<Sent> rows, int number, int pages, long total, long from, long to) {}

    /** Why nothing was sent: something about what the admin typed. */
    public enum Problem {
        NO_RECIPIENT,
        /** One of the addresses cannot be mailed to (Outcome.badAddress names it). */
        BAD_ADDRESS,
        TOO_MANY,
        NO_SUBJECT,
        SUBJECT_TOO_LONG,
        EMPTY,
        TOO_LONG
    }

    /**
     * What happened to an email the admin tried to send.
     *
     * problem      set when the form was refused; nothing was sent then
     * badAddress   the entry that is not an email address (BAD_ADDRESS only)
     * sent         the addresses the mail server accepted, in the order typed
     * unsent       the addresses it did not go to: the one the mail server refused first,
     *              then the ones that were not tried after it. Empty when all went out.
     * notRecorded  how many of the sent ones could not be written to the list
     */
    public record Outcome(Problem problem, String badAddress, List<String> sent, List<String> unsent,
                          int notRecorded) {
        static Outcome refused(Problem problem, String badAddress) {
            return new Outcome(problem, badAddress, List.of(), List.of(), 0);
        }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Sends one email to every address in `to`, each person getting their own copy (nobody
     * sees the other addresses). The mail goes first and synchronously; a copy is recorded
     * only when the mail server accepted it.
     *
     * to       one address, or several separated by commas, semicolons, spaces or new lines
     * name     optional: greets the person by their first name ("Hello Ali,"). Used only
     *          when the email goes to ONE address; several people get a plain greeting.
     * subject  as typed, on one line
     * text     the message, sent as typed (line breaks kept, outer blank space dropped)
     *
     * When the mail server refuses an address the rest are not tried: a server that is
     * down would otherwise be waited on once per address. Outcome.unsent lists them.
     */
    public Outcome send(String to, String name, String subject, String text) {
        List<String> addresses = new ArrayList<>();
        String bad = parseRecipients(to, addresses);
        if (bad != null) return Outcome.refused(Problem.BAD_ADDRESS, bad);
        if (addresses.isEmpty()) return Outcome.refused(Problem.NO_RECIPIENT, null);
        if (addresses.size() > MAX_RECIPIENTS) return Outcome.refused(Problem.TOO_MANY, null);

        String cleanSubject = oneLine(subject);
        if (cleanSubject.isEmpty()) return Outcome.refused(Problem.NO_SUBJECT, null);
        if (cleanSubject.length() > MAX_SUBJECT) return Outcome.refused(Problem.SUBJECT_TOO_LONG, null);
        // a browser submits every line break of the box as CR LF but counts it as one
        // character against the box's own limit: measure and keep it the same way
        // (and a NUL character can be neither mailed nor stored, so it is dropped)
        String body = text == null ? ""
                : text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "").strip();
        if (body.isEmpty()) return Outcome.refused(Problem.EMPTY, null);
        if (body.length() > MAX_BODY) return Outcome.refused(Problem.TOO_LONG, null);

        String lang = frameLang(cleanSubject, body);
        String greetName = addresses.size() == 1 ? clip(oneLine(name), MAX_NAME) : "";

        List<String> sent = new ArrayList<>();
        List<String> unsent = new ArrayList<>();
        int notRecorded = 0;
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            if (!mail.sendAdminMessage(address, greetName, cleanSubject, body, Locale.forLanguageTag(lang))) {
                unsent.addAll(addresses.subList(i, addresses.size()));
                break;
            }
            sent.add(address);
            try {
                jdbc.update("""
                        INSERT INTO admin_emails (to_email, to_name, subject, body, lang)
                        VALUES (?, ?, ?, ?, ?)
                        """, address, greetName.isEmpty() ? null : greetName, cleanSubject, body, lang);
            } catch (RuntimeException e) {
                log.error("Email to {} went out but could not be recorded", address, e);
                notRecorded++;
            }
        }
        return new Outcome(null, null, List.copyOf(sent), List.copyOf(unsent), notRecorded);
    }

    // ------------------------------------------------------------------
    // Reading (admin console)
    // ------------------------------------------------------------------

    /** How many emails were sent from the console in all. */
    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM admin_emails", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * One page of sent emails, newest first.
     *
     * q     matched against the address, the name, the subject and the message
     * page  zero-based; a number past the end is pulled back to the last page
     */
    public Listing list(String q, int page) {
        String where = "";
        List<Object> args = new ArrayList<>();
        String needle = q == null ? "" : q.trim();
        if (!needle.isEmpty()) {
            String like = "%" + InquiryService.likeEscape(needle) + "%";
            where = " WHERE (e.to_email ILIKE ? OR e.to_name ILIKE ? OR e.subject ILIKE ? OR e.body ILIKE ?)";
            for (int i = 0; i < 4; i++) args.add(like);
        }

        Long counted = jdbc.queryForObject("SELECT count(*) FROM admin_emails e" + where, Long.class, args.toArray());
        long total = counted == null ? 0 : counted;
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int number = Math.max(0, Math.min(page, pages - 1));
        long offset = (long) number * PAGE_SIZE;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add(offset);
        List<Sent> rows = jdbc.query(
                SELECT + where + " ORDER BY e.sent_at DESC, e.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());
        return new Listing(rows, number, pages, total, rows.isEmpty() ? 0 : offset + 1, offset + rows.size());
    }

    public Optional<Sent> find(long id) {
        return jdbc.query(SELECT + " WHERE e.id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Sent map(ResultSet rs) throws SQLException {
        String name = rs.getString("to_name");
        String body = rs.getString("body");
        return new Sent(rs.getLong("id"), rs.getString("to_email"), name == null ? "" : name,
                rs.getString("subject"), body, InquiryService.excerpt(body),
                "en".equals(rs.getString("lang")) ? "English" : "Arabic",
                InquiryService.when(rs.getObject("sent_at", OffsetDateTime.class)));
    }

    /**
     * Splits what was typed into the To box into addresses, in the order typed and without
     * repeats (an address typed twice gets one copy). Returns the first entry that is not
     * an email address, or null when every entry is one.
     */
    static String parseRecipients(String to, List<String> into) {
        if (to == null) return null;
        Map<String, String> seen = new LinkedHashMap<>();
        for (String piece : BETWEEN_ADDRESSES.split(to)) {
            String address = piece.strip();
            if (address.isEmpty()) continue;
            if (!InquiryService.looksLikeEmail(address)) return clip(address, 80);
            seen.putIfAbsent(address.toLowerCase(Locale.ROOT), address);
        }
        into.addAll(seen.values());
        return null;
    }

    /** The text on one line: line breaks, tabs and control characters become single spaces.
     *  A subject must never carry a line break into the mail's headers. */
    static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Cntrl}\\u0085\\u2028\\u2029]+", " ").strip();
    }

    /** At most max characters, never ending in half of a surrogate pair (an emoji). */
    static String clip(String s, int max) {
        if (s.length() <= max) return s;
        int cut = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, cut).stripTrailing();
    }

    /**
     * The language the frame of the mail is laid out in (direction, greeting, footer):
     * "ar" when the subject or the message holds Arabic letters, "en" otherwise. The admin
     * console is English only, so the email follows what was written, not the page.
     */
    public static String frameLang(String subject, String body) {
        String text = (subject == null ? "" : subject) + " " + (body == null ? "" : body);
        boolean arabic = text.codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC);
        return arabic ? "ar" : "en";
    }
}
