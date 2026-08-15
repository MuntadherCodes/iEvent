package iq.ievent.web;

import iq.ievent.domain.Event;
import iq.ievent.domain.Order;
import iq.ievent.domain.Organization;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.TicketType;
import iq.ievent.domain.User;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.repo.TicketTypeRepository;
import iq.ievent.service.Format;
import iq.ievent.service.HostService;
import iq.ievent.service.OrderService;
import iq.ievent.service.UserService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Host dashboard (everything under /host). Every request resolves the current
 * user's organization; without one the user is sent to /host/start (onboarding).
 *
 * Template contracts (all pages also get: currentUser, org):
 *  host/start.html      — form fields orgName, handle, city, bio
 *  host/dashboard.html  — stats (HostService.HostStats + labels revenueLabel), upcoming (List<EventRow>),
 *                         recentOrders (List<OrderRow>), pendingCount (long)
 *  host/events.html     — eventRows (List<EventRow>)
 *  host/event-form.html — categories (PageController.CATEGORIES), cities (List<String>), form re-post target /host/events/new
 *  host/event-console.html — ev (EventRow), ticketRows (List<TicketTypeRow>), pendingCount, stats per event
 *  host/orders.html     — orders (Page<OrderRow>), statusFilter (String), pendingCount
 *  host/attendees.html  — ev (EventRow or null), eventOptions (List<EventRow>), rows (List<AttendeeRow>), q
 *  host/checkin.html    — eventOptions, ev, result (flash CheckinResult), counts {in,total}
 *  host/settings-payments.html — org, saved (flash)
 */
@Controller
@RequestMapping("/host")
public class HostController {

    public record EventRow(Long id, String slug, String title, String statusLabel, String dateLine,
                           String city, String venueName, long sold, long capacity, String salesLabel,
                           String revenueLabel) {}

    public record OrderRow(Long id, String orderCode, String buyerName, String buyerEmail,
                           String eventTitle, String itemsLabel, String totalLabel, String methodLabel,
                           String statusLabel, boolean pending, String createdLine, boolean hasReceipt,
                           String transferReference) {}

    public record TicketTypeRow(Long id, String name, String priceLabel, int quantity, int sold,
                                String statusLabel) {}

    public record AttendeeRow(Long ticketId, String holderName, String typeName, String orderCode,
                              String code, boolean checkedIn, String checkedInLine) {}

    public record CheckinResult(boolean ok, String message, String holderName, String typeName) {}

    private final UserService userService;
    private final HostService hostService;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final TicketTypeRepository ticketTypes;

    private final String baseUrl;

    public HostController(UserService userService, HostService hostService, OrderService orderService,
                          OrderRepository orders, TicketRepository tickets, TicketTypeRepository ticketTypes,
                          @org.springframework.beans.factory.annotation.Value("${app.base-url}") String baseUrl) {
        this.userService = userService;
        this.hostService = hostService;
        this.orderService = orderService;
        this.orders = orders;
        this.tickets = tickets;
        this.ticketTypes = ticketTypes;
        this.baseUrl = baseUrl;
    }

    private User user(UserDetails principal) {
        User u = principal == null ? null : userService.byEmail(principal.getUsername());
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return u;
    }

    // ---------- onboarding ----------

    @GetMapping("/start")
    public String start(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        if (hostService.organizationOf(u).isPresent()) return "redirect:/host";
        model.addAttribute("currentUser", u);
        return "host/start";
    }

    @PostMapping("/start")
    public String createOrg(@AuthenticationPrincipal UserDetails principal,
                            @RequestParam String orgName,
                            @RequestParam(required = false) String handle,
                            @RequestParam(required = false) String city,
                            @RequestParam(required = false) String bio,
                            RedirectAttributes redirect) {
        User u = user(principal);
        if (hostService.organizationOf(u).isPresent()) return "redirect:/host";
        if (orgName == null || orgName.isBlank()) {
            redirect.addFlashAttribute("error", "Give your organizer profile a name.");
            return "redirect:/host/start";
        }
        hostService.createOrganization(u, orgName, handle, city, bio);
        return "redirect:/host";
    }

    // ---------- dashboard ----------

