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
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
