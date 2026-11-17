package iq.ievent.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R42: how the super-admin console shows the people it may need to reach. Two jobs:
 * make a stored phone number ready for a page (readable text, a dial link, a WhatsApp
 * link when the number allows one), and fold order rows into the "Buyers" list, one
 * row per person.
 *
 * Plain Java on purpose (no Spring, no JPA): AdminController runs the queries and hands
 * the rows over, so everything here can be exercised without a database.
 */
public final class AdminContacts {

    private AdminContacts() {}

    private static final ZoneId BAGHDAD = ZoneId.of("Asia/Baghdad");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    // ------------------------------------------------------------------
    // Phone numbers
    // ------------------------------------------------------------------

    /**
     * A stored phone number made ready for a page.
     *
     * display   what to print; "" when there is no number
     * tel       what goes after "tel:"; "" when the stored text holds no usable digits
     * whatsapp  what goes after "https://wa.me/"; "" unless the number is a valid Iraqi
     *           mobile or was written in international form with a leading +
     */
    public record Phone(String display, String tel, String whatsapp) {

        public static final Phone NONE = new Phone("", "", "");

        public static Phone of(String stored) {
            if (stored == null || stored.isBlank()) return NONE;
            String s = stored.trim();
            String e164 = IraqPhone.normalize(s).orElse(null);
            if (e164 != null) {
                return new Phone(IraqPhone.display(e164), e164, e164.substring(1));
            }
            // Not an Iraqi mobile (a buyer abroad, or a legacy free-text value): print it
            // as stored. Only a number written with its country code can open WhatsApp.
            String d = digits(s);
            boolean plus = s.startsWith("+");
            String tel = d.length() < 5 ? "" : (plus ? "+" : "") + d;
            boolean international = plus && d.length() >= 8 && d.length() <= 15;
            return new Phone(s, tel, international ? d : "");
        }

        public boolean present() {
            return !display.isEmpty();
        }
    }

    /** The number a list shows for an organizer, a note naming where it came from when it
     *  is not the owner's own ("" otherwise), and the digits of every number the organizer
     *  has, for the search box. */
    public record OrganizerPhone(Phone phone, String note, String searchDigits) {}

    /** Owner's account number first (asked for when the organizer signs up), then the
     *  public contact number from Branding, then the number buyers call before a transfer. */
    public static OrganizerPhone organizerPhone(String ownerPhone, String contactPhone, String paymentPhone) {
        String all = digits(ownerPhone) + " " + digits(contactPhone) + " " + digits(paymentPhone);
        Phone owner = Phone.of(ownerPhone);
        if (owner.present()) return new OrganizerPhone(owner, "", all);
        Phone contact = Phone.of(contactPhone);
        if (contact.present()) return new OrganizerPhone(contact, "public contact number", all);
        Phone payment = Phone.of(paymentPhone);
        if (payment.present()) return new OrganizerPhone(payment, "payments number", all);
        return new OrganizerPhone(Phone.NONE, "", all);
    }

