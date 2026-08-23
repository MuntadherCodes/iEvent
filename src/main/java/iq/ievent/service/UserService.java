package iq.ievent.service;

import iq.ievent.domain.User;
import iq.ievent.repo.UserRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MessageSource messages;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, MessageSource messages) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.messages = messages;
    }

    /** Localized user-facing message in the current request locale. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args, LocaleContextHolder.getLocale());
    }

    /** Avatar URLs for whichever of the given emails belong to a registered account
     *  with a photo (e.g. a Google avatar) — used to show a real photo instead of an
     *  initial for ticket holders/buyers who happen to also have an account. Emails
     *  with no match (guest checkout, no photo, local account) are simply absent. */
    @Transactional(readOnly = true)
    public java.util.Map<String, String> avatarsByEmail(java.util.Collection<String> emails) {
        List<String> distinct = emails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase())
                .distinct().toList();
        if (distinct.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (User u : users.findWithAvatarByEmailIgnoreCaseIn(distinct)) {
            out.put(u.getEmail().toLowerCase(), u.getAvatarUrl());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }

    @Transactional(readOnly = true)
    public boolean emailTaken(String email) {
        return users.existsByEmailIgnoreCase(email);
    }

    @Transactional
    public User register(String fullName, String email, String phone, String rawPassword) {
        User user = new User();
        user.setFullName(fullName.trim());
        user.setEmail(email.trim().toLowerCase());
        user.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(User.Role.USER);
        user.setPreferredLang(currentLang());
        return users.save(user);
    }

    /** "en" or "ar" for the current request. */
    public static String currentLang() {
        return "en".equals(org.springframework.context.i18n.LocaleContextHolder
                .getLocale().getLanguage()) ? "en" : "ar";
    }

    /** Persist the user's language whenever they browse in a different one. */
    @Transactional
    public void rememberLanguage(User user, String lang) {
        if (user != null && lang != null && !lang.equals(user.getPreferredLang())) {
            user.setPreferredLang(lang);
            users.save(user);
        }
    }

    @Transactional(readOnly = true)
    public User byEmail(String email) {
        return users.findByEmailIgnoreCase(email).orElse(null);
    }

    public record GuestProvision(User user, boolean created) {}

    /** Guest checkout: matches the buyer's email to an existing account, or
     *  quietly provisions a new local one (random unusable password — the
     *  buyer sets a real one later via the password-reset link the checkout
     *  flow emails them). Mirrors GoogleOAuthConfig's find-or-create pattern,
     *  including the same race-safe fallback for two concurrent first-time
     *  checkouts with the same brand-new email. */
    @Transactional
    public GuestProvision findOrCreateGuest(String fullName, String email, String phone) {
        String normalized = email.trim().toLowerCase();
        var existing = users.findByEmailIgnoreCase(normalized);
        if (existing.isPresent()) return new GuestProvision(existing.get(), false);
        User u = new User();
        u.setEmail(normalized);
        u.setFullName(fullName == null || fullName.isBlank() ? normalized : fullName.trim());
        u.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        byte[] noise = new byte[32];
        new java.security.SecureRandom().nextBytes(noise);
        u.setPasswordHash(passwordEncoder.encode(java.util.Base64.getEncoder().encodeToString(noise)));
        u.setRole(User.Role.USER);
        u.setPreferredLang(currentLang());
        try {
            return new GuestProvision(users.save(u), true);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return new GuestProvision(users.findByEmailIgnoreCase(normalized).orElseThrow(() -> e), false);
        }
    }

    @Transactional
    public void updateProfile(User user, String fullName, String phone) {
        user.setFullName(fullName.trim());
        user.setPhone(phone == null || phone.isBlank() ? null : phone.trim());
        users.save(user);
    }

    /** Returns an error message, or null on success. */
    @Transactional
    public String changePassword(User user, String current, String next) {
        if (next == null || next.length() < 8) return msg("user.password.tooShort");
        if (!"local".equals(user.getAuthProvider())) {
            return msg("user.password.google");
        }
        if (!passwordEncoder.matches(current == null ? "" : current, user.getPasswordHash())) {
            return msg("user.password.wrongCurrent");
        }
        user.setPasswordHash(passwordEncoder.encode(next));
        users.save(user);
        return null;
    }

    @Transactional
    public void updateNotifications(User user, boolean events, boolean marketing) {
        user.setNotifyEvents(events);
        user.setNotifyMarketing(marketing);
        users.save(user);
    }
}
