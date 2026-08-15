package iq.ievent.config;

import iq.ievent.domain.User;
import iq.ievent.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> googleUserService(
            UserRepository users, PasswordEncoder passwordEncoder) {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        SecureRandom random = new SecureRandom();
        return request -> {
            OAuth2User oauth = delegate.loadUser(request);
            String email = oauth.getAttribute("email");
            String name = oauth.getAttribute("name");
            if (email == null || email.isBlank()) {
                throw new OAuth2AuthenticationException("Google account has no email");
            }
            User user = users.findByEmailIgnoreCase(email).orElseGet(() -> {
                User u = new User();
                u.setEmail(email.toLowerCase());
                u.setFullName(name == null || name.isBlank() ? email : name);
                byte[] noise = new byte[32];
                random.nextBytes(noise);
                u.setPasswordHash(passwordEncoder.encode(Base64.getEncoder().encodeToString(noise)));
                u.setRole(User.Role.USER);
                u.setAuthProvider("google");
                return users.save(u);
            });
            return new AppOAuth2User(user, oauth.getAttributes());
        };
    }
}
