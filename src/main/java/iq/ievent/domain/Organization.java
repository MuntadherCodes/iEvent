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
}