    /** ASCII digits of any text; Arabic-Indic and Persian digits are read as well. */
    public static String digits(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            } else if (c >= '٠' && c <= '٩') {
                out.append((char) ('0' + (c - '٠')));
            } else if (c >= '۰' && c <= '۹') {
                out.append((char) ('0' + (c - '۰')));
            }
        }
        return out.toString();
    }

    /**
     * What to look for among stored numbers when a search entry looks like a phone number
     * or part of one: only digits, spaces and + - ( ) . with at least three digits. Leading
     * zeros are dropped, so "0770 123" finds "+964 770 123 4567". Anything else gives "".
     */
    public static String phoneNeedle(String query) {
        if (query == null) return "";
        String q = query.trim();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= '٠' && c <= '٩')
                    || (c >= '۰' && c <= '۹');
            if (!digit && c != ' ' && c != '+' && c != '-' && c != '(' && c != ')' && c != '.') return "";
        }
        String d = digits(q);
        int start = 0;
        while (start < d.length() && d.charAt(start) == '0') start++;
        d = d.substring(start);
        return d.length() < 3 ? "" : d;
    }

    /** True when the needle (from phoneNeedle) occurs in the digits of the stored numbers.
     *  A needle that starts with Iraq's 964 is also tried without it, for numbers that were
     *  stored the domestic way (0770...). */
    public static boolean hasNumber(String storedDigits, String needle) {
        if (needle == null || needle.isEmpty() || storedDigits == null) return false;
        if (storedDigits.contains(needle)) return true;
        return needle.startsWith("964") && needle.length() >= 6
                && storedDigits.contains(needle.substring(3));
    }

    // ------------------------------------------------------------------
    // Buyers
    // ------------------------------------------------------------------

    /** One order, as the Buyers page needs it. buyerName, buyerEmail and buyerPhone are
     *  what was typed at checkout for this order; accountEmail and accountPhone belong to
     *  the buyer's account. tickets is the quantity ordered. */
    public record OrderLine(String orderCode, long buyerUserId, String buyerName, String buyerEmail,
                            String buyerPhone, String accountEmail, String accountPhone,
                            String status, int tickets, OffsetDateTime createdAt,
                            long eventId, String eventTitle, String eventSlug, String orgName) {}

    /**
     * One person who placed at least one order.
     *
     * name, email    as typed on their newest order
     * accountEmail   their sign-in email when it differs from `email`, else ""
     * phone          the newest number they typed at checkout, else their account number
     * orders         every order of theirs in view; confirmed and pending count two of
     *                the statuses, ordersNote spells the split out ("2 confirmed, 1 awaiting
     *                confirmation")
     * tickets        tickets on their confirmed orders
     * moreEvents     how many events they ordered for besides the newest one
     * last...        their newest order
     */
    public record Buyer(long userId, String name, String email, String accountEmail, Phone phone,
                        int orders, int confirmed, int pending, String ordersNote, int tickets,
                        int moreEvents, String lastEventTitle, String lastEventSlug, String lastOrgName,
                        String lastOrderCode, String lastOrderLine, String lastStatus,
                        String lastStatusLabel, String searchText, String searchDigits) {}

    /** Folds orders into buyers. The orders must come newest first; the buyers come out in
     *  the order of their newest order, so the most recent buyer leads the list. */
    public static List<Buyer> buyers(List<OrderLine> newestFirst) {
        Map<Long, List<OrderLine>> byBuyer = new LinkedHashMap<>();
        for (OrderLine line : newestFirst) {
            byBuyer.computeIfAbsent(line.buyerUserId(), k -> new ArrayList<>()).add(line);
        }
        List<Buyer> out = new ArrayList<>(byBuyer.size());
        for (List<OrderLine> own : byBuyer.values()) out.add(toBuyer(own));
        return out;
    }

    private static Buyer toBuyer(List<OrderLine> own) {
        OrderLine last = own.get(0);
        String storedPhone = null;
        int confirmed = 0;
        int pending = 0;
        int tickets = 0;
        Set<Long> eventIds = new HashSet<>();
        StringBuilder text = new StringBuilder();
        StringBuilder numbers = new StringBuilder();
        for (OrderLine line : own) {
            if (storedPhone == null && line.buyerPhone() != null && !line.buyerPhone().isBlank()) {
                storedPhone = line.buyerPhone();
            }
            if ("CONFIRMED".equals(line.status())) {
                confirmed++;
                tickets += line.tickets();
            } else if ("PENDING_CONFIRMATION".equals(line.status())) {
                pending++;
            }
            eventIds.add(line.eventId());
            text.append(nz(line.buyerName())).append('\n')
                    .append(nz(line.buyerEmail())).append('\n')
                    .append(nz(line.orderCode())).append('\n')
                    .append(nz(line.eventTitle())).append('\n')
                    .append(nz(line.orgName())).append('\n');
            numbers.append(digits(line.buyerPhone())).append(' ');
        }
        text.append(nz(last.accountEmail()));
        numbers.append(digits(last.accountPhone()));
        if (storedPhone == null) storedPhone = last.accountPhone();

        String email = nz(last.buyerEmail()).trim();
        String accountEmail = nz(last.accountEmail()).trim();
        if (accountEmail.equalsIgnoreCase(email)) accountEmail = "";

        return new Buyer(last.buyerUserId(), nz(last.buyerName()).trim(), email, accountEmail,
                Phone.of(storedPhone),
                own.size(), confirmed, pending, ordersNote(own.size(), confirmed, pending), tickets,
                eventIds.size() - 1, nz(last.eventTitle()), nz(last.eventSlug()), nz(last.orgName()),
                nz(last.orderCode()), dateLine(last.createdAt()), nz(last.status()),
                statusLabel(last.status()), text.toString().toLowerCase(Locale.ROOT), numbers.toString());
    }

    /** "2 confirmed, 1 awaiting confirmation, 1 rejected, cancelled or refunded". */
    static String ordersNote(int orders, int confirmed, int pending) {
        List<String> parts = new ArrayList<>(3);
        if (confirmed > 0) parts.add(confirmed + " confirmed");
        if (pending > 0) parts.add(pending + " awaiting confirmation");
        int rest = orders - confirmed - pending;
        if (rest > 0) parts.add(rest + " rejected, cancelled or refunded");
        return String.join(", ", parts);
    }

    public static String statusLabel(String status) {
        if (status == null) return "";
        return switch (status) {
            case "CONFIRMED" -> "Confirmed";
            case "PENDING_CONFIRMATION" -> "Awaiting confirmation";
            case "REJECTED" -> "Rejected";
            case "CANCELLED" -> "Cancelled";
            case "REFUNDED" -> "Refunded";
            default -> status;
        };
    }

    /** True for an empty search; otherwise the entry must occur in the buyer's name, an
     *  email, an order code, an event title or an organizer name, or (when it looks like a
     *  phone number) in one of their numbers. */
    public static boolean matches(Buyer buyer, String query) {
        if (query == null || query.isBlank()) return true;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return buyer.searchText().contains(needle)
                || hasNumber(buyer.searchDigits(), phoneNeedle(query));
    }

    /** One page of the list. number is zero-based; from and to are the one-based positions
     *  of the first and last row shown (both 0 when the list is empty). */
    public record Paged(List<Buyer> rows, int number, int pages, int total, int from, int to) {}

    /** Page `number` of `all` with `size` rows a page; a number out of range is pulled back
     *  to the nearest page that exists. */
    public static Paged page(List<Buyer> all, int number, int size) {
        int total = all.size();
        int pages = Math.max(1, (total + size - 1) / size);
        int n = Math.max(0, Math.min(number, pages - 1));
        int start = Math.min(n * size, total);
        int end = Math.min(start + size, total);
        return new Paged(all.subList(start, end), n, pages, total, total == 0 ? 0 : start + 1, end);
    }

    /** A positive id from a request parameter; null when it is missing or not a number. */
    public static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A zero-based page number from a request parameter; 0 when it is missing, negative
     *  or not a number. */
    public static int parsePage(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String dateLine(OffsetDateTime t) {
        return t == null ? "-" : t.atZoneSameInstant(BAGHDAD).format(DATE_FMT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
