package iq.ievent.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "ticket_types")
public class TicketType {

    public enum Status { ON_SALE, SOLD_OUT, HIDDEN, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_iqd", nullable = false)
    private long priceIqd;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int sold;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ON_SALE;

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public void setEvent(Event event) { this.event = event; }
    public String getName() { return name; }
    public void setName(String name) { this.name = Text.clip(name, 80); }
    public long getPriceIqd() { return priceIqd; }
    public void setPriceIqd(long priceIqd) { this.priceIqd = priceIqd; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getSold() { return sold; }
    public void setSold(int sold) { this.sold = sold; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int remaining() { return Math.max(0, quantity - sold); }
}
