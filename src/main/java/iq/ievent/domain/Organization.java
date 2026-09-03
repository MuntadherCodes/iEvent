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

    /** R27 #4: on for new organizers by default (they can switch it off). */
    @Column(name = "direct_payments_enabled", nullable = false)
    private boolean directPaymentsEnabled = true;

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

    /** R27 #5: the refund policy is an organizer-level setting shown (or not)
     *  on every event page. Values: NO_REFUNDS | UP_TO_48H | UP_TO_7_DAYS. */
    @Column(name = "refund_policy", nullable = false)
    private String refundPolicy = "NO_REFUNDS";

    @Column(name = "refund_policy_visible", nullable = false)
    private boolean refundPolicyVisible = true;

    @Column(name = "cover_image_path")
    private String coverImagePath;

    @Column(name = "checklist_dismissed", nullable = false)
    private boolean checklistDismissed;

    @Column(name = "cover_focus_y", nullable = false)
    private int coverFocusY = 50;

    /** Suspended by the super admin — while true, every event under this org
     *  is hidden from public discovery and the host console is locked out. */
    @Column(nullable = false)
    private boolean disabled = false;

    @Column(name = "disabled_at")
    private OffsetDateTime disabledAt;

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = Text.clip(name, 120); }
    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = Text.clip(city, 60); }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isDirectPaymentsEnabled() { return directPaymentsEnabled; }
    public void setDirectPaymentsEnabled(boolean directPaymentsEnabled) { this.directPaymentsEnabled = directPaymentsEnabled; }
    public String getPayCardNumber() { return payCardNumber; }
    public void setPayCardNumber(String payCardNumber) { this.payCardNumber = Text.clip(payCardNumber, 32); }
    public String getPayAccountName() { return payAccountName; }
    public void setPayAccountName(String payAccountName) { this.payAccountName = Text.clip(payAccountName, 120); }
    public String getPayWalletBank() { return payWalletBank; }
    public void setPayWalletBank(String payWalletBank) { this.payWalletBank = Text.clip(payWalletBank, 60); }
    public String getPayInstructions() { return payInstructions; }
    public void setPayInstructions(String payInstructions) { this.payInstructions = payInstructions; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = Text.clip(contactPhone, 32); }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = Text.clip(website, 255); }
    public String getInstagram() { return instagram; }
    public void setInstagram(String instagram) { this.instagram = Text.clip(instagram, 80); }
    public String getLogoPath() { return logoPath; }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }
    public String getBrandColor() { return brandColor; }
    public void setBrandColor(String brandColor) { this.brandColor = brandColor; }
    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }
    public boolean isRefundPolicyVisible() { return refundPolicyVisible; }
    public void setRefundPolicyVisible(boolean refundPolicyVisible) { this.refundPolicyVisible = refundPolicyVisible; }
    public boolean isNotifyPendingOrders() { return notifyPendingOrders; }
    public void setNotifyPendingOrders(boolean notifyPendingOrders) { this.notifyPendingOrders = notifyPendingOrders; }
    public String getCoverImagePath() { return coverImagePath; }
    public void setCoverImagePath(String coverImagePath) { this.coverImagePath = coverImagePath; }
    public boolean isChecklistDismissed() { return checklistDismissed; }
    public void setChecklistDismissed(boolean checklistDismissed) { this.checklistDismissed = checklistDismissed; }
    public int getCoverFocusY() { return coverFocusY; }
    public void setCoverFocusY(int coverFocusY) { this.coverFocusY = coverFocusY; }
    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }
    public OffsetDateTime getDisabledAt() { return disabledAt; }
    public void setDisabledAt(OffsetDateTime disabledAt) { this.disabledAt = disabledAt; }
}
