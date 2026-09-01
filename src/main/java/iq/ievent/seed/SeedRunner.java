package iq.ievent.seed;

import iq.ievent.domain.Event;
import iq.ievent.domain.Organization;
import iq.ievent.domain.TicketType;
import iq.ievent.domain.User;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.OrganizationRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;

/**
 * Seeds demo content (SEED_DEMO=true) and optional synthetic volume (SEED_SCALE=N).
 * Idempotent: demo seeding is skipped when the demo organizer already exists;
 * scale seeding tops up to the requested count.
 */
@Component
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private static final String DEMO_HANDLE = "zainevents";
    private static final String[] CITIES =
            {"Baghdad", "Erbil", "Basra", "Sulaymaniyah", "Najaf", "Karbala", "Mosul", "Duhok"};

    private final boolean seedDemo;
    private final int seedScale;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final EventRepository events;
    private final TicketTypeRepository ticketTypes;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public SeedRunner(@Value("${app.seed.demo:false}") boolean seedDemo,
                      @Value("${app.seed.scale:0}") int seedScale,
                      UserRepository users,
                      OrganizationRepository organizations,
                      EventRepository events,
                      TicketTypeRepository ticketTypes,
                      PasswordEncoder passwordEncoder,
                      JdbcTemplate jdbc) {
        this.seedDemo = seedDemo;
        this.seedScale = seedScale;
        this.users = users;
        this.organizations = organizations;
        this.events = events;
        this.ticketTypes = ticketTypes;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        repairScaleEventOwnership();
        if (seedDemo && organizations.findByHandle(DEMO_HANDLE).isEmpty()) {
            seedDemoData();
        } else if (seedDemo) {
            log.info("Demo seed already present — skipping");
            seedDemoOrdersIfMissing();
        }
        if (seedDemo) {
            enrichDemoContent();
        }
        if (seedScale > 0) {
            seedScaleData(seedScale);
        }
    }

    /** V5 fields (summary/tags/lineup, org contacts) for existing demo databases.
     *  Idempotent: only fills columns that are still NULL. */
    private void enrichDemoContent() {
        jdbc.update("""
                UPDATE organizations SET
                    contact_email = COALESCE(contact_email, 'hello@zainevents.iq'),
                    contact_phone = COALESCE(contact_phone, '+964 770 123 4567'),
                    website       = COALESCE(website, 'https://zainevents.iq'),
                    instagram     = COALESCE(instagram, 'zainevents'),
                    brand_color   = COALESCE(brand_color, '#8f7ac9')
                WHERE handle = ?
                """, DEMO_HANDLE);
        // second demo payment method so checkout shows a real choice (idempotent)
        jdbc.update("""
                INSERT INTO payment_methods (organization_id, label, account_number, account_name, instructions, sort_order)
                SELECT o.id, 'ZainCash wallet', '0770 123 4567', 'Zain Events Finance',
                       'Send to this ZainCash number, then upload the confirmation screenshot.', 1
                FROM organizations o
                WHERE o.handle = ?
                  AND NOT EXISTS (SELECT 1 FROM payment_methods pm
                                   WHERE pm.organization_id = o.id AND pm.label = 'ZainCash wallet')
                """, DEMO_HANDLE);
        enrich("baghdad-nights-music-festival",
                "One night, three stages, the best of Iraq's live music scene under the open sky.",
                "music, festival, live, family-friendly, baghdad",
                "DJ Rafi - 7:00 PM\nHiba Salim & Band - 8:15 PM\nMaqam Reborn Ensemble - 9:30 PM\nIlham (headliner) - 11:00 PM");
        enrich("erbil-tech-summit-2026",
                "Iraq and Kurdistan's largest gathering of startups, engineers and investors.",
                "tech, startup, conference, networking, erbil",
                "Opening keynote - 9:30 AM\nFounders panel - 11:00 AM\nInvestor office hours - 2:00 PM\nDemo night - 5:00 PM");
        enrich("basra-corniche-food-carnival",
                "Fifty kitchens along the Shatt al-Arab, free entry, taste tickets on site.",
                "food, street-food, family, basra", null);
        enrich("sulaymaniyah-film-nights",
                "Three award-winning Iraqi and Kurdish features, followed by a director Q&A.",
                "film, cinema, culture, sulaymaniyah",
                "Doors - 6:30 PM\nFeature one - 7:00 PM\nFeature two - 8:40 PM\nDirector Q&A - 10:20 PM");
        enrich("startup-mixer-baghdad",
                "Monthly meetup for founders, freelancers and the simply curious. First drink on us.",
                "business, networking, startup, baghdad", null);
        enrich("mosul-heritage-walk",
                "A guided morning walk through the rebuilt Old City with local historians.",
                "community, heritage, walking-tour, mosul", null);
        enrich("karbala-book-fair",
                "Four days, 120 publishers, author signings and children's readings.",
                "education, books, family, karbala", null);
        enrich("duhok-mountain-marathon",
                "21K and 10K trail routes above Duhok Dam, with medals, chips and water stations.",
                "sports, running, outdoors, duhok",
                "10K start - 6:30 AM\n21K start - 7:00 AM\nAwards ceremony - 12:30 PM");
    }

    private void enrich(String slug, String summary, String tags, String lineup) {
        jdbc.update("""
                UPDATE events SET
                    summary = COALESCE(summary, ?),
                    tags    = COALESCE(tags, ?),
                    lineup  = COALESCE(lineup, ?)
                WHERE slug = ?
                """, summary, tags, lineup, slug);
    }

    /** Older seeds attached synthetic scale events to the demo organizer, drowning
     *  its dashboard in noise. Move them (idempotent data repair, runs every boot). */
    private void repairScaleEventOwnership() {
        Long demoOrg = jdbc.query("SELECT id FROM organizations WHERE handle = ?",
                rs -> rs.next() ? rs.getLong(1) : null, DEMO_HANDLE);
        if (demoOrg == null) return;
        Integer misplaced = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE slug LIKE 'scale-%' AND organization_id = ?",
                Integer.class, demoOrg);
        if (misplaced == null || misplaced == 0) return;
        Long scaleOrg = jdbc.query("SELECT id FROM organizations WHERE handle = 'scaletest'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (scaleOrg == null) {
            User owner = user("scale-host@ievent.iq", "Scale Host", "Password123!", User.Role.HOST);
            Organization o = new Organization();
            o.setOwnerUserId(owner.getId());
            o.setName("Scale Test Events");
            o.setHandle("scaletest");
            o.setCity("Baghdad");
            scaleOrg = organizations.save(o).getId();
        }
        int moved = jdbc.update(
                "UPDATE events SET organization_id = ? WHERE slug LIKE 'scale-%' AND organization_id = ?",
                scaleOrg, demoOrg);
        log.info("Repair: moved {} scale events off the demo organizer", moved);
    }

    /** Flagship events looked sold (sold counters) but had no ticket rows, so
     *  attendee lists and door lists were empty. Create real demo orders once. */
    private void seedDemoOrdersIfMissing() {
        Long demoOrg = jdbc.query("SELECT id FROM organizations WHERE handle = ?",
                rs -> rs.next() ? rs.getLong(1) : null, DEMO_HANDLE);
        if (demoOrg == null) return;
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM orders o JOIN events e ON e.id = o.event_id
                WHERE e.organization_id = ? AND o.order_code LIKE 'EVT-DEMO-%'
                """, Integer.class, demoOrg);
        if (existing != null && existing > 0) return;
        seedDemoOrders();
    }

    private static final String[] DEMO_GUESTS = {
            "Ali Hassan", "Noor Al-Saadi", "Omar Dawood", "Huda Jassim", "Mustafa Karim",
            "Zainab Qasim", "Rania Faris", "Yousif Salman", "Layla Ibrahim", "Ahmed Rashid",
            "Sarah Mahmoud", "Bilal Hameed", "Dina Kareem", "Hasan Jabbar", "Mariam Adel"
    };

    private void seedDemoOrders() {
        log.info("Seeding demo orders & tickets for flagship events …");
        Random rnd = new Random(7);
        // (event slug, ticket type name, how many single-ticket confirmed orders, how many checked in)
        String[][] plan = {
                {"baghdad-nights-music-festival", "General Admission", "12", "5"},
                {"startup-mixer-baghdad", "RSVP", "10", "6"},
                {"erbil-tech-summit-2026", "Standard Pass", "8", "0"},
                {"sulaymaniyah-film-nights", "Screening Pass", "6", "0"},
        };
        int seq = 1;
        for (String[] p : plan) {
            Long eventId = jdbc.query("SELECT id FROM events WHERE slug = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, p[0]);
            if (eventId == null) continue;
            Long ttId = jdbc.query("SELECT id FROM ticket_types WHERE event_id = ? AND name = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, eventId, p[1]);
            if (ttId == null) continue;
            Long price = jdbc.queryForObject("SELECT price_iqd FROM ticket_types WHERE id = ?",
                    Long.class, ttId);
            int orders = Integer.parseInt(p[2]);
            int checkins = Integer.parseInt(p[3]);
            for (int i = 0; i < orders; i++) {
                String guest = DEMO_GUESTS[rnd.nextInt(DEMO_GUESTS.length)];
                String email = guest.toLowerCase().replace(" ", ".") + "@example.iq";
                User buyer = user(email, guest, "Password123!", User.Role.USER);
                long fee = price != null ? iq.ievent.service.Format.bookingFeeFor(price) : 0L;
                String code = String.format("EVT-DEMO-%05d", seq++);
                Long orderId = jdbc.queryForObject("""
                        INSERT INTO orders (order_code, event_id, buyer_user_id, buyer_name, buyer_email,
                                            payment_method, status, subtotal_iqd, booking_fee_iqd, total_iqd,
                                            confirmed_at, holder_names)
                        VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, now(), ?)
                        RETURNING id
                        """, Long.class,
                        code, eventId, buyer.getId(), guest, email,
                        price != null && price > 0 ? "DIRECT_TRANSFER" : "FREE",
                        price == null ? 0 : price, fee, (price == null ? 0 : price) + fee, guest);
                jdbc.update("""
                        INSERT INTO order_items (order_id, ticket_type_id, quantity, unit_price_iqd)
                        VALUES (?, ?, 1, ?)
                        """, orderId, ttId, price == null ? 0 : price);
                boolean checkedIn = i < checkins;
                StringBuilder tcode = new StringBuilder("DEMO");
                String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
                for (int c = 0; c < 16; c++) tcode.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                jdbc.update("""
                        INSERT INTO tickets (code, order_id, ticket_type_id, event_id, holder_name, status, checked_in_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, tcode.toString(), orderId, ttId, eventId, guest,
                        checkedIn ? "CHECKED_IN" : "VALID",
                        checkedIn ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
            }
        }
        log.info("Demo orders & tickets seeded");
    }

    private void seedDemoData() {
        log.info("Seeding demo data …");

        User fahad = user("fahad@zainevents.iq", "Fahad Al-Thakur", "Password123!", User.Role.HOST);
        User amira = user("amira@example.iq", "Amira Hassan", "Password123!", User.Role.USER);
        User omar = user("omar@example.iq", "Omar Dawood", "Password123!", User.Role.USER);

        Organization org = new Organization();
        org.setOwnerUserId(fahad.getId());
        org.setName("Zain Events Co.");
        org.setHandle(DEMO_HANDLE);
        org.setBio("Baghdad's leading live-events crew. We produce festivals, concerts and cultural nights across Iraq, from intimate rooftop sessions to full-scale park festivals.");
        org.setCity("Baghdad");
        org.setVerified(true);
        org.setDirectPaymentsEnabled(true);
        org.setPayCardNumber("5326 1102 4478 4821");
        org.setPayAccountName("Zain Events Finance");
        org.setPayWalletBank("ZainCash");
        org.setPayInstructions("Transfer the exact total, then upload a screenshot of the receipt. Write your order number in the transfer note.");
        org = organizations.save(org);

        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS);

        Event e1 = event(org, "Baghdad Nights Music Festival", "baghdad-nights-music-festival",
                Event.Category.MUSIC, "Baghdad", "Al-Zawraa Park, Main Amphitheatre",
                "Zawraa Park Main Gate, Al-Mansour District, Baghdad",
                base.plusDays(5).withHour(19), base.plusDays(6).withHour(0),
                """
                Baghdad Nights returns for its third — and biggest — edition. One unforgettable evening under the open sky of Al-Zawraa Park, bringing together the best of Iraq's live music scene: from classic maqam reimagined for a new generation to the freshest voices in Arabic indie and hip-hop.

                Expect three stages, a full street-food village curated by Baghdad's favourite kitchens, an artisan market, and a closing fireworks show over the park lake. Gates open at 5:30 PM for VIP ticket holders and 7:00 PM for general admission.

                This is an all-ages, family-friendly event. Free parking is available at the Zawraa main gate, and dedicated shuttle buses run from Karrada and Mansour every 20 minutes from 5:00 PM.
                """);
        tt(e1, "Early Bird", 25_000, 100, 100, 0, TicketType.Status.SOLD_OUT);
        tt(e1, "General Admission", 35_000, 300, 118, 1, TicketType.Status.ON_SALE);
        tt(e1, "VIP Table (4 seats)", 200_000, 20, 6, 2, TicketType.Status.ON_SALE);

        Event e2 = event(org, "Erbil Tech Summit 2026", "erbil-tech-summit-2026",
                Event.Category.TECH, "Erbil", "Erbil International Fairground", "100m Road, Erbil",
                base.plusDays(17).withHour(9), base.plusDays(17).withHour(18),
                "Two stages, 40 speakers and 1,500 builders: Iraq and Kurdistan's largest gathering of startups, engineers and investors.\n\nTalks in Arabic, Kurdish and English with live translation.");
        tt(e2, "Standard Pass", 50_000, 1200, 342, 0, TicketType.Status.ON_SALE);
        tt(e2, "Startup Booth", 250_000, 40, 22, 1, TicketType.Status.ON_SALE);

        Event e3 = event(org, "Basra Corniche Food Carnival", "basra-corniche-food-carnival",
                Event.Category.FOOD, "Basra", "Basra Corniche", "Corniche Street, Basra",
                base.plusDays(25).withHour(16), base.plusDays(25).withHour(23),
                "Fifty kitchens along the Shatt al-Arab. Free entry, taste tickets sold on site.");
        tt(e3, "Entry", 0, 5000, 129, 0, TicketType.Status.ON_SALE);

        Event e4 = event(org, "Sulaymaniyah Film Nights", "sulaymaniyah-film-nights",
                Event.Category.FILM, "Sulaymaniyah", "Culture Hall", "Salim Street, Sulaymaniyah",
                base.plusDays(12).withHour(18).withMinute(30), base.plusDays(12).withHour(23),
                "Three award-winning features from Iraqi and Kurdish directors, followed by a Q&A.");
        tt(e4, "Screening Pass", 10_000, 220, 88, 0, TicketType.Status.ON_SALE);

        Event e5 = event(org, "Startup Mixer Baghdad", "startup-mixer-baghdad",
                Event.Category.BUSINESS, "Baghdad", "The Station", "Karrada, Baghdad",
                base.plusDays(3).withHour(18), base.plusDays(3).withHour(21),
                "Monthly meetup for founders, freelancers and the simply curious. First drink on us.");
        tt(e5, "RSVP", 0, 150, 96, 0, TicketType.Status.ON_SALE);

        Event e6 = event(org, "Mosul Heritage Walk", "mosul-heritage-walk",
                Event.Category.COMMUNITY, "Mosul", "Old City", "Al-Nuri Mosque gate, Mosul",
                base.plusDays(7).withHour(8), base.plusDays(7).withHour(12),
                "A guided morning walk through the rebuilt Old City with local historians.");
        tt(e6, "Walk Ticket", 5_000, 80, 74, 0, TicketType.Status.ON_SALE);

        Event e7 = event(org, "Karbala Book Fair", "karbala-book-fair",
                Event.Category.EDUCATION, "Karbala", "Karbala Expo", "Expo grounds, Karbala",
                base.plusDays(30).withHour(10), base.plusDays(33).withHour(21),
                "Four days, 120 publishers, author signings and children's readings.");
        tt(e7, "Entry", 0, 10_000, 63, 0, TicketType.Status.ON_SALE);

        Event e8 = event(org, "Duhok Mountain Marathon", "duhok-mountain-marathon",
                Event.Category.SPORTS, "Duhok", "Duhok Dam", "Duhok Dam start line",
                base.plusDays(35).withHour(6), base.plusDays(35).withHour(14),
                "21K and 10K trail routes above Duhok Dam. Finisher medals, timing chips, water stations.");
        tt(e8, "10K Entry", 15_000, 600, 141, 0, TicketType.Status.ON_SALE);
        tt(e8, "21K Entry", 25_000, 400, 97, 1, TicketType.Status.ON_SALE);

        // demo promo code: EARLY20 → 20% off, all events, 100 uses
        jdbc.update("""
                INSERT INTO promo_codes (organization_id, event_id, code, kind, value, max_uses)
                VALUES (?, NULL, 'EARLY20', 'PERCENT', 20, 100)
                ON CONFLICT DO NOTHING
                """, org.getId());
        // demo staff member for door check-in
        User sara = user("sara@zainevents.iq", "Sara Kareem", "Password123!", User.Role.HOST);
        jdbc.update("""
                INSERT INTO org_members (organization_id, user_id, role) VALUES (?, ?, 'STAFF')
                ON CONFLICT (organization_id, user_id) DO NOTHING
                """, org.getId(), sara.getId());

        // likes + follows for realistic counts
        like(amira, e1); like(omar, e1); like(amira, e2); like(omar, e4); like(amira, e5); like(omar, e8);
        follow(amira, org); follow(omar, org);

        log.info("Demo seed complete: 8 events for organizer @{}", DEMO_HANDLE);
        seedDemoOrders();
    }

    private void seedScaleData(int target) {
        long existing = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE slug LIKE 'scale-%'", Long.class);
        int toCreate = (int) Math.max(0, target - existing);
        if (toCreate == 0) {
            log.info("Scale seed already at {} events — skipping", existing);
            return;
        }
        log.info("Scale seeding {} synthetic events …", toCreate);

        // Synthetic volume lives under its OWN organizer so the demo host's
        // dashboard, earnings and attendee selects stay realistic.
        Organization org = organizations.findByHandle("scaletest").orElseGet(() -> {
            User owner = user("scale-host@ievent.iq", "Scale Host", "Password123!", User.Role.HOST);
            Organization o = new Organization();
            o.setOwnerUserId(owner.getId());
            o.setName("Scale Test Events");
            o.setHandle("scaletest");
            o.setCity("Baghdad");
            return organizations.save(o);
        });

        Event.Category[] cats = Event.Category.values();
        Random rnd = new Random(42);
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS);

        for (int i = 0; i < toCreate; i++) {
            long n = existing + i;
            Event.Category cat = cats[rnd.nextInt(cats.length)];
            String city = CITIES[rnd.nextInt(CITIES.length)];
            Event e = event(org,
                    "Scale Event #" + n + " · " + city,
                    "scale-" + n,
                    cat, city, "Venue " + n, "Address " + n,
                    base.plusDays(1 + rnd.nextInt(90)).withHour(10 + rnd.nextInt(10)),
                    null,
                    "Synthetic event for load testing.");
            long price = rnd.nextInt(5) == 0 ? 0 : (5 + rnd.nextInt(20)) * 5_000L;
            tt(e, "General", price, 100 + rnd.nextInt(900), rnd.nextInt(100), 0, TicketType.Status.ON_SALE);
        }
        log.info("Scale seed complete ({} total synthetic events)", target);
    }

    // ---- helpers ----

    private User user(String email, String name, String rawPassword, User.Role role) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            u.setFullName(name);
            u.setPhone(null);
            u.setPasswordHash(passwordEncoder.encode(rawPassword));
            u.setRole(role);
            return users.save(u);
        });
    }

    private Event event(Organization org, String title, String slug, Event.Category cat,
                        String city, String venueName, String venueAddress,
                        OffsetDateTime startsAt, OffsetDateTime endsAt, String description) {
        Event e = new Event();
        e.setOrganization(org);
        e.setTitle(title);
        e.setSlug(slug);
        e.setCategory(cat);
        e.setCity(city);
        e.setVenueName(venueName);
        e.setVenueAddress(venueAddress);
        e.setStartsAt(startsAt);
        e.setEndsAt(endsAt);
        // Same rule as the V23 backfill: an end more than ~a day out means the
        // seed event is genuinely multi-day (e.g. the book fair), so it renders
        // as a date range rather than one day with a strange end time.
        if (endsAt != null && java.time.Duration.between(startsAt, endsAt).toHours() > 20) {
            e.setDatePrecision(Event.PRECISION_RANGE);
        }
        e.setDescription(description == null ? "" : description.strip());
        e.setStatus(Event.Status.LIVE);
        e.setCoverTheme(iq.ievent.service.Format.coverTheme(cat));
        return events.save(e);
    }

    private void tt(Event e, String name, long price, int qty, int sold, int order, TicketType.Status status) {
        TicketType t = new TicketType();
        t.setEvent(e);
        t.setName(name);
        t.setPriceIqd(price);
        t.setQuantity(qty);
        t.setSold(sold);
        t.setSortOrder(order);
        t.setStatus(status);
        ticketTypes.save(t);
    }

    private void like(User u, Event e) {
        jdbc.update("INSERT INTO event_likes (user_id, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                u.getId(), e.getId());
    }

    private void follow(User u, Organization o) {
        jdbc.update("INSERT INTO follows (user_id, organization_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                u.getId(), o.getId());
    }
}
