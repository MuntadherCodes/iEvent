package iq.ievent.service;

import iq.ievent.domain.*;
import iq.ievent.repo.*;
import iq.ievent.service.PromoService.Applied;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OrderService {

    public static final long BOOKING_FEE_PER_PAID_TICKET = 1_500L;
    private static final String CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford base32
    private static final Set<String> RECEIPT_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "pdf");
    private static final long RECEIPT_MAX_BYTES = 5 * 1024 * 1024;

    public static class CheckoutException extends RuntimeException {
        public CheckoutException(String message) { super(message); }
    }

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final TicketTypeRepository ticketTypes;
    private final EventRepository events;
    private final JdbcTemplate jdbc;
    private final MailService mail;
    private final PromoService promoService;
    private final NotificationService notifications;
    private final SecureRandom random = new SecureRandom();
    private final Path uploadDir;

    public OrderService(OrderRepository orders,
                        TicketRepository tickets,
                        TicketTypeRepository ticketTypes,
                        EventRepository events,
                        JdbcTemplate jdbc,
                        MailService mail,
                        PromoService promoService,
                        NotificationService notifications,
                        @Value("${app.upload-dir:/app/data/uploads}") String uploadDir) {
        this.orders = orders;
        this.tickets = tickets;
        this.ticketTypes = ticketTypes;
        this.events = events;
        this.jdbc = jdbc;
        this.mail = mail;
        this.promoService = promoService;
        this.notifications = notifications;
        this.uploadDir = Path.of(uploadDir);
    }

    /**
     * Creates an order for the given quantities (ticketTypeId → qty).
     * Free orders confirm instantly (tickets issued); direct-transfer orders are
     * created PENDING_CONFIRMATION and issue tickets only on host approval.
     * Inventory is reserved atomically at creation (guarded UPDATE) and released on rejection.
     */
    @Transactional
    public Order checkout(User buyer, String slug, Map<Long, Integer> quantities,
                          String buyerName, String buyerEmail, String buyerPhone,
                          String transferReference, MultipartFile receipt,
                          String promoCode, List<String> holderNames, List<String> holderEmails,
                          boolean keepUpdated) {
        Event event = events.findBySlug(slug)
                .filter(e -> e.getStatus() == Event.Status.LIVE)
                .orElseThrow(() -> new CheckoutException("This event is not on sale."));

        Map<TicketType, Integer> selection = new LinkedHashMap<>();
        int totalQty = 0;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            int qty = entry.getValue() == null ? 0 : entry.getValue();
            if (qty <= 0) continue;
            TicketType tt = ticketTypes.findById(entry.getKey())
                    .orElseThrow(() -> new CheckoutException("Unknown ticket type."));
            if (!tt.getEvent().getId().equals(event.getId())) {
                throw new CheckoutException("Ticket does not belong to this event.");
            }
            if (tt.getStatus() != TicketType.Status.ON_SALE) {
                throw new CheckoutException("'" + tt.getName() + "' is not on sale.");
            }
            selection.put(tt, qty);
            totalQty += qty;
        }
        if (totalQty == 0) throw new CheckoutException("Pick at least one ticket.");
        if (totalQty > 10) throw new CheckoutException("Maximum 10 tickets per order.");

        // atomic inventory reservation
        for (Map.Entry<TicketType, Integer> entry : selection.entrySet()) {
            int updated = jdbc.update(
                    "UPDATE ticket_types SET sold = sold + ? WHERE id = ? AND sold + ? <= quantity",
                    entry.getValue(), entry.getKey().getId(), entry.getValue());
            if (updated == 0) {
                throw new CheckoutException("'" + entry.getKey().getName()
                        + "' just sold out — reduce the quantity and try again.");
            }
        }

        long subtotal = 0;
        long paidTickets = 0;
        for (Map.Entry<TicketType, Integer> entry : selection.entrySet()) {
            subtotal += entry.getKey().getPriceIqd() * entry.getValue();
            if (entry.getKey().getPriceIqd() > 0) paidTickets += entry.getValue();
        }
        // ABSORB fee mode: the organizer swallows the booking fee (deducted in
        // earnings), so the buyer pays face value only.
        long fee = "ABSORB".equals(event.getFeeMode()) ? 0
                : paidTickets * BOOKING_FEE_PER_PAID_TICKET;

        Applied applied = promoService.preview(event, promoCode, subtotal).orElse(null);
        if (promoCode != null && !promoCode.isBlank() && applied == null) {
            throw new CheckoutException("Promo code '" + promoCode.trim() + "' is not valid for this event.");
        }
        long discount = applied == null ? 0 : applied.discountIqd();
        if (applied != null && !promoService.redeem(applied.promo())) {
            throw new CheckoutException("That promo code just ran out of uses.");
        }
        long total = Math.max(0, subtotal - discount) + fee;

        boolean free = total == 0;
        if (!free && !event.getOrganization().isDirectPaymentsEnabled()) {
            throw new CheckoutException(
                    "This organizer has not enabled a payment method yet. Please check back soon.");
        }

        Order order = new Order();
        order.setOrderCode(uniqueOrderCode());
        order.setEvent(event);
        order.setBuyerUserId(buyer.getId());
        order.setBuyerName(buyerName.trim());
        order.setBuyerEmail(buyerEmail.trim());
        order.setBuyerPhone(buyerPhone == null || buyerPhone.isBlank() ? null : buyerPhone.trim());
        order.setPaymentMethod(free ? Order.PaymentMethod.FREE : Order.PaymentMethod.DIRECT_TRANSFER);
        order.setStatus(free ? Order.Status.CONFIRMED : Order.Status.PENDING_CONFIRMATION);
        order.setSubtotalIqd(subtotal);
        order.setBookingFeeIqd(fee);
        order.setTotalIqd(total);
        if (applied != null) {
            order.setPromoCode(applied.promo().getCode());
            order.setDiscountIqd(discount);
        }
        if (!free) {
            order.setTransferReference(transferReference == null || transferReference.isBlank()
                    ? null : transferReference.trim());
            order.setReceiptPath(storeReceipt(receipt, order.getOrderCode()));
        } else {
            order.setConfirmedAt(OffsetDateTime.now());
        }
        for (Map.Entry<TicketType, Integer> entry : selection.entrySet()) {
            OrderItem item = new OrderItem();
            item.setTicketType(entry.getKey());
            item.setQuantity(entry.getValue());
            item.setUnitPriceIqd(entry.getKey().getPriceIqd());
            order.addItem(item);
        }
        List<String> names = sanitizeHolders(holderNames, totalQty, buyerName);
        List<String> emails = sanitizeHolderEmails(holderEmails, totalQty);
        StringBuilder holderLines = new StringBuilder();
        for (int i = 0; i < totalQty; i++) {
            if (i > 0) holderLines.append("\n");
            holderLines.append(names.get(i)).append("\t").append(emails.get(i));
        }
        order.setHolderNames(holderLines.toString());
        order = orders.save(order);
        if (keepUpdated && !buyer.isNotifyEvents()) {
            jdbc.update("UPDATE users SET notify_events = TRUE WHERE id = ?", buyer.getId());
        }

        if (free) {
            List<Ticket> issued = issueTickets(order);
            mail.sendOrderConfirmed(order, issued);
            notifications.notify(buyer.getId(), "ORDER_CONFIRMED",
                    "Tickets confirmed — " + event.getTitle(),
                    "Order " + order.getOrderCode() + " · your tickets are ready.",
                    "/me/tickets");
        } else {
            mail.sendOrderPending(order);
            notifications.notify(buyer.getId(), "ORDER_PENDING",
                    "Order received — " + event.getTitle(),
                    "Order " + order.getOrderCode()
                            + " is waiting for the organizer to confirm your transfer.",
                    "/me/tickets");
            Organization org = event.getOrganization();
            notifications.notify(org.getOwnerUserId(), "NEW_ORDER",
                    "New order to confirm — " + event.getTitle(),
                    order.getBuyerName() + " · " + Format.iqd(order.getTotalIqd())
                            + " · " + order.getOrderCode(),
                    "/host/orders?f=1&status=pending");
            if (org.isNotifyPendingOrders()) {
                final Order saved = order;
                jdbc.query("SELECT email FROM users WHERE id = ?",
                        rs -> { mail.sendPendingOrderAlert(rs.getString(1), saved); },
                        org.getOwnerUserId());
            }
        }
        return order;
    }

    /** CONFIRMED → REFUNDED: voids tickets, releases inventory, notifies the buyer.
     *  (For direct transfers the money itself is returned by the organizer offline.) */
    @Transactional
    public Order refund(Long orderId, Long hostOrgId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new CheckoutException("Order not found."));
        if (!order.getEvent().getOrganization().getId().equals(hostOrgId)) {
            throw new CheckoutException("Order does not belong to your organization.");
        }
        if (order.getStatus() != Order.Status.CONFIRMED) {
            throw new CheckoutException("Only confirmed orders can be refunded.");
        }
        order.setStatus(Order.Status.REFUNDED);
        for (OrderItem item : order.getItems()) {
            jdbc.update("UPDATE ticket_types SET sold = GREATEST(0, sold - ?) WHERE id = ?",
                    item.getQuantity(), item.getTicketType().getId());
        }
        jdbc.update("UPDATE tickets SET status = 'VOID' WHERE order_id = ?", order.getId());
        order.getEvent().getTitle(); // init for async mail
        mail.sendOrderRefunded(order);
        notifications.notify(order.getBuyerUserId(), "ORDER_REFUNDED",
                "Order refunded — " + order.getEvent().getTitle(),
                "Order " + order.getOrderCode() + " was refunded; tickets are no longer valid.",
                "/me/tickets");
        return orders.save(order);
    }

    /** Re-sends the confirmation email with tickets. */
    @Transactional(readOnly = true)
    public Order resend(Long orderId, Long hostOrgId) {
        Order order = orders.findById(orderId)
                .filter(o -> o.getEvent().getOrganization().getId().equals(hostOrgId))
                .filter(o -> o.getStatus() == Order.Status.CONFIRMED)
                .orElseThrow(() -> new CheckoutException("Only confirmed orders can be re-sent."));
        List<Ticket> list = tickets.findByOrderIdOrderByIdAsc(order.getId());
        list.forEach(t -> t.getTicketType().getName());
        order.getEvent().getTitle();
        mail.sendOrderConfirmed(order, list);
        return order;
    }

    @Transactional
    public Order approve(Long orderId, Long hostOrgId) {
        Order order = ownedPendingOrder(orderId, hostOrgId);
        order.setStatus(Order.Status.CONFIRMED);
        order.setConfirmedAt(OffsetDateTime.now());
        List<Ticket> issued = issueTickets(order);
        mail.sendOrderConfirmed(order, issued);
        notifications.notify(order.getBuyerUserId(), "ORDER_CONFIRMED",
                "Order confirmed — " + order.getEvent().getTitle(),
                "The organizer confirmed " + order.getOrderCode() + "; your tickets are ready.",
                "/me/tickets");
        return order;
    }

    @Transactional
    public Order reject(Long orderId, Long hostOrgId) {
        Order order = ownedPendingOrder(orderId, hostOrgId);
        order.setStatus(Order.Status.REJECTED);
        for (OrderItem item : order.getItems()) {
            jdbc.update("UPDATE ticket_types SET sold = GREATEST(0, sold - ?) WHERE id = ?",
                    item.getQuantity(), item.getTicketType().getId());
        }
        mail.sendOrderRejected(order);
        notifications.notify(order.getBuyerUserId(), "ORDER_REJECTED",
                "Order could not be confirmed — " + order.getEvent().getTitle(),
                "The organizer could not verify the transfer for " + order.getOrderCode() + ".",
                "/me/tickets");
        return order;
    }

    private Order ownedPendingOrder(Long orderId, Long hostOrgId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new CheckoutException("Order not found."));
        if (!order.getEvent().getOrganization().getId().equals(hostOrgId)) {
            throw new CheckoutException("Order does not belong to your organization.");
        }
        if (order.getStatus() != Order.Status.PENDING_CONFIRMATION) {
            throw new CheckoutException("Order is not awaiting confirmation.");
        }
        return order;
    }

    private List<Ticket> issueTickets(Order order) {
        List<String> holders = order.getHolderNames() == null ? List.of()
                : List.of(order.getHolderNames().split("\\n"));
        List<Ticket> issued = new ArrayList<>();
        int n = 0;
        for (OrderItem item : order.getItems()) {
            item.getTicketType().getName(); // initialize proxy inside txn (email renders async)
            for (int i = 0; i < item.getQuantity(); i++) {
                Ticket t = new Ticket();
                t.setCode(randomCode(20));
                t.setOrder(order);
                t.setTicketType(item.getTicketType());
                t.setEvent(order.getEvent());
                String line = n < holders.size() ? holders.get(n) : order.getBuyerName();
                String[] parts = line.split("\\t", 2);
                t.setHolderName(parts[0].isBlank() ? order.getBuyerName() : parts[0]);
                t.setHolderEmail(parts.length > 1 && !parts[1].isBlank() ? parts[1] : null);
                t.setStatus(Ticket.Status.VALID);
                issued.add(tickets.save(t));
                n++;
            }
        }
        return issued;
    }

    private static List<String> sanitizeHolderEmails(List<String> raw, int totalQty) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String r : raw) {
                if (out.size() >= totalQty) break;
                String v = r == null ? "" : r.trim();
                out.add(v.contains("@") && v.length() <= 255 ? v : "");
            }
        }
        while (out.size() < totalQty) out.add("");
        return out;
    }

    private static List<String> sanitizeHolders(List<String> raw, int totalQty, String buyerName) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String r : raw) {
                if (out.size() >= totalQty) break;
                out.add(r == null || r.isBlank() ? buyerName.trim() : r.trim());
            }
        }
        while (out.size() < totalQty) out.add(buyerName.trim());
        return out;
    }

    private String storeReceipt(MultipartFile receipt, String orderCode) {
        if (receipt == null || receipt.isEmpty()) return null;
        if (receipt.getSize() > RECEIPT_MAX_BYTES) {
            throw new CheckoutException("Receipt file is too large (max 5 MB).");
        }
        String original = receipt.getOriginalFilename() == null ? "receipt" : receipt.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!RECEIPT_EXTENSIONS.contains(ext)) {
            throw new CheckoutException("Receipt must be a JPG, PNG, WEBP or PDF file.");
        }
        try {
            Path dir = uploadDir.resolve("receipts");
            Files.createDirectories(dir);
            Path target = dir.resolve(orderCode + "." + ext);
            Files.copy(receipt.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            throw new CheckoutException("Could not store the receipt file. Please try again.");
        }
    }

    private String uniqueOrderCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < 5; i++) digits.append(random.nextInt(10));
            String code = "EVT-" + Year.now().getValue() + "-" + digits;
            if (orders.findByOrderCode(code).isEmpty()) return code;
        }
        return "EVT-" + Year.now().getValue() + "-" + randomCode(6);
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
