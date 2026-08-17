package iq.ievent.service;

import iq.ievent.domain.PasswordResetToken;
import iq.ievent.domain.User;
import iq.ievent.repo.PasswordResetTokenRepository;
import iq.ievent.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Forgot-password flow. Deliberately quiet about whether an email exists:
 * the request endpoint always reports success to the visitor.
 */
@Service
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final String baseUrl;

    public PasswordResetService(PasswordResetTokenRepository tokens,
                                UserRepository users,
                                PasswordEncoder passwordEncoder,
                                MailService mail,
                                @Value("${app.base-url}") String baseUrl) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
        this.baseUrl = baseUrl;
    }

    /** Creates a 60-minute token and emails the link. No-op for unknown emails. */
    @Transactional
    public void requestReset(String email) {
        if (email == null || email.isBlank()) return;
        users.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            PasswordResetToken t = new PasswordResetToken();
            t.setUser(user);
            t.setToken(randomToken(48));
            t.setExpiresAt(OffsetDateTime.now().plusMinutes(60));
            tokens.save(t);
            mail.sendPasswordReset(user.getEmail(), baseUrl + "/auth/reset?token=" + t.getToken());
        });
    }

    /** The user behind a live (unused, unexpired) token, or empty. */
    @Transactional(readOnly = true)
    public Optional<User> userForToken(String token) {
        return liveToken(token).map(PasswordResetToken::getUser);
    }

    /** Sets the new password and burns the token. Returns false if token invalid. */
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        Optional<PasswordResetToken> live = liveToken(token);
        if (live.isEmpty()) return false;
        PasswordResetToken t = live.get();
        User user = t.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        t.setUsed(true);
        tokens.save(t);
        users.save(user);
        return true;
    }

    private Optional<PasswordResetToken> liveToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return tokens.findByToken(token.trim())
                .filter(t -> !t.isUsed())
                .filter(t -> t.getExpiresAt().isAfter(OffsetDateTime.now()));
    }

    private static String randomToken(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return sb.toString();
    }
}
