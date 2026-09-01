package iq.ievent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "events")
public class Event {

    // Declaration order drives any UI that iterates values() directly (e.g. the
    // profile "interests" picker) — kept in step with PageController.CATEGORIES
    // so both show categories in the same order. Stored by name (EnumType.STRING
    // below), so reordering here is safe and needs no migration.
    public enum Category { EDUCATION, COMMUNITY, BUSINESS, FOOD, TECH, MUSIC, SPORTS, ARTS, FAMILY, FILM }

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

    /** Whether the host actually picked a start time, as opposed to leaving it
     *  blank (time is optional in the form, but starts_at itself can't be null
     *  — a blank pick still stores a noon placeholder there). Existing rows all
     *  had a real time before this flag existed, hence the true default. Drives
     *  whether the edit form re-shows a time on reload, and can also gate a
     *  future "Time TBA" treatment on public pages. */
    @Column(name = "has_start_time", nullable = false)
    private boolean hasStartTime = true;

    /** How precise the host's schedule actually is — see {@link DatePrecision}.
     *  Stored as text (like locationType) so the check constraint in V23 is
     *  the single source of allowed values. startsAt/endsAt always hold a
     *  real timestamp regardless (placeholders for MONTH/TBA — see
     *  Format.TBA_PLACEHOLDER) so sorting and the NOT NULL column keep
     *  working; display code must branch on this field, never trust the raw
     *  timestamp alone. */
    @Column(name = "date_precision", nullable = false)
    private String datePrecision = "DAY";

    /** DAY = exact date (the default); RANGE = multi-day, endsAt is on a later
     *  calendar day; MONTH = month + year only, startsAt is the 1st at noon;
     *  TBA = date not announced yet, startsAt is the 2099 placeholder. */
    public static final String PRECISION_DAY = "DAY";
    public static final String PRECISION_RANGE = "RANGE";
    public static final String PRECISION_MONTH = "MONTH";
    public static final String PRECISION_TBA = "TBA";

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

    /** The language the host actually wrote title/summary/description/lineup
     *  in ("ar"/"en") — set once at creation from the host's UI locale. Drives
     *  which of the *Translated columns below feeds a given viewer: their own
     *  locale when it matches this, the auto-translated copy otherwise. */
    @Column(nullable = false)
    private String language = "en";

    /** Auto-translated copy of title/summary/description/lineup, generated by
     *  {@link iq.ievent.service.GoogleTranslateService} when the event is
     *  published (see HostService#publish) — null until then, or whenever
     *  translation isn't configured/failed, in which case display falls back
     *  to the original. */
    @Column(name = "title_translated")
    private String titleTranslated;

    @Column(name = "summary_translated")
    private String summaryTranslated;

    @Column(name = "description_translated")
    private String descriptionTranslated;

    @Column(name = "lineup_translated")
    private String lineupTranslated;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    /** Taken down by the super admin — independent of the host's own status/
     *  visibility fields, so only an admin action can clear it. */
    @Column(name = "admin_hidden", nullable = false)
    private boolean adminHidden = false;

    @Column(name = "admin_hidden_at")
    private OffsetDateTime adminHiddenAt;

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
    public boolean isHasStartTime() { return hasStartTime; }
    public void setHasStartTime(boolean hasStartTime) { this.hasStartTime = hasStartTime; }
    public String getDatePrecision() { return datePrecision; }
    public void setDatePrecision(String datePrecision) { this.datePrecision = datePrecision; }
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
    public String getTitleTranslated() { return titleTranslated; }
    public void setTitleTranslated(String titleTranslated) { this.titleTranslated = titleTranslated; }
    public String getSummaryTranslated() { return summaryTranslated; }
    public void setSummaryTranslated(String summaryTranslated) { this.summaryTranslated = summaryTranslated; }
    public String getDescriptionTranslated() { return descriptionTranslated; }
    public void setDescriptionTranslated(String descriptionTranslated) { this.descriptionTranslated = descriptionTranslated; }
    public String getLineupTranslated() { return lineupTranslated; }
    public void setLineupTranslated(String lineupTranslated) { this.lineupTranslated = lineupTranslated; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public boolean isAdminHidden() { return adminHidden; }
    public void setAdminHidden(boolean adminHidden) { this.adminHidden = adminHidden; }
    public OffsetDateTime getAdminHiddenAt() { return adminHiddenAt; }
    public void setAdminHiddenAt(OffsetDateTime adminHiddenAt) { this.adminHiddenAt = adminHiddenAt; }
}
