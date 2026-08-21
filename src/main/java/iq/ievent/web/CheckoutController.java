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
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
    private final MessageSource messages;

    public CheckoutController(CatalogService catalog, OrderService orderService,
                              OrderRepository orders, TicketRepository tickets,
                              QrService qr, UserService userService,
                              PromoService promoService, iq.ievent.repo.EventRepository eventRepo,
                              iq.ievent.service.TicketPdfService ticketPdf,
                              MessageSource messages) {
        this.catalog = catalog;
        this.orderService = orderService;
        this.orders = orders;
        this.tickets = tickets;
        this.qr = qr;
        this.userService = userService;
        this.promoService = promoService;
        this.eventRepo = eventRepo;
        this.ticketPdf = ticketPdf;
        this.messages = messages;
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    private User currentUser(UserDetails principal) {
        if (principal == null) return null;
        return userService.byEmail(principal.getUsername());
    }

    @GetMapping("/e/{slug}/checkout")
    @Transactional(readOnly = true)
    public String checkout(@PathVariable String slug,
                           @RequestParam Map<String, String> params,
                           @AuthenticationPrincipal UserDetails principal,
                           jakarta.servlet.http.HttpServletRequest request,
                           Model model) {
        User user = currentUser(principal);
        EventDetail event = catalog.eventDetail(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));

        // #11 sign-in continuation: the current checkout URL (path + query, incl.
        // qty-* selection) becomes the ?next= target of the "Sign in to complete
        // your order" link for anonymous buyers. Built server-side; @{...(next=...)}
        // URL-encodes it in the template, and AuthController validates it again.
        String qs = request.getQueryString();
        model.addAttribute("checkoutNext",
                request.getRequestURI() + (qs == null || qs.isEmpty() ? "" : "?" + qs));

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
        // ABSORB fee mode: organizer swallows the booking fee — buyer pays face
        // value, so the DISPLAYED total must match what OrderService will charge.
        boolean absorbFee = eventRepo.findBySlug(slug)
                .map(e -> "ABSORB".equals(e.getFeeMode())).orElse(false);
        long subtotal = 0;
        long fee = 0;
        boolean anyPaidTicket = false;
        int totalQty = 0;
        for (var tt : event.ticketTypes()) {
            int q = quantities.get(tt.id());
            subtotal += tt.priceIqd() * q;
            if (tt.priceIqd() > 0) {
                anyPaidTicket = true;
                if (!absorbFee) fee += Format.bookingFeeFor(tt.priceIqd()) * q;
            }
            totalQty += q;
        }

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
                promoMsg = msg("checkout.promoApplied", promo.trim().toUpperCase(), Format.iqd(discount));
            } else {
                promoMsg = msg("checkout.promoCodeInvalid", promo.trim());
            }
        }
        long total = Math.max(0, subtotal - discount) + fee;

        model.addAttribute("currentUser", user);
        model.addAttribute("event", event);
        model.addAttribute("quantities", quantities);
        model.addAttribute("totalQty", totalQty);
        model.addAttribute("subtotalLabel", Format.iqd(subtotal));
        model.addAttribute("feeLabel", Format.iqd(fee));
        model.addAttribute("absorbFee", absorbFee);
        model.addAttribute("anyFeeShown", !absorbFee && anyPaidTicket);
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

    @PostMapping("/e/{slug}/checkout")
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
        Map<Long, Integer> quantities = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().startsWith("qty-")) {
                try {
                    quantities.put(Long.parseLong(entry.getKey().substring(4)),
                            Integer.parseInt(entry.getValue()));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (user == null) {
            // #11 safety net: the session expired (or JS was bypassed) and an
            // anonymous POST arrived. Send the buyer to sign in with a ?next=
            // that reconstructs this checkout GET including the picked quantities.
            StringBuilder nextQs = new StringBuilder();
            quantities.forEach((id, q) -> nextQs.append(nextQs.length() == 0 ? "?" : "&")
                    .append("qty-").append(id).append("=").append(Math.max(0, Math.min(10, q))));
            String next = "/e/" + slug + "/checkout" + nextQs;
            return "redirect:/auth/login?next="
                    + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8);
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
            return "redirect:/e/" + org.springframework.web.util.UriUtils.encodePathSegment(slug, java.nio.charset.StandardCharsets.UTF_8)
                    + "/checkout?_e" + qs;
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
            case PENDING_CONFIRMATION -> msg("status.order.pending");
            case CONFIRMED -> msg("status.order.confirmed");
            case REJECTED -> msg("status.order.rejected");
            case CANCELLED -> msg("status.order.cancelled");
            case REFUNDED -> msg("status.order.refunded");
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
        String venueLine = online ? msg("location.online")
                : "TBA".equals(locType) ? msg("location.tba")
                : (order.getEvent().getVenueName() == null ? "" : order.getEvent().getVenueName() + ", ")
                        + iq.ievent.service.Cities.label(order.getEvent().getCity(),
                                org.springframework.context.i18n.LocaleContextHolder.getLocale());
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
