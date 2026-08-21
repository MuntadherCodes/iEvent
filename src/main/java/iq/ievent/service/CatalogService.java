package iq.ievent.service;

import iq.ievent.domain.Event;
import iq.ievent.domain.EventImage;
import iq.ievent.domain.Organization;
import iq.ievent.domain.TicketType;
import iq.ievent.repo.EventImageRepository;
import iq.ievent.repo.EventRepository;
import iq.ievent.repo.LikeCountRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.web.dto.Views.CityCount;
import iq.ievent.web.dto.Views.EventCard;
import iq.ievent.web.dto.Views.EventDetail;
import iq.ievent.web.dto.Views.OrganizerView;
import iq.ievent.web.dto.Views.TicketTypeView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final EventRepository events;
    private final TicketTypeRepository ticketTypes;
    private final LikeCountRepository likeCounts;
    private final iq.ievent.repo.PaymentMethodRepository paymentMethods;
    private final EventImageRepository eventImages;

    public CatalogService(EventRepository events,
                          TicketTypeRepository ticketTypes,
                          LikeCountRepository likeCounts,
                          iq.ievent.repo.PaymentMethodRepository paymentMethods,
                          EventImageRepository eventImages) {
        this.events = events;
        this.ticketTypes = ticketTypes;
        this.likeCounts = likeCounts;
        this.paymentMethods = paymentMethods;
        this.eventImages = eventImages;
    }

    /** Primary cover + every extra image, in display order — 2+ means the
     *  event page renders a slider instead of a single static cover. */
    private List<String> galleryUrls(Event e, String primary) {
        List<String> out = new java.util.ArrayList<>();
        if (primary != null) out.add(primary);
        eventImages.findByEventIdOrderBySortOrderAsc(e.getId()).forEach(img -> out.add(img.getUrl()));
        return out;
    }

    public List<EventCard> upcomingThisWeek(int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Event> list = events.findUpcomingWindow(Event.Status.LIVE, now, now.plusDays(7),
                PageRequest.of(0, limit));
        return toCards(list);
    }

    public List<EventCard> trending(int limit) {
        return toCards(events.findTrending(PageRequest.of(0, limit)));
    }

    /**
     * @param price  null/"all" | "free" | "paid"
     * @param sort   "soonest" (default) | "price" | "popular"
     */
    public Page<EventCard> search(String q, String category, String city, String price,
                                  String when, String sort, Pageable pageable) {
        String qn = normalize(q);
        String cat = normalize(category);
        String cty = normalize(city);
        boolean freeOnly = "free".equalsIgnoreCase(normalize(price) == null ? "" : price.trim());
        boolean paidOnly = "paid".equalsIgnoreCase(normalize(price) == null ? "" : price.trim());
        OffsetDateTime fromTs = null;
        OffsetDateTime toTs = null;
        if (when != null) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(Format.BAGHDAD);
            switch (when) {
                case "today" -> {
                    fromTs = now.toLocalDate().atStartOfDay(Format.BAGHDAD).toOffsetDateTime();
                    toTs = now.toLocalDate().plusDays(1).atStartOfDay(Format.BAGHDAD).toOffsetDateTime();
                }
                case "tomorrow" -> {
                    fromTs = now.toLocalDate().plusDays(1).atStartOfDay(Format.BAGHDAD).toOffsetDateTime();
                    toTs = now.toLocalDate().plusDays(2).atStartOfDay(Format.BAGHDAD).toOffsetDateTime();
                }
                case "weekend" -> {
                    // Iraqi weekend: Friday + Saturday
                    java.time.LocalDate d = now.toLocalDate();
                    while (d.getDayOfWeek() != java.time.DayOfWeek.FRIDAY) d = d.plusDays(1);
                    fromTs = d.atStartOfDay(Format.BAGHDAD).toOffsetDateTime();
                    toTs = d.plusDays(2).atStartOfDay(Format.BAGHDAD).toOffsetDateTime();
                }
                case "week" -> {
                    fromTs = now.toOffsetDateTime();
                    toTs = now.plusDays(7).toOffsetDateTime();
                }
                case "month" -> {
                    fromTs = now.toOffsetDateTime();
                    toTs = now.plusDays(31).toOffsetDateTime();
                }
                default -> { }
            }
        }
        String sortKey = switch (sort == null ? "" : sort) {
            case "price", "popular" -> sort;
            default -> "soonest";
        };
        Page<Event> page = events.search(qn, cat == null ? null : cat.toUpperCase(), cty,
                freeOnly, paidOnly, fromTs, toTs, sortKey, pageable);
        List<EventCard> cards = toCards(page.getContent());
        return new PageImpl<>(cards, pageable, page.getTotalElements());
    }

    /** Fire-and-forget page-view counter; separate txn, never breaks the page. */
    @Transactional
    public void recordView(String slug) {
        try {
            events.findBySlug(slug).ifPresent(e -> events.incrementViewCount(e.getId()));
        } catch (Exception ignored) {
            // view counting must never break event rendering
        }
    }

    public List<CityCount> liveCities() {
        return events.countLiveByCity().stream()
                .map(r -> new CityCount(r.getCity(), r.getN()))
                .collect(Collectors.toList());
    }

    public Optional<EventDetail> eventDetail(String slug) {
        return events.findBySlug(slug)
                .filter(e -> e.getStatus() == Event.Status.LIVE || e.getStatus() == Event.Status.ENDED)
                .map(this::toDetail);
    }

    /** Cards for an explicit id list (e.g. favorites), preserving the given order. */
    public List<EventCard> cardsForIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<Event> found = events.findAllById(ids);
        Map<Long, Event> byId = found.stream()
                .collect(Collectors.toMap(Event::getId, e -> e));
        List<Event> ordered = ids.stream().map(byId::get)
                .filter(java.util.Objects::nonNull).toList();
        return toCards(ordered);
    }

    public List<EventCard> upcomingForOrganization(Long orgId, int limit) {
        return toCards(events.findByOrganizationIdAndStatusAndStartsAtAfterOrderByStartsAtAsc(
                orgId, Event.Status.LIVE, OffsetDateTime.now(), PageRequest.of(0, limit)));
    }

    /** Checkout view of one payment method the buyer can pick. */
    public record PaymentMethodView(Long id, String label, String accountNumber,
                                    String accountName, String instructions, String qrUrl) {}

    /** All enabled direct-payment methods of the event's organizer (empty when
     *  direct payments are off). */
    public List<PaymentMethodView> paymentMethodsFor(String slug) {
        return events.findBySlug(slug)
                .map(Event::getOrganization)
                .filter(Organization::isDirectPaymentsEnabled)
                .map(o -> paymentMethods.findByOrganizationIdAndEnabledTrueOrderBySortOrderAscIdAsc(o.getId())
                        .stream()
                        .map(m -> new PaymentMethodView(
                                m.getId(), m.getLabel(), m.getAccountNumber(), m.getAccountName(),
                                m.getInstructions(),
                                m.getQrImagePath() == null ? null : "/media/payment-qr/" + m.getId()))
                        .toList())
                .orElse(List.of());
    }

    public java.util.Optional<iq.ievent.web.dto.Views.DirectPayInfo> directPayInfo(String slug) {
        return events.findBySlug(slug)
                .map(Event::getOrganization)
                .filter(Organization::isDirectPaymentsEnabled)
                .map(o -> new iq.ievent.web.dto.Views.DirectPayInfo(
                        o.getPayCardNumber(), o.getPayAccountName(),
                        o.getPayWalletBank(), o.getPayInstructions()));
    }

    public List<EventCard> related(String slug, int limit) {
        return events.findBySlug(slug)
                .map(e -> toCards(events.findRelated(e.getId(), e.getCategory().name(), e.getCity(),
                        PageRequest.of(0, limit))))
                .orElse(List.of());
    }

    // ---- mapping ----

    private List<EventCard> toCards(List<Event> list) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(Event::getId).toList();
        Map<Long, Long> likes = likeCounts.likesForEvents(ids);
        Map<Long, Long> minPrices = ticketTypes.minPricesForEvents(ids).stream()
                .collect(Collectors.toMap(TicketTypeRepository.MinPriceRow::getEventId,
                                          TicketTypeRepository.MinPriceRow::getMinPrice));
        List<EventCard> out = new ArrayList<>(list.size());
        for (Event e : list) {
            out.add(new EventCard(
                    e.getSlug(),
                    e.getTitle(),
                    Format.categoryLabel(e.getCategory()),
                    e.getCoverTheme(),
                    Format.coverUrl(e),
                    e.getCity(),
                    Format.venueDisplay(e.getVenueName(), e.getLocationType()),
                    Format.cardDateLine(e.getStartsAt()),
                    Format.priceLineFromMin(minPrices.get(e.getId())),
                    likes.getOrDefault(e.getId(), 0L)));
        }
        return out;
    }

    private EventDetail toDetail(Event e) {
        Organization org = e.getOrganization();
        List<TicketType> tts = ticketTypes.findByEventIdOrderBySortOrderAsc(e.getId());
        List<TicketTypeView> ttViews = tts.stream()
                .filter(tt -> tt.getStatus() != TicketType.Status.HIDDEN)
                .map(tt -> new TicketTypeView(
                        tt.getId(), tt.getName(), tt.getPriceIqd(),
                        Format.priceLabel(tt.getPriceIqd()),
                        tt.getStatus().name(), tt.remaining()))
                .toList();

        Long minPrice = tts.stream()
                .filter(tt -> tt.getStatus() == TicketType.Status.ON_SALE)
                .map(TicketType::getPriceIqd)
                .min(Long::compare)
                .orElse(null);

        long likes = likeCounts.likesForEvents(List.of(e.getId())).getOrDefault(e.getId(), 0L);

        OrganizerView organizer = new OrganizerView(
                org.getName(),
                org.getHandle(),
                org.getBio(),
                org.isVerified(),
                Format.compactCount(likeCounts.followersForOrganization(org.getId())),
                likeCounts.eventsHostedForOrganization(org.getId()),
                initialsOf(org.getName()),
                org.getLogoPath() == null || org.getLogoPath().isBlank() ? null : "/media/org-logo/" + org.getId());

        List<String> paragraphs = Arrays.stream(e.getDescription().split("\n\n"))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .toList();

        String primary = Format.coverUrl(e);
        return new EventDetail(
                e.getSlug(), e.getTitle(),
                Format.categoryLabel(e.getCategory()),
                e.getCoverTheme(),
                primary,
                galleryUrls(e, primary),
                e.getCity(), Format.venueDisplay(e.getVenueName(), e.getLocationType()), e.getVenueAddress(),
                Format.longDateLine(e.getStartsAt(), e.getEndsAt()),
                Format.monthShort(e.getStartsAt()),
                Format.dayOfMonth(e.getStartsAt()),
                paragraphs,
                // bare digits — the templates add their own "From"/"IQD" around it
                minPrice == null || minPrice == 0 ? Format.priceLabel(0)
                        : String.format(java.util.Locale.ENGLISH, "%,d", minPrice),
                likes,
                organizer,
                ttViews,
                e.getLocationType(),
                e.isAnnounceOnly(),
                e.getMapsUrl());
    }

    private static String initialsOf(String name) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