    @GetMapping({"", "/"})
    @Transactional(readOnly = true)
    public String dashboard(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";

        HostService.HostStats stats = hostService.stats(org.getId());
        List<EventRow> upcoming = hostService.eventsOf(org.getId()).stream()
                .filter(e -> e.getStartsAt().isAfter(OffsetDateTime.now().minusDays(1)))
                .limit(5).map(this::toRow).toList();
        Page<Order> recent = orders.findForOrganization(org.getId(), null, PageRequest.of(0, 5));

        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("stats", stats);
        model.addAttribute("revenueLabel", Format.iqd(stats.revenueIqd()));
        model.addAttribute("upcoming", upcoming);
        model.addAttribute("recentOrders", recent.getContent().stream().map(this::toRow).toList());
        model.addAttribute("pendingCount", stats.pendingOrders());
        model.addAttribute("salesPoints", hostService.dailySales(org.getId()));
        return "host/dashboard";
    }

    // ---------- events ----------

    @GetMapping("/events")
    @Transactional(readOnly = true)
    public String events(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("eventRows", hostService.eventsOf(org.getId()).stream().map(this::toRow).toList());
        model.addAttribute("pendingCount", hostService.stats(org.getId()).pendingOrders());
        return "host/events";
    }

    @GetMapping("/events/new")
    public String newEvent(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("categories", PageController.CATEGORIES);
        return "host/event-form";
    }

