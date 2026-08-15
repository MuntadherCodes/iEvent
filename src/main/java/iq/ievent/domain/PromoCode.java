package iq.ievent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "promo_codes")
public class PromoCode {

    public enum Kind { PERCENT, FIXED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "event_id")
    private Long eventId; // null = every event of the organization

    @Column(nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.PERCENT;

    @Column(nullable = false)
    private long value;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(nullable = false)
    private int used;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public long getValue() { return value; }
    public void setValue(long value) { this.value = value; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getUsed() { return used; }
    public void setUsed(int used) { this.used = used; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
