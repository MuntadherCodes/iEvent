package iq.ievent.web;

import iq.ievent.domain.Order;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.User;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.service.CatalogService;
import iq.ievent.service.Format;
import iq.ievent.service.OrderService;
import iq.ievent.service.PromoService;
import iq.ievent.service.QrService;
import iq.ievent.service.UserService;
import iq.ievent.web.dto.Views.EventDetail;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checkout + order confirmation.
 *
 * Template contracts:
 *  checkout.html:  event (EventDetail), quantities (Map<Long,Integer> preselected), currentUser,
 *                  directPay (DirectPayInfo or null), subtotalLabel/feeLabel/totalLabel (String),
 *                  totalQty (int), error (String, optional flash)
 *  confirmation.html: order (OrderView), tickets (List<TicketView>), pending (boolean)
 */
@Controller
public class CheckoutController {

    public record OrderView(String orderCode, String eventTitle, String eventSlug, String dateLine,
                            String venueLine, String buyerName, String buyerEmail, String statusLabel,
                            boolean pending, String subtotalLabel, String feeLabel, String totalLabel,
                            String discountLabel, String promoCode, List<ItemView> items,
                            String transferReference, String receiptName, String organizerName,
                            boolean online, String onlineUrl) {}

    public record ItemView(String name, int quantity, String unitLabel, String lineLabel) {}

    public record TicketView(String code, String typeName, String holderName, String qrSvg, String statusLabel) {}

    private final CatalogService catalog;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final QrService qr;
    private final UserService userService;
    private final PromoService promoService;
    private final iq.ievent.repo.EventRepository eventRepo;
    private final iq.ievent.service.TicketPdfService ticketPdf;

    public CheckoutController(CatalogService catalog, OrderService orderService,
                              OrderRepository orders, TicketRepository tickets,
                              QrService qr, UserService userService,
                              PromoService promoService, iq.ievent.repo.EventRepository eventRepo,
                              iq.ievent.service.TicketPdfService ticketPdf) {
        this.catalog = catalog;
        this.orderService = orderService;
        this.orders = orders;
        this.tickets = tickets;
        this.qr = qr;
        this.userService = userService;
        this.promoService = promoService;
        this.eventRepo = eventRepo;
        this.ticketPdf = ticketPdf;
    }

    private User currentUser(UserDetails principal) {
        if (principal == null) return null;
        return userService.byEmail(principal.getUsername());
    }

    @GetMapping("/events/{slug}/checkout")
    @Transactional(readOnly = true)
    public String checkout(@PathVariable String slug,
                           @RequestParam Map<String, String> params,
                           @AuthenticationPrincipal UserDetails principal,
                           Model model) {
        User user = currentUser(principal);
        EventDetail event = catalog.eventDetail(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));

        Map<Long, Integer> quantities = new HashMap<>();
        boolean explicitQty = false;
        for (String key : params.keySet()) {
            if (key.startsWith("qty-")) { explicitQty = true; break; }
        }
        for (var tt : event.ticketTypes()) {
            String raw = params.get("qty-" + tt.id());
            int q = 0;
            try { q = raw == null ? 0 : Math.max(0, Math.min(10, Integer.parseInt(raw))); }
            catch (NumberFormatException ignored) {}
            quantities.put(tt.id(), q);
        }
        // First visit (no explicit qty-* params, e.g. straight from the event page):
        // preselect one ticket of the first purchasable type so the buyer starts at
        // qty 1 instead of an empty order. Re-posts and deep links with qty-* params
        // are respected exactly as sent.
        if (!explicitQty) {
            for (var tt : event.ticketTypes()) {
                if ("ON_SALE".equals(tt.status()) && tt.remaining() > 0) {
                    quantities.put(tt.id(), 1);
                    break;
                }
            }
        }
        long subtotal = 0;
        long paidCount = 0;
        int totalQty = 0;
        for (var tt : event.ticketTypes()) {
            int q = quantities.get(tt.id());
            subtotal += tt.priceIqd() * q;
            if (tt.priceIqd() > 0) paidCount += q;
            totalQty += q;
        }
        long fee = paidCount * OrderService.BOOKING_FEE_PER_PAID_TICKET;

