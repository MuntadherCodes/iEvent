package iq.ievent.config;

import iq.ievent.domain.User;
import iq.ievent.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * "Sign in with Google" — active ONLY when GOOGLE_CLIENT_ID is set in the env.
 * Users are matched/created by their Google email (auth_provider = 'google').
 */
@Configuration
@ConditionalOnExpression("!'${app.google.client-id:}'.isEmpty()")
public class GoogleOAuthConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.google.client-id}") String clientId,
            @Value("${app.google.client-secret}") String clientSecret) {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }

    /** Matches or provisions the local account for a Google identity. Refreshes the
     *  avatar from Google on every login (only writes when it actually changed) so a
     *  new Google photo follows the account without needing re-provisioning. */
    private static User provision(UserRepository users, PasswordEncoder passwordEncoder,
                                  SecureRandom random, String email, String name, String pictureUrl) {
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException("Google account has no email");
        }
        var existing = users.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            User u = existing.get();
            if (pictureUrl != null && !pictureUrl.isBlank() && !pictureUrl.equals(u.getAvatarUrl())) {
                u.setAvatarUrl(pictureUrl);
                users.save(u);
            }
            return u;
        }
        User u = new User();
        u.setEmail(email.toLowerCase());
        u.setFullName(name == null || name.isBlank() ? email : name);
        byte[] noise = new byte[32];
        random.nextBytes(noise);
        u.setPasswordHash(passwordEncoder.encode(Base64.getEncoder().encodeToString(noise)));
        u.setRole(User.Role.USER);
        u.setAuthProvider("google");
        u.setPreferredLang(iq.ievent.service.UserService.currentLang());
        if (pictureUrl != null && !pictureUrl.isBlank()) u.setAvatarUrl(pictureUrl);
        try {
            return users.save(u);
        } catch (DataIntegrityViolationException e) {
            // Two sign-ins for the same brand-new Google email raced the
            // findByEmailIgnoreCase check above — the other one already
            // inserted the row (ux_users_email) between our check and this
            // insert. That's not a real failure, just a lost race: the
            // account exists now, so use it instead of surfacing a 500 that
            // Spring Security would otherwise turn into a misleading
            // "wrong password" message on the login page.
            return users.findByEmailIgnoreCase(email)
                    .orElseThrow(() -> e);
        }
    }

    /** Plain-OAuth2 path (used when the registration has no "openid" scope). */
    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> googleUserService(
            UserRepository users, PasswordEncoder passwordEncoder) {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        SecureRandom random = new SecureRandom();
        return request -> {
            OAuth2User oauth = delegate.loadUser(request);
            User user = provision(users, passwordEncoder, random,
                    oauth.getAttribute("email"), oauth.getAttribute("name"), oauth.getAttribute("picture"));
            return new AppOAuth2User(user, oauth.getAttributes());
        };
    }

    /**
     * OIDC path — this is the one Google logins ACTUALLY take: Google's default
     * scopes include "openid", so Spring Security uses the OIDC login flow and
     * IGNORES the plain userService above. Without this bean no local account
     * was created and the principal wasn't a UserDetails, so signed-in Google
     * users looked anonymous and hit 404s on /me/** and PDF downloads.
     */
    @Bean
    public OAuth2UserService<org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
                             org.springframework.security.oauth2.core.oidc.user.OidcUser>
            googleOidcUserService(UserRepository users, PasswordEncoder passwordEncoder) {
        org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService delegate =
                new org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService();
        SecureRandom random = new SecureRandom();
        return request -> {
            org.springframework.security.oauth2.core.oidc.user.OidcUser oidc = delegate.loadUser(request);
            User user = provision(users, passwordEncoder, random,
                    oidc.getEmail(), oidc.getFullName(), oidc.getPicture());
            return new AppOidcUser(user, oidc);
        };
    }
}