    @PostMapping("/events/new")
    public String createEvent(@AuthenticationPrincipal UserDetails principal,
                              @RequestParam String title,
                              @RequestParam String category,
                              @RequestParam String city,
                              @RequestParam(required = false) String venueName,
                              @RequestParam(required = false) String venueAddress,
                              @RequestParam String date,
                              @RequestParam String startTime,
                              @RequestParam(required = false) String endTime,
                              @RequestParam(required = false) String description,
                              @RequestParam(name = "ttName", required = false) List<String> ttNames,
                              @RequestParam(name = "ttPrice", required = false) List<String> ttPrices,
                              @RequestParam(name = "ttQty", required = false) List<String> ttQtys,
                              @RequestParam(name = "action", defaultValue = "draft") String action,
                              RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        try {
            List<HostService.TicketTypeForm> forms = new ArrayList<>();
            if (ttNames != null) {
                for (int i = 0; i < ttNames.size(); i++) {
                    String name = ttNames.get(i);
                    if (name == null || name.isBlank()) continue;
                    long price = 0;
                    int qty = 0;
                    try { price = Long.parseLong(ttPrices.get(i).replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                    try { qty = Integer.parseInt(ttQtys.get(i).replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                    forms.add(new HostService.TicketTypeForm(name, price, qty));
                }
            }
            Event created = hostService.createEvent(org, title, Event.Category.valueOf(category), city,
                    venueName, venueAddress, LocalDate.parse(date), LocalTime.parse(startTime),
                    endTime == null || endTime.isBlank() ? null : LocalTime.parse(endTime),
                    description, forms);
            if ("publish".equals(action)) {
                hostService.publish(created);
                redirect.addFlashAttribute("published", true);
            }
            return "redirect:/host/events/" + created.getId();
        } catch (Exception e) {
            redirect.addFlashAttribute("error",
                    "Could not create the event — check the required fields. (" + e.getMessage() + ")");
            return "redirect:/host/events/new";
        }
    }

    @GetMapping("/events/{id}")
    @Transactional(readOnly = true)
    public String eventConsole(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<TicketTypeRow> ttRows = ticketTypes.findByEventIdOrderBySortOrderAsc(ev.getId()).stream()
                .map(tt -> new TicketTypeRow(tt.getId(), tt.getName(),
                        Format.priceLabel(tt.getPriceIqd()), tt.getQuantity(), tt.getSold(),
                        statusLabel(tt.getStatus().name())))
                .toList();
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("ev", toRow(ev));
        model.addAttribute("isLive", ev.getStatus() == Event.Status.LIVE);
        model.addAttribute("isDraft", ev.getStatus() == Event.Status.DRAFT);
        model.addAttribute("ticketRows", ttRows);
        model.addAttribute("checkedIn", tickets.countByEventIdAndStatus(ev.getId(), Ticket.Status.CHECKED_IN));
        model.addAttribute("ticketsTotal", tickets.countByEventId(ev.getId()));
        model.addAttribute("shareBase", baseUrl);
        return "host/event-console";
    }

    @GetMapping("/events/{id}/edit")
    @Transactional(readOnly = true)
    public String editEvent(@PathVariable Long id,
                            @AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        java.time.ZonedDateTime z = ev.getStartsAt().atZoneSameInstant(Format.BAGHDAD);
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("ev", toRow(ev));
        model.addAttribute("evEntity", new EventEditView(ev.getTitle(), ev.getCategory().name(),
                ev.getCity(), ev.getVenueName(), ev.getVenueAddress(),
                z.toLocalDate().toString(), z.toLocalTime().toString().substring(0, 5),
                ev.getEndsAt() == null ? "" : ev.getEndsAt().atZoneSameInstant(Format.BAGHDAD)
                        .toLocalTime().toString().substring(0, 5),
                ev.getDescription()));
        model.addAttribute("ticketRows",
                ticketTypes.findByEventIdOrderBySortOrderAsc(ev.getId()).stream()
                        .map(tt -> new TicketTypeEditRow(tt.getId(), tt.getName(), tt.getPriceIqd(),
                                tt.getQuantity(), tt.getSold(), tt.getStatus().name()))
                        .toList());
        model.addAttribute("categories", PageController.CATEGORIES);
        return "host/event-edit";
    }

    public record EventEditView(String title, String category, String city, String venueName,
                                String venueAddress, String date, String startTime, String endTime,
                                String description) {}

    public record TicketTypeEditRow(Long id, String name, long priceIqd, int quantity, int sold,
                                    String status) {}

    @PostMapping("/events/{id}/edit")
    public String updateEvent(@PathVariable Long id,
                              @AuthenticationPrincipal UserDetails principal,
                              @RequestParam String title,
                              @RequestParam String category,
                              @RequestParam String city,
                              @RequestParam(required = false) String venueName,
                              @RequestParam(required = false) String venueAddress,
                              @RequestParam String date,
                              @RequestParam String startTime,
                              @RequestParam(required = false) String endTime,
                              @RequestParam(required = false) String description,
                              RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            hostService.updateEvent(ev, title, Event.Category.valueOf(category), city,
                    venueName, venueAddress, LocalDate.parse(date), LocalTime.parse(startTime),
                    endTime == null || endTime.isBlank() ? null : LocalTime.parse(endTime), description);
            redirect.addFlashAttribute("saved", true);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Could not save — check the fields. (" + e.getMessage() + ")");
        }
        return "redirect:/host/events/" + id + "/edit";
    }

    @PostMapping("/events/{id}/tickets")
    public String upsertTicket(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails principal,
                               @RequestParam(required = false) Long ttId,
                               @RequestParam String name,
                               @RequestParam(defaultValue = "0") long price,
                               @RequestParam(defaultValue = "0") int quantity,
                               @RequestParam(defaultValue = "ON_SALE") String status,
                               RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        Event ev = hostService.eventOf(org.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String error = hostService.upsertTicketType(ev, ttId, name, price, quantity, status);
        if (error != null) redirect.addFlashAttribute("error", error);
        else redirect.addFlashAttribute("saved", true);
        return "redirect:/host/events/" + id + "/edit";
    }

    private void requireManage(User u) {
        if (hostService.accessOf(u).map(a -> !a.canManage()).orElse(true)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @PostMapping("/events/{id}/publish")
    public String publish(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        hostService.eventOf(org.getId(), id).ifPresent(hostService::publish);
        return "redirect:/host/events/" + id;
    }

    @PostMapping("/events/{id}/unpublish")
    public String unpublish(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        hostService.eventOf(org.getId(), id).ifPresent(hostService::unpublish);
        return "redirect:/host/events/" + id;
    }

    // ---------- orders ----------

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public String orders(@AuthenticationPrincipal UserDetails principal,
                         @RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        Order.Status filter = null;
        if ("pending".equalsIgnoreCase(status)) filter = Order.Status.PENDING_CONFIRMATION;
        else if ("confirmed".equalsIgnoreCase(status)) filter = Order.Status.CONFIRMED;
        else if ("rejected".equalsIgnoreCase(status)) filter = Order.Status.REJECTED;
        Page<Order> result = orders.findForOrganization(org.getId(), filter,
                PageRequest.of(Math.max(0, page), 20));
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("orders", result.map(this::toRow));
        model.addAttribute("statusFilter", status == null ? "" : status);
        model.addAttribute("pendingCount",
                orders.countForOrganizationByStatus(org.getId(), Order.Status.PENDING_CONFIRMATION));
        return "host/orders";
    }

    @PostMapping("/orders/{id}/approve")
    public String approve(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                          RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        try {
            Order o = orderService.approve(id, org.getId());
            redirect.addFlashAttribute("actioned", "Order " + o.getOrderCode() + " approved — tickets emailed to the buyer.");
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/host/orders?status=pending";
    }

    @PostMapping("/orders/{id}/reject")
    public String reject(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                         RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        try {
            Order o = orderService.reject(id, org.getId());
            redirect.addFlashAttribute("actioned", "Order " + o.getOrderCode() + " rejected — the buyer was notified.");
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/host/orders?status=pending";
    }

    @GetMapping("/orders/{id}/receipt")
    @Transactional(readOnly = true)
    public ResponseEntity<FileSystemResource> receipt(@PathVariable Long id,
                                                      @AuthenticationPrincipal UserDetails principal) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Order order = orders.findById(id)
                .filter(o -> o.getEvent().getOrganization().getId().equals(org.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (order.getReceiptPath() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path = Path.of(order.getReceiptPath());
        MediaType type = order.getReceiptPath().endsWith(".pdf")
                ? MediaType.APPLICATION_PDF : MediaType.IMAGE_JPEG;
        if (order.getReceiptPath().endsWith(".png")) type = MediaType.IMAGE_PNG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(path));
    }

    // ---------- attendees & check-in ----------

    @GetMapping("/attendees")
    @Transactional(readOnly = true)
    public String attendees(@AuthenticationPrincipal UserDetails principal,
                            @RequestParam(required = false) Long event,
                            @RequestParam(required = false) String q,
                            Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        List<EventRow> options = hostService.eventsOf(org.getId()).stream().map(this::toRow).toList();
        EventRow selected = null;
        List<AttendeeRow> rows = List.of();
        if (event != null) {
            Event ev = hostService.eventOf(org.getId(), event).orElse(null);
            if (ev != null) {
                selected = toRow(ev);
                rows = tickets.searchForEvent(ev.getId(),
                                q == null || q.isBlank() ? null : q.trim()).stream()
                        .map(this::toRow).toList();
            }
        } else if (!options.isEmpty()) {
            return "redirect:/host/attendees?event=" + options.get(0).id();
        }
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("eventOptions", options);
        model.addAttribute("ev", selected);
        model.addAttribute("rows", rows);
        model.addAttribute("q", q == null ? "" : q);
        return "host/attendees";
    }

    @PostMapping("/tickets/{id}/checkin")
    @Transactional
    public String checkin(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                          @RequestHeader(value = "Referer", required = false) String referer) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        tickets.findById(id)
                .filter(t -> t.getEvent().getOrganization().getId().equals(org.getId()))
                .filter(t -> t.getStatus() == Ticket.Status.VALID)
                .ifPresent(t -> {
                    t.setStatus(Ticket.Status.CHECKED_IN);
                    t.setCheckedInAt(OffsetDateTime.now());
                    tickets.save(t);
                });
        return "redirect:" + (referer == null ? "/host/attendees" : referer);
    }

    @PostMapping("/tickets/{id}/undo-checkin")
    @Transactional
    public String undoCheckin(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal,
                              @RequestHeader(value = "Referer", required = false) String referer) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        tickets.findById(id)
                .filter(t -> t.getEvent().getOrganization().getId().equals(org.getId()))
                .filter(t -> t.getStatus() == Ticket.Status.CHECKED_IN)
                .ifPresent(t -> {
                    t.setStatus(Ticket.Status.VALID);
                    t.setCheckedInAt(null);
                    tickets.save(t);
                });
        return "redirect:" + (referer == null ? "/host/attendees" : referer);
    }

    @GetMapping("/checkin")
    @Transactional(readOnly = true)
    public String checkinPage(@AuthenticationPrincipal UserDetails principal,
                              @RequestParam(required = false) Long event,
                              Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        List<EventRow> options = hostService.eventsOf(org.getId()).stream().map(this::toRow).toList();
        EventRow selected = null;
        long in = 0, total = 0;
        if (event != null) {
            Event ev = hostService.eventOf(org.getId(), event).orElse(null);
            if (ev != null) {
                selected = toRow(ev);
                in = tickets.countByEventIdAndStatus(ev.getId(), Ticket.Status.CHECKED_IN);
                total = tickets.countByEventId(ev.getId());
            }
        } else if (!options.isEmpty()) {
            return "redirect:/host/checkin?event=" + options.get(0).id();
        }
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        model.addAttribute("eventOptions", options);
        model.addAttribute("ev", selected);
        model.addAttribute("checkedIn", in);
        model.addAttribute("ticketsTotal", total);
        return "host/checkin";
    }

    @PostMapping("/checkin")
    @Transactional
    public String checkinByCode(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam Long event,
                                @RequestParam String code,
                                RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        String clean = code == null ? "" : code.trim().toUpperCase().replaceAll(".*/T/", "");
        Ticket t = tickets.findByCode(clean).orElse(null);
        CheckinResult result;
        if (t == null || !t.getEvent().getOrganization().getId().equals(org.getId())) {
            result = new CheckinResult(false, "Ticket not found for your events.", null, null);
        } else if (!t.getEvent().getId().equals(event)) {
            result = new CheckinResult(false, "Ticket belongs to a different event: " + t.getEvent().getTitle(), t.getHolderName(), t.getTicketType().getName());
        } else if (t.getStatus() == Ticket.Status.CHECKED_IN) {
            result = new CheckinResult(false, "Already checked in " + (t.getCheckedInAt() == null ? "" : Format.cardDateLine(t.getCheckedInAt())), t.getHolderName(), t.getTicketType().getName());
        } else if (t.getStatus() == Ticket.Status.VOID) {
            result = new CheckinResult(false, "Ticket is void.", t.getHolderName(), t.getTicketType().getName());
        } else {
            t.setStatus(Ticket.Status.CHECKED_IN);
            t.setCheckedInAt(OffsetDateTime.now());
            tickets.save(t);
            result = new CheckinResult(true, "Checked in", t.getHolderName(), t.getTicketType().getName());
        }
        redirect.addFlashAttribute("result", result);
        return "redirect:/host/checkin?event=" + event;
    }

    // ---------- payment settings ----------

    @GetMapping("/settings/payments")
    public String paymentSettings(@AuthenticationPrincipal UserDetails principal, Model model) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        model.addAttribute("currentUser", u);
        model.addAttribute("org", org);
        return "host/settings-payments";
    }

    @PostMapping("/settings/payments")
    public String savePaymentSettings(@AuthenticationPrincipal UserDetails principal,
                                      @RequestParam(defaultValue = "false") boolean enabled,
                                      @RequestParam(required = false) String cardNumber,
                                      @RequestParam(required = false) String accountName,
                                      @RequestParam(required = false) String walletBank,
                                      @RequestParam(required = false) String instructions,
                                      RedirectAttributes redirect) {
        User u = user(principal);
        Organization org = hostService.organizationOf(u).orElse(null);
        if (org == null) return "redirect:/host/start";
        requireManage(u);
        hostService.savePaymentSettings(org, enabled, cardNumber, accountName, walletBank, instructions);
        redirect.addFlashAttribute("saved", true);
        return "redirect:/host/settings/payments";
    }

    // ---------- mapping ----------

    private EventRow toRow(Event e) {
        List<TicketType> tts = ticketTypes.findByEventIdOrderBySortOrderAsc(e.getId());
        long sold = tts.stream().mapToLong(TicketType::getSold).sum();
        long cap = tts.stream().mapToLong(TicketType::getQuantity).sum();
        long revenue = tts.stream().mapToLong(t -> t.getSold() * t.getPriceIqd()).sum();
        return new EventRow(e.getId(), e.getSlug(), e.getTitle(), statusLabel(e.getStatus().name()),
                Format.cardDateLine(e.getStartsAt()), e.getCity(), e.getVenueName(),
                sold, cap, sold + " / " + cap, Format.iqd(revenue));
    }

    private OrderRow toRow(Order o) {
        String items = o.getItems().stream()
                .map(i -> i.getQuantity() + "× " + i.getTicketType().getName())
                .reduce((a, b) -> a + ", " + b).orElse("—");
        return new OrderRow(o.getId(), o.getOrderCode(), o.getBuyerName(), o.getBuyerEmail(),
                o.getEvent().getTitle(), items, Format.iqd(o.getTotalIqd()),
                o.getPaymentMethod() == Order.PaymentMethod.FREE ? "Free" : "Direct transfer",
                statusLabel(o.getStatus().name()),
                o.getStatus() == Order.Status.PENDING_CONFIRMATION,
                Format.cardDateLine(o.getCreatedAt()),
                o.getReceiptPath() != null,
                o.getTransferReference());
    }

    private AttendeeRow toRow(Ticket t) {
        return new AttendeeRow(t.getId(), t.getHolderName(), t.getTicketType().getName(),
                t.getOrder().getOrderCode(), t.getCode(),
                t.getStatus() == Ticket.Status.CHECKED_IN,
                t.getCheckedInAt() == null ? null : Format.cardDateLine(t.getCheckedInAt()));
    }

    private static String statusLabel(String name) {
        return switch (name) {
            case "PENDING_CONFIRMATION" -> "Pending confirmation";
            case "CONFIRMED" -> "Confirmed";
            case "REJECTED" -> "Rejected";
            case "CANCELLED" -> "Cancelled";
            case "DRAFT" -> "Draft";
            case "LIVE" -> "Live";
            case "ENDED" -> "Ended";
            case "ON_SALE" -> "On sale";
            case "SOLD_OUT" -> "Sold out";
            case "HIDDEN" -> "Hidden";
            default -> name;
        };
    }
}
