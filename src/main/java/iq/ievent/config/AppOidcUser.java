package iq.ievent.config;

import iq.ievent.domain.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.List;

/**
 * OIDC principal (Google sends the "openid" scope, so Spring Security uses the
 * OIDC login path, not plain OAuth2) that ALSO implements UserDetails, so every
 * controller using @AuthenticationPrincipal UserDetails works identically for
 * form-login, OAuth2 and OIDC sessions.
 */
public class AppOidcUser extends DefaultOidcUser implements UserDetails {

    private final String email;

    public AppOidcUser(User user, OidcUser oidc) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
              oidc.getIdToken(), oidc.getUserInfo());
        this.email = user.getEmail();
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
