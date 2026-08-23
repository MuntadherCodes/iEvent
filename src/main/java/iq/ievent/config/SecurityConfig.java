package iq.ievent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final boolean googleEnabled;
    private final org.springframework.beans.factory.ObjectProvider<
            org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                    org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest,
                    org.springframework.security.oauth2.core.user.OAuth2User>> googleUserService;
    private final org.springframework.beans.factory.ObjectProvider<
            org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                    org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
                    org.springframework.security.oauth2.core.oidc.user.OidcUser>> googleOidcUserService;

    public SecurityConfig(
            @org.springframework.beans.factory.annotation.Value("${app.google.client-id:}") String googleClientId,
            org.springframework.beans.factory.ObjectProvider<
                    org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                            org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest,
                            org.springframework.security.oauth2.core.user.OAuth2User>> googleUserService,
            org.springframework.beans.factory.ObjectProvider<
                    org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                            org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
                            org.springframework.security.oauth2.core.oidc.user.OidcUser>> googleOidcUserService) {
        this.googleEnabled = googleClientId != null && !googleClientId.isBlank();
        this.googleUserService = googleUserService;
        this.googleOidcUserService = googleOidcUserService;
    }

    /**
     * Forces the deferred CSRF token to materialize BEFORE the view starts rendering.
     * Without this, pages that contain a POST form for a visitor with no session yet
     * (login/register) blow up mid-render: the response buffer has already flushed
     * when the CSRF processor tries to create the session, so the session cookie can
     * no longer be set and rendering dies with an IllegalStateException appended as
     * an error page fragment (observed locally as "ERROR 200" inside the login page).
     */
    /** Hosts land on their console after login; everyone else follows the saved
     *  request (deep link) or goes home. */
    static class HostAwareSuccessHandler
            extends org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler {
        HostAwareSuccessHandler() {
            setDefaultTargetUrl("/");
        }
        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                org.springframework.security.core.Authentication authentication)
                throws IOException, ServletException {
            // Explicit ?next= continuation (e.g. "sign in to finish checkout")
            // beats everything: buyer returns to the exact page with their
            // ticket selection intact. Set by AuthController, validated here.
            var session = request.getSession(false);
            Object next = session == null ? null : session.getAttribute("LOGIN_NEXT");
            if (next instanceof String n && n.startsWith("/") && !n.startsWith("//") && !n.contains("://")) {
                session.removeAttribute("LOGIN_NEXT");
                getRedirectStrategy().sendRedirect(request, response, n);
                return;
            }
            var cache = new org.springframework.security.web.savedrequest.HttpSessionRequestCache();
            boolean hasSaved = cache.getRequest(request, response) != null;
            boolean host = authentication.getAuthorities().stream().anyMatch(a ->
                    "ROLE_HOST".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
            if (!hasSaved && host) {
                getRedirectStrategy().sendRedirect(request, response, "/host");
                return;
            }
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    static class CsrfEagerLoadFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken(); // resolve now → session/cookie created before any output
            }
            filterChain.doFilter(request, response);
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // The embeddable event page (/e/**) must be frameable on ANY external
        // site (that's the whole point of the sales widget's iframe mode), so
        // the default X-Frame-Options: DENY is written for every path EXCEPT
        // /e/**. All other pages stay clickjacking-protected.
        org.springframework.security.web.util.matcher.RequestMatcher notEmbed =
                request -> !request.getRequestURI().startsWith("/e/");
        http
            .headers(headers -> headers
                .frameOptions(frame -> frame.disable())
                .addHeaderWriter(new org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter(
                        notEmbed,
                        new org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter(
                                org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY))))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/", "/browse", "/events/**", "/organizers/**",
                        "/auth/**", "/t/**", "/e/**", "/l/**", "/invite/*", "/newsletter", "/media/**", "/css/**", "/img/**", "/js/**",
                        "/favicon.ico", "/sw.js", "/actuator/health", "/error", "/set-lang", "/.well-known/**")
                .permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/auth/login")
                .loginProcessingUrl("/auth/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler(new HostAwareSuccessHandler())
                .failureUrl("/auth/login?error"))
            .rememberMe(remember -> remember
                .rememberMeParameter("remember-me")
                .key("ievent-remember")
                .tokenValiditySeconds(60 * 60 * 24 * 30))
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/?signedout"))
            .addFilterAfter(new CsrfEagerLoadFilter(), BasicAuthenticationFilter.class);
        if (googleEnabled) {
            http.oauth2Login(oauth -> oauth
                .loginPage("/auth/login")
                // Google sends the "openid" scope → the OIDC path is the one that
                // actually runs; the plain userService stays as a fallback.
                .userInfoEndpoint(u -> u
                        .userService(googleUserService.getObject())
                        .oidcUserService(googleOidcUserService.getObject()))
                .successHandler(new HostAwareSuccessHandler())
                // Distinct from form login's failureUrl above: without this,
                // Spring's default OAuth2 failure handler redirects to the
                // exact same "/auth/login?error" the password form uses, so
                // the login page showed "wrong email or password" for a
                // failed Google sign-in — misleading, since no password was
                // ever typed. See auth/login.html's oauth_error block.
                .failureUrl("/auth/login?oauth_error"));
        }
        return http.build();
    }
}
