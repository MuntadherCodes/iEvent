package iq.ievent.config;

import iq.ievent.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Principal for Google-authenticated users that ALSO implements UserDetails,
 * so every controller using @AuthenticationPrincipal UserDetails works
 * identically for form-login and OAuth sessions.
 */
public class AppOAuth2User implements OAuth2User, UserDetails {

    private final String email;
    private final String role;
    private final Map<String, Object> attributes;

    public AppOAuth2User(User user, Map<String, Object> attributes) {
        this.email = user.getEmail();
        this.role = user.getRole().name();
        this.attributes = attributes;
    }

    @Override
    public Map<String, Object> getAttributes() { return attributes; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getName() { return email; }

    @Override
    public String getUsername() { return email; }

    @Override
    public String getPassword() { return ""; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
