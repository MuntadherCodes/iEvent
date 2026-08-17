package iq.ievent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "campaigns")
public class Campaign {

    public enum Audience { EVENT_ATTENDEES, PAST_ATTENDEES, FOLLOWERS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Audience audience;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private int recipients;

    @Column(name = "sent_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime sentAt;

    public Long getId() { return id; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }
    public Event getEvent() { return event; }
    public void setEvent(Event event) { this.event = event; }
    public Audience getAudience() { return audience; }
    public void setAudience(Audience audience) { this.audience = audience; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public int getRecipients() { return recipients; }
    public void setRecipients(int recipients) { this.recipients = recipients; }
    public OffsetDateTime getSentAt() { return sentAt; }
}
