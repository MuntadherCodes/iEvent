package iq.ievent.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class User {

    public enum Role { USER, HOST, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "notify_events", nullable = false)
    private boolean notifyEvents = true;

    @Column(name = "notify_marketing", nullable = false)
    private boolean notifyMarketing = true;

    @Column(name = "auth_provider", nullable = false)
    private String authProvider = "local";

    private String city;

    private String interests;

    @Column(name = "preferred_lang")
    private String preferredLang;

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public boolean isNotifyEvents() { return notifyEvents; }
    public void setNotifyEvents(boolean notifyEvents) { this.notifyEvents = notifyEvents; }
    public boolean isNotifyMarketing() { return notifyMarketing; }
    public void setNotifyMarketing(boolean notifyMarketing) { this.notifyMarketing = notifyMarketing; }
    public String getAuthProvider() { return authProvider; }
    public void setAuthProvider(String authProvider) { this.authProvider = authProvider; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getPreferredLang() { return preferredLang; }
    public void setPreferredLang(String preferredLang) { this.preferredLang = preferredLang; }
    public String getInterests() { return interests; }
    public void setInterests(String interests) { this.interests = interests; }

    public String initials() {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