        // promo preview (?promo=CODE)
        String promo = params.get("promo");
        long discount = 0;
        String promoMsg = null;
        boolean promoOk = false;
        if (promo != null && !promo.isBlank()) {
            var entity = eventRepo.findBySlug(slug).orElse(null);
            var applied = entity == null ? java.util.Optional.<PromoService.Applied>empty()
                    : promoService.preview(entity, promo, subtotal);
            if (applied.isPresent()) {
                discount = applied.get().discountIqd();
                promoOk = true;
                promoMsg = promo.trim().toUpperCase() + " applied — you save " + Format.iqd(discount) + ".";
            } else {
                promoMsg = "Code '" + promo.trim() + "' is not valid for this event.";
            }
        }
        long total = Math.max(0, subtotal - discount) + fee;

        model.addAttribute("currentUser", user);
        model.addAttribute("event", event);
        model.addAttribute("quantities", quantities);
        model.addAttribute("totalQty", totalQty);
        model.addAttribute("subtotalLabel", Format.iqd(subtotal));
        model.addAttribute("feeLabel", Format.iqd(fee));
        model.addAttribute("discountLabel", discount > 0 ? Format.iqd(discount) : null);
        model.addAttribute("promo", promo == null ? "" : promo.trim());
        model.addAttribute("promoOk", promoOk);
        model.addAttribute("promoMsg", promoMsg);
        model.addAttribute("totalLabel", Format.iqd(total));
        model.addAttribute("isFreeOrder", total == 0 && totalQty > 0);
        // Multi-method direct payments: the buyer picks any of the organizer's methods.
        // The legacy single-method block renders only when no methods exist (older orgs).
        List<CatalogService.PaymentMethodView> paymentMethods = catalog.paymentMethodsFor(slug);
        var directPay = catalog.directPayInfo(slug).orElse(null);
        model.addAttribute("paymentMethods", paymentMethods);
        model.addAttribute("directPay", directPay);
        model.addAttribute("hasPayment", !paymentMethods.isEmpty() || directPay != null);
        return "checkout";
    }

    @PostMapping("/events/{slug}/checkout")
    public String placeOrder(@PathVariable String slug,
                             @RequestParam Map<String, String> params,
                             @RequestParam(name = "buyerName") String buyerName,
                             @RequestParam(name = "buyerEmail") String buyerEmail,
                             @RequestParam(name = "buyerPhone", required = false) String buyerPhone,
                             @RequestParam(name = "transferReference", required = false) String transferReference,
                             @RequestParam(name = "receipt", required = false) MultipartFile receipt,
                             @RequestParam(name = "promo", required = false) String promo,
                             @RequestParam(name = "holderName", required = false) List<String> holderNames,
                             @RequestParam(name = "holderEmail", required = false) List<String> holderEmails,
                             @RequestParam(name = "keepUpdated", defaultValue = "false") boolean keepUpdated,
                             @AuthenticationPrincipal UserDetails principal,
                             RedirectAttributes redirect) {
        User user = currentUser(principal);
        if (user == null) {
            return "redirect:/auth/login";
        }
        Map<Long, Integer> quantities = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().startsWith("qty-")) {
                try {
                    quantities.put(Long.parseLong(entry.getKey().substring(4)),
                            Integer.parseInt(entry.getValue()));
                } catch (NumberFormatException ignored) {}
            }
        }
        try {
            Order order = orderService.checkout(user, slug, quantities,
                    buyerName, buyerEmail, buyerPhone, transferReference, receipt,
                    promo, holderNames, holderEmails, keepUpdated);
            return "redirect:/orders/" + order.getOrderCode();
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            StringBuilder qs = new StringBuilder();
            quantities.forEach((id, q) -> qs.append("&qty-").append(id).append("=").append(q));
            return "redirect:/events/" + slug + "/checkout?_e" + qs;
        }
    }

    @GetMapping("/orders/{code}/tickets.pdf")
    @Transactional(readOnly = true)
    public org.springframework.http.ResponseEntity<byte[]> orderTicketsPdf(
            @PathVariable String code,
            @AuthenticationPrincipal UserDetails principal) {
        User user = currentUser(principal);
        Order order = orders.findByOrderCode(code)
                .filter(o -> user != null && o.getBuyerUserId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        java.util.List<iq.ievent.domain.Ticket> list =
                tickets.findByOrderIdOrderByIdAsc(order.getId());
        if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ievent-" + order.getOrderCode() + "-tickets.pdf\"")
                .body(ticketPdf.ticketsPdf(list));
    }

    @GetMapping("/orders/{code}")
    @Transactional(readOnly = true)
    public String confirmation(@PathVariable String code,
                               @AuthenticationPrincipal UserDetails principal,
                               Model model) {
        User user = currentUser(principal);
        Order order = orders.findByOrderCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (user == null || !order.getBuyerUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        boolean pending = order.getStatus() == Order.Status.PENDING_CONFIRMATION;
        boolean confirmed = order.getStatus() == Order.Status.CONFIRMED;

        String statusLabel = switch (order.getStatus()) {
            case PENDING_CONFIRMATION -> "Pending confirmation";
            case CONFIRMED -> "Confirmed";
            case REJECTED -> "Rejected";
            case CANCELLED -> "Cancelled";
            case REFUNDED -> "Refunded";
        };
        String receiptName = null;
        if (order.getReceiptPath() != null) {
            String p = order.getReceiptPath().replace('\\', '/');
            receiptName = p.substring(p.lastIndexOf('/') + 1);
        }

        List<ItemView> items = order.getItems().stream()
                .map(i -> new ItemView(i.getTicketType().getName(), i.getQuantity(),
                        Format.priceLabel(i.getUnitPriceIqd()),
                        Format.iqd(i.getUnitPriceIqd() * i.getQuantity())))
                .toList();
        // Location-aware venue line. The join link is SECRET: it leaves the server
        // only for CONFIRMED orders — never while pending/rejected/cancelled.
        String locType = order.getEvent().getLocationType();
        boolean online = "ONLINE".equals(locType);
        String venueLine = online ? "Online event"
                : "TBA".equals(locType) ? "Location to be announced"
                : (order.getEvent().getVenueName() == null ? "" : order.getEvent().getVenueName() + ", ")
                        + order.getEvent().getCity();
        String joinUrl = confirmed && online
                && order.getEvent().getOnlineUrl() != null && !order.getEvent().getOnlineUrl().isBlank()
                ? order.getEvent().getOnlineUrl() : null;

        OrderView view = new OrderView(order.getOrderCode(), order.getEvent().getTitle(),
                order.getEvent().getSlug(),
                Format.longDateLine(order.getEvent().getStartsAt(), order.getEvent().getEndsAt()),
                venueLine,
                order.getBuyerName(), order.getBuyerEmail(),
                statusLabel,
                pending,
                Format.iqd(order.getSubtotalIqd()), Format.iqd(order.getBookingFeeIqd()),
                Format.iqd(order.getTotalIqd()),
                order.getDiscountIqd() > 0 ? Format.iqd(order.getDiscountIqd()) : null,
                order.getPromoCode(), items,
                order.getTransferReference(), receiptName,
                order.getEvent().getOrganization().getName(),
                online, joinUrl);

        List<TicketView> ticketViews = tickets.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(t -> new TicketView(t.getCode(), t.getTicketType().getName(), t.getHolderName(),
                        qr.ticketQrSvg(t.getCode()), t.getStatus().name()))
                .toList();

        model.addAttribute("currentUser", user);
        model.addAttribute("order", view);
        model.addAttribute("tickets", ticketViews);
        model.addAttribute("pending", pending);
        model.addAttribute("confirmed", confirmed);
        model.addAttribute("closed", !pending && !confirmed);
        return "confirmation";
    }
}
