package iq.ievent.domain;

import jakarta.persistence.*;

/** One extra image beyond the event's primary cover — together they make the
 *  public event page a slider. url is always a directly resolvable URL (a
 *  local /media/... path or an external Pexels CDN link), so templates never
 *  need to branch on where it came from. */
@Entity
@Table(name = "event_images")
public class EventImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false)
    private String url;

    @Column(name = "credit_name")
    private String creditName;

    @Column(name = "credit_url")
    private String creditUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Vertical crop focus for this image, 0 (top) to 100 (bottom), 50 = centered. */
    @Column(name = "focus_y", nullable = false)
    private int focusY = 50;

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public void setEvent(Event event) { this.event = event; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getCreditName() { return creditName; }
    public void setCreditName(String creditName) { this.creditName = creditName; }
    public String getCreditUrl() { return creditUrl; }
    public void setCreditUrl(String creditUrl) { this.creditUrl = creditUrl; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public int getFocusY() { return focusY; }
    public void setFocusY(int focusY) { this.focusY = focusY; }
}
