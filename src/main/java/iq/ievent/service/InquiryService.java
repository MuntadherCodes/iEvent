package iq.ievent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * R42: messages from the website's contact form, kept in the `inquiries` table (V29) so
 * the super-admin console can list them, tell the answered ones from the waiting ones,
 * and reply by email. ExtrasController stores a message when the form is submitted (the
 * copy to the support mailbox still goes out as before); AdminController reads and
 * answers them.
 *
 * "Replied" means inquiries.replied_at is set: by a reply sent from the console, or by
 * an admin marking the inquiry as replied by hand.
 */
@Service
public class InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    /** Rows per page on the admin list. */
    public static final int PAGE_SIZE = 50;
    /** Longest reply the console sends (the contact form caps a message at the same length). */
    public static final int MAX_REPLY = 5000;

    private static final ZoneId BAGHDAD = ZoneId.of("Asia/Baghdad");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.ENGLISH);

    private static final String SELECT = """
            SELECT i.id, i.name, i.email, i.topic, i.message, i.lang, i.user_id,
                   i.created_at, i.replied_at,
                   (SELECT count(*) FROM inquiry_replies r WHERE r.inquiry_id = i.id) AS reply_count
            FROM inquiries i
            """;

    private final JdbcTemplate jdbc;
    private final MailService mail;
    private final String mailFrom;
    private final String supportEmail;

    public InquiryService(JdbcTemplate jdbc, MailService mail,
                          @Value("${app.mail.from}") String mailFrom,
                          @Value("${app.mail.support}") String supportEmail) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.mailFrom = mailFrom;
        this.supportEmail = supportEmail;
    }

    /** The address replies are sent from (shown beside the reply box). */
    public String mailFrom() {
        return mailFrom;
    }

    /** The support mailbox: where the person's own answer to a reply arrives. */
    public String supportEmail() {
        return supportEmail;
    }

    /**
     * One inquiry, ready for the admin pages (which are English only).
     *
     * topicLabel    the topic as the contact form words it
     * excerpt       the message on one line, cut to fit a table cell
     * lang          "ar" or "en": the language the visitor was browsing in
     * receivedLine  when it arrived, Baghdad time ("Nov 17, 2026, 2:12 PM")
     * replied       true once a reply went out or an admin marked it by hand
     * repliedLine   when that happened ("" while it waits)
     * replyCount    replies sent from the console (0 when it was only marked by hand)
     * hasAccount    the sender was signed in, so name and email come from their account
     */
    public record Inquiry(long id, String name, String email, String topic, String topicLabel,
                          String message, String excerpt, String lang, String langLabel,
                          String receivedLine, boolean replied, String repliedLine,
                          int replyCount, boolean hasAccount) {}

    /** One reply sent from the console. */
    public record Reply(long id, String body, String sentLine) {}

    /** How many inquiries there are in all, how many still wait and how many are replied. */
    public record Counts(long all, long open, long replied) {}

    /** One page of the list. number is zero-based; from and to are the one-based positions
     *  of the first and last row shown (both 0 when nothing matches). */
    public record Listing(List<Inquiry> rows, int number, int pages, long total, long from, long to) {}

    /** What happened to a reply the admin tried to send. */
    public enum ReplyOutcome {
        /** Emailed, and recorded on the inquiry. */
        SENT,
        /** Emailed, but the record could not be written: the inquiry still shows as waiting. */
        SENT_NOT_RECORDED,
        NOT_FOUND,
        EMPTY,
        TOO_LONG,
        /** The address the visitor typed cannot be mailed to. Nothing was sent. */
        BAD_ADDRESS,
        /** The mail server refused or could not be reached. Nothing was recorded. */
        MAIL_FAILED
    }

    // ------------------------------------------------------------------
    // Storing (contact form)
    // ------------------------------------------------------------------

    /**
     * Keeps one contact-form message. Returns false instead of throwing when the row
     * cannot be written: the visitor's message is still forwarded by email, and a
     * database problem must not turn the contact page into an error page.
     */
    public boolean record(String name, String email, String topic, String message, String lang, Long userId) {
        try {
            jdbc.update("""
                    INSERT INTO inquiries (name, email, topic, message, lang, user_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, name, email, topic, message, "en".equals(lang) ? "en" : "ar", userId);
            return true;
        } catch (RuntimeException e) {
            log.error("Could not store the contact-form message from {}", email, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Reading (admin console)
    // ------------------------------------------------------------------

    public Counts counts() {
        return jdbc.query(
                "SELECT count(*), count(*) FILTER (WHERE replied_at IS NULL) FROM inquiries",
                (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(1) - rs.getLong(2)))
                .get(0);
    }

    /** Inquiries that still wait for an answer: the number on the admin tab. Never throws,
     *  because every admin page asks for it and a problem with this one count must not
     *  take the organizations and buyers pages down with it. */
    public long openCount() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM inquiries WHERE replied_at IS NULL", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            log.warn("Could not count the waiting inquiries", e);
            return 0;
        }
    }

    /**
     * One page of inquiries, newest first.
     *
     * q       matched against the sender's name, their email and the message text
     * status  "open" (not replied yet), "replied", anything else for all of them
     * page    zero-based; a number past the end is pulled back to the last page
     */
    public Listing list(String q, String status, int page) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if ("open".equals(status)) {
            where.append(" AND i.replied_at IS NULL");
        } else if ("replied".equals(status)) {
            where.append(" AND i.replied_at IS NOT NULL");
        }
        String needle = q == null ? "" : q.trim();
        if (!needle.isEmpty()) {
            String like = "%" + likeEscape(needle) + "%";
            where.append(" AND (i.name ILIKE ? OR i.email ILIKE ? OR i.message ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        Long counted = jdbc.queryForObject("SELECT count(*) FROM inquiries i" + where, Long.class, args.toArray());
        long total = counted == null ? 0 : counted;
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int number = Math.max(0, Math.min(page, pages - 1));
        long offset = (long) number * PAGE_SIZE;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add(offset);
        List<Inquiry> rows = jdbc.query(
                SELECT + where + " ORDER BY i.created_at DESC, i.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());
        return new Listing(rows, number, pages, total, rows.isEmpty() ? 0 : offset + 1, offset + rows.size());
    }

    public Optional<Inquiry> find(long id) {
        return jdbc.query(SELECT + " WHERE i.id = ?", (rs, i) -> map(rs), id).stream().findFirst();
    }

    /** The replies sent for one inquiry, oldest first. */
    public List<Reply> replies(long inquiryId) {
        return jdbc.query("""
                SELECT id, body, sent_at FROM inquiry_replies
                WHERE inquiry_id = ?
                ORDER BY sent_at, id
                """,
                (rs, i) -> new Reply(rs.getLong("id"), rs.getString("body"),
                        when(rs.getObject("sent_at", OffsetDateTime.class))),
                inquiryId);
    }

    // ------------------------------------------------------------------
    // Answering (admin console)
    // ------------------------------------------------------------------

    /**
     * Emails the admin's answer to the person who wrote, then records it and marks the
     * inquiry as replied. The mail goes first and synchronously: an answer is recorded
     * only when the mail server accepted it, so "Replied" never covers a mail that did
     * not go out.
     */
    public ReplyOutcome reply(long id, String text) {
        Inquiry inquiry = find(id).orElse(null);
        if (inquiry == null) return ReplyOutcome.NOT_FOUND;
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) return ReplyOutcome.EMPTY;
        if (body.length() > MAX_REPLY) return ReplyOutcome.TOO_LONG;
        if (!looksLikeEmail(inquiry.email())) return ReplyOutcome.BAD_ADDRESS;

        boolean sent = mail.sendInquiryReply(inquiry.email(), inquiry.name(), body, inquiry.message(),
                Locale.forLanguageTag(inquiry.lang()));
        if (!sent) return ReplyOutcome.MAIL_FAILED;

        try {
            // one statement, so the reply and the status can never disagree
            jdbc.update("""
                    WITH sent AS (
                        INSERT INTO inquiry_replies (inquiry_id, body) VALUES (?, ?) RETURNING sent_at
                    )
                    UPDATE inquiries SET replied_at = (SELECT sent_at FROM sent) WHERE id = ?
                    """, id, body, id);
            return ReplyOutcome.SENT;
        } catch (RuntimeException e) {
            log.error("Reply to inquiry {} was emailed but could not be recorded", id, e);
            return ReplyOutcome.SENT_NOT_RECORDED;
        }
    }

    /** Marks an inquiry as replied by hand (answered from the mailbox, by phone, or
     *  needing no answer). Leaves the time alone when it is already replied. */
    public void markReplied(long id) {
        jdbc.update("UPDATE inquiries SET replied_at = now() WHERE id = ? AND replied_at IS NULL", id);
    }

    /** Puts an inquiry back among the waiting ones. Replies already sent stay listed. */
    public void markOpen(long id) {
        jdbc.update("UPDATE inquiries SET replied_at = NULL WHERE id = ?", id);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Inquiry map(ResultSet rs) throws SQLException {
        String topic = rs.getString("topic");
        String message = rs.getString("message");
        String lang = "en".equals(rs.getString("lang")) ? "en" : "ar";
        OffsetDateTime repliedAt = rs.getObject("replied_at", OffsetDateTime.class);
        rs.getLong("user_id");
        boolean hasAccount = !rs.wasNull();
        return new Inquiry(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                topic, topicLabel(topic), message, excerpt(message), lang,
                "en".equals(lang) ? "English" : "Arabic",
                when(rs.getObject("created_at", OffsetDateTime.class)),
                repliedAt != null, repliedAt == null ? "" : when(repliedAt),
                rs.getInt("reply_count"), hasAccount);
    }

    /** The topic in the words of the contact form's own menu. */
    public static String topicLabel(String topic) {
        if (topic == null) return "General question";
        return switch (topic) {
            case "attendee" -> "Order / ticket issue";
            case "organizer" -> "Organizer / event support";
            case "billing" -> "Billing / payments";
            default -> "General question";
        };
    }

    /** The message on one line, at most 140 characters, ending in "…" when it was cut. */
    public static String excerpt(String message) {
        if (message == null) return "";
        String flat = message.strip().replaceAll("\\s+", " ");
        if (flat.length() <= 140) return flat;
        int cut = 139;
        // never cut a surrogate pair (an emoji) in half
        if (Character.isHighSurrogate(flat.charAt(cut - 1))) cut--;
        return flat.substring(0, cut).stripTrailing() + "…";
    }

    /** "Nov 17, 2026, 2:12 PM", Baghdad time. */
    public static String when(OffsetDateTime t) {
        return t == null ? "" : t.atZoneSameInstant(BAGHDAD).format(WHEN);
    }

    /** Escapes the three characters LIKE treats specially, so a search for "50%" or
     *  "a_b" looks for exactly that. Backslash is LIKE's default escape character. */
    static String likeEscape(String needle) {
        return needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * True when the text is one plain address the console may send mail to: something,
     * an @, a domain with a dot, no spaces, no control characters and none of the
     * characters that could smuggle in a second recipient or a header. The contact form
     * itself only asks for an "@", so this is checked again before a reply goes out.
     */
    public static boolean looksLikeEmail(String email) {
        if (email == null || email.length() > 254) return false;
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) return false;
        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (c <= ' ' || c == 0x7f || Character.isWhitespace(c) || Character.isISOControl(c)
                    || "<>(),;:\\\"[]".indexOf(c) >= 0) {
                return false;
            }
        }
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        return dot > 0 && dot < domain.length() - 1 && !domain.startsWith(".") && !domain.contains("..");
    }
}
