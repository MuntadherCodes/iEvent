package iq.ievent.web;

import iq.ievent.domain.Order;
import iq.ievent.domain.Ticket;
import iq.ievent.domain.User;
import iq.ievent.repo.OrderRepository;
import iq.ievent.repo.TicketRepository;
import iq.ievent.service.CatalogService;
import iq.ievent.service.Format;
import iq.ievent.service.OrderService;
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
                            List<ItemView> items) {}

    public record ItemView(String name, int quantity, String unitLabel, String lineLabel) {}

    public record TicketView(String code, String typeName, String holderName, String qrSvg, String statusLabel) {}

    private final CatalogService catalog;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final QrService qr;
    private final UserService userService;

    public CheckoutController(CatalogService catalog, OrderService orderService,
                              OrderRepository orders, TicketRepository tickets,
                              QrService qr, UserService userService) {
        this.catalog = catalog;
        this.orderService = orderService;
        this.orders = orders;
        this.tickets = tickets;
        this.qr = qr;
        this.userService = userService;
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
        long subtotal = 0;
        long paidCount = 0;
        int totalQty = 0;
        for (var tt : event.ticketTypes()) {
            String raw = params.get("qty-" + tt.id());
            int q = 0;
            try { q = raw == null ? 0 : Math.max(0, Math.min(10, Integer.parseInt(raw))); }
            catch (NumberFormatException ignored) {}
            quantities.put(tt.id(), q);
            subtotal += tt.priceIqd() * q;
            if (tt.priceIqd() > 0) paidCount += q;
            totalQty += q;
        }
        long fee = paidCount * OrderService.BOOKING_FEE_PER_PAID_TICKET;

        model.addAttribute("currentUser", user);
        model.addAttribute("event", event);
        model.addAttribute("quantities", quantities);
        model.addAttribute("totalQty", totalQty);
        model.addAttribute("subtotalLabel", Format.iqd(subtotal));
        model.addAttribute("feeLabel", Format.iqd(fee));
        model.addAttribute("totalLabel", Format.iqd(subtotal + fee));
        model.addAttribute("directPay", catalog.directPayInfo(slug).orElse(null));
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
                    buyerName, buyerEmail, buyerPhone, transferReference, receipt);
            return "redirect:/orders/" + order.getOrderCode();
        } catch (OrderService.CheckoutException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            StringBuilder qs = new StringBuilder();
            quantities.forEach((id, q) -> qs.append("&qty-").append(id).append("=").append(q));
            return "redirect:/events/" + slug + "/checkout?_e" + qs;
        }
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

        List<ItemView> items = order.getItems().stream()
                .map(i -> new ItemView(i.getTicketType().getName(), i.getQuantity(),
                        Format.priceLabel(i.getUnitPriceIqd()),
                        Format.iqd(i.getUnitPriceIqd() * i.getQuantity())))
                .toList();
        OrderView view = new OrderView(order.getOrderCode(), order.getEvent().getTitle(),
                order.getEvent().getSlug(),
                Format.longDateLine(order.getEvent().getStartsAt(), order.getEvent().getEndsAt()),
                (order.getEvent().getVenueName() == null ? "" : order.getEvent().getVenueName() + ", ")
                        + order.getEvent().getCity(),
                order.getBuyerName(), order.getBuyerEmail(),
                pending ? "Pending confirmation" : "Confirmed",
                pending,
                Format.iqd(order.getSubtotalIqd()), Format.iqd(order.getBookingFeeIqd()),
                Format.iqd(order.getTotalIqd()), items);

        List<TicketView> ticketViews = tickets.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(t -> new TicketView(t.getCode(), t.getTicketType().getName(), t.getHolderName(),
                        qr.ticketQrSvg(t.getCode()), t.getStatus().name()))
                .toList();

        model.addAttribute("currentUser", user);
        model.addAttribute("order", view);
        model.addAttribute("tickets", ticketViews);
        model.addAttribute("pending", pending);
        return "confirmation";
    }
}
