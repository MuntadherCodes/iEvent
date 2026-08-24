package iq.ievent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    public enum PaymentMethod { FREE, DIRECT_TRANSFER, CASH }

    public enum Status { PENDING_CONFIRMATION, CONFIRMED, REJECTED, CANCELLED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "buyer_user_id", nullable = false)
    private Long buyerUserId;

    @Column(name = "buyer_name", nullable = false)
    private String buyerName;

    @Column(name = "buyer_email", nullable = false)
    private String buyerEmail;

    @Column(name = "buyer_phone")
    private String buyerPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "subtotal_iqd", nullable = false)
    private long subtotalIqd;

    @Column(name = "booking_fee_iqd", nullable = false)
    private long bookingFeeIqd;

    @Column(name = "total_iqd", nullable = false)
    private long totalIqd;

    @Column(name = "transfer_reference")
    private String transferReference;

    @Column(name = "receipt_path")
    private String receiptPath;

    @Column(name = "promo_code")
    private String promoCode;

    @Column(name = "discount_iqd", nullable = false)
    private long discountIqd;

    @Column(name = "holder_names")
    private String holderNames;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public Long getId() { return id; }
    public String getOrderCode() { return orderCode; }
    public void setOrderCode(String orderCode) { this.orderCode = orderCode; }
    public Event getEvent() { return event; }
    public void setEvent(Event event) { this.event = event; }
    public Long getBuyerUserId() { return buyerUserId; }
    public void setBuyerUserId(Long buyerUserId) { this.buyerUserId = buyerUserId; }
    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }
    public String getBuyerEmail() { return buyerEmail; }
    public void setBuyerEmail(String buyerEmail) { this.buyerEmail = buyerEmail; }
    public String getBuyerPhone() { return buyerPhone; }
    public void setBuyerPhone(String buyerPhone) { this.buyerPhone = buyerPhone; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getSubtotalIqd() { return subtotalIqd; }
    public void setSubtotalIqd(long subtotalIqd) { this.subtotalIqd = subtotalIqd; }
    public long getBookingFeeIqd() { return bookingFeeIqd; }
    public void setBookingFeeIqd(long bookingFeeIqd) { this.bookingFeeIqd = bookingFeeIqd; }
    public long getTotalIqd() { return totalIqd; }
    public void setTotalIqd(long totalIqd) { this.totalIqd = totalIqd; }
    public String getTransferReference() { return transferReference; }
    public void setTransferReference(String transferReference) { this.transferReference = transferReference; }
    public String getReceiptPath() { return receiptPath; }
    public void setReceiptPath(String receiptPath) { this.receiptPath = receiptPath; }
    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }
    public long getDiscountIqd() { return discountIqd; }
    public void setDiscountIqd(long discountIqd) { this.discountIqd = discountIqd; }
    public String getHolderNames() { return holderNames; }
    public void setHolderNames(String holderNames) { this.holderNames = holderNames; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public List<OrderItem> getItems() { return items; }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }
}
