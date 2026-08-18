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
