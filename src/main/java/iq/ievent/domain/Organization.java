package iq.ievent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String handle;

    private String bio;

    private String city;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "direct_payments_enabled", nullable = false)
    private boolean directPaymentsEnabled;

    @Column(name = "pay_card_number")
    private String payCardNumber;

    @Column(name = "pay_account_name")
    private String payAccountName;

    @Column(name = "pay_wallet_bank")
    private String payWalletBank;

    @Column(name = "pay_instructions")
    private String payInstructions;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    private String website;

    private String instagram;

    @Column(name = "logo_path")
    private String logoPath;

    @Column(name = "brand_color")
    private String brandColor;

    @Column(name = "notify_pending_orders", nullable = false)
    private boolean notifyPendingOrders = true;

    @Column(name = "cover_image_path")
    private String coverImagePath;

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isDirectPaymentsEnabled() { return directPaymentsEnabled; }
    public void setDirectPaymentsEnabled(boolean directPaymentsEnabled) { this.directPaymentsEnabled = directPaymentsEnabled; }
    public String getPayCardNumber() { return payCardNumber; }
    public void setPayCardNumber(String payCardNumber) { this.payCardNumber = payCardNumber; }
    public String getPayAccountName() { return payAccountName; }
    public void setPayAccountName(String payAccountName) { this.payAccountName = payAccountName; }
    public String getPayWalletBank() { return payWalletBank; }
    public void setPayWalletBank(String payWalletBank) { this.payWalletBank = payWalletBank; }
    public String getPayInstructions() { return payInstructions; }
    public void setPayInstructions(String payInstructions) { this.payInstructions = payInstructions; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getInstagram() { return instagram; }
    public void setInstagram(String instagram) { this.instagram = instagram; }
    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }
    public String getBrandColor() { return brandColor; }
    public void setBrandColor(String brandColor) { this.brandColor = brandColor; }
    public boolean isNotifyPendingOrders() { return notifyPendingOrders; }
    public void setNotifyPendingOrders(boolean notifyPendingOrders) { this.notifyPendingOrders = notifyPendingOrders; }
    public String getCoverImagePath() { return coverImagePath; }
    public void setCoverImagePath(String coverImagePath) { this.coverImagePath = coverImagePath; }
}
