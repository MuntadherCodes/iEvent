package iq.ievent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "events")
public class Event {

    public enum Category { MUSIC, TECH, BUSINESS, ARTS, FOOD, SPORTS, COMMUNITY, EDUCATION, FILM, FAMILY }

    public enum Status { DRAFT, LIVE, ENDED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category = Category.COMMUNITY;

    @Column(nullable = false)
    private String description = "";

    @Column(nullable = false)
    private String city;

    @Column(name = "venue_name")
    private String venueName;

    @Column(name = "venue_address")
    private String venueAddress;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "cover_theme", nullable = false)
    private String coverTheme = "community";

    @Column(name = "cover_image_path")
    private String coverImagePath;

    /** External URL (Pexels) used as the primary cover when no file was
     *  uploaded — coverImagePath always wins if both are somehow set. */
    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Column(name = "cover_image_credit_name")
    private String coverImageCreditName;

    @Column(name = "cover_image_credit_url")
    private String coverImageCreditUrl;

    /** Vertical crop focus for the cover image, 0 (top) to 100 (bottom),
     *  50 = centered. Same idea as Organization.coverFocusY. */
    @Column(name = "cover_focus_y", nullable = false)
    private int coverFocusY = 50;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    private String summary;

    private String tags;

    private String lineup;

    @Column(nullable = false)
    private String visibility = "PUBLIC";

    @Column(name = "refund_policy", nullable = false)
    private String refundPolicy = "NO_REFUNDS";

    @Column(name = "location_type", nullable = false)
    private String locationType = "VENUE";

    /** No tickets are sold — the event is a pure informational listing. Independent of
     *  locationType: an announce-only event can still have a real venue, be online, or be TBA. */
    @Column(name = "announce_only", nullable = false)
    private boolean announceOnly = false;

    @Column(name = "online_url")
    private String onlineUrl;

    @Column(name = "maps_url")
    private String mapsUrl;

    @Column(name = "fee_mode", nullable = false)
    private String feeMode = "PASS";

    /** Whether a paid direct-transfer order must include a transfer
     *  reference or receipt before it can be submitted. Cash-on-arrival
     *  orders never need this regardless. */
    @Column(name = "require_payment_proof", nullable = false)
    private boolean requirePaymentProof = true;

    @Column(nullable = false)
    private String language = "en";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getVenueName() { return venueName; }
    public void setVenueName(String venueName) { this.venueName = venueName; }
    public String getVenueAddress() { return venueAddress; }
    public void setVenueAddress(String venueAddress) { this.venueAddress = venueAddress; }
    public OffsetDateTime getStartsAt() { return startsAt; }
    public void setStartsAt(OffsetDateTime startsAt) { this.startsAt = startsAt; }
    public OffsetDateTime getEndsAt() { return endsAt; }
    public void setEndsAt(OffsetDateTime endsAt) { this.endsAt = endsAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCoverTheme() { return coverTheme; }
    public void setCoverTheme(String coverTheme) { this.coverTheme = coverTheme; }
    public String getCoverImagePath() { return coverImagePath; }
    public void setCoverImagePath(String coverImagePath) { this.coverImagePath = coverImagePath; }
    public String getCoverImageUrl() { return coverImageUrl; }
    public void setCoverImageUrl(String coverImageUrl) { this.coverImageUrl = coverImageUrl; }
    public String getCoverImageCreditName() { return coverImageCreditName; }
    public void setCoverImageCreditName(String coverImageCreditName) { this.coverImageCreditName = coverImageCreditName; }
    public String getCoverImageCreditUrl() { return coverImageCreditUrl; }
    public void setCoverImageCreditUrl(String coverImageCreditUrl) { this.coverImageCreditUrl = coverImageCreditUrl; }
    public int getCoverFocusY() { return coverFocusY; }
    public void setCoverFocusY(int coverFocusY) { this.coverFocusY = coverFocusY; }
    public long getViewCount() { return viewCount; }
    public void setViewCount(long viewCount) { this.viewCount = viewCount; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getLineup() { return lineup; }
    public void setLineup(String lineup) { this.lineup = lineup; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getLocationType() { return locationType; }
    public void setLocationType(String locationType) { this.locationType = locationType; }
    public boolean isAnnounceOnly() { return announceOnly; }
    public void setAnnounceOnly(boolean announceOnly) { this.announceOnly = announceOnly; }
    public String getOnlineUrl() { return onlineUrl; }
    public void setOnlineUrl(String onlineUrl) { this.onlineUrl = onlineUrl; }
    public String getMapsUrl() { return mapsUrl; }
    public void setMapsUrl(String mapsUrl) { this.mapsUrl = mapsUrl; }
    public String getFeeMode() { return feeMode; }
    public void setFeeMode(String feeMode) { this.feeMode = feeMode; }
    public boolean isRequirePaymentProof() { return requirePaymentProof; }
    public void setRequirePaymentProof(boolean requirePaymentProof) { this.requirePaymentProof = requirePaymentProof; }
    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
