package iq.ievent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final boolean googleEnabled;
    private final String rememberMeKey;
    private final HostAccountGateFilter hostAccountGateFilter;
    private final SuperAdminAuthFilter superAdminAuthFilter;
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
            @org.springframework.beans.factory.annotation.Value("${app.security.remember-me-key:}") String rememberMeKey,
            org.springframework.beans.factory.ObjectProvider<
                    org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                            org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest,
                            org.springframework.security.oauth2.core.user.OAuth2User>> googleUserService,
            org.springframework.beans.factory.ObjectProvider<
                    org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                            org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
                            org.springframework.security.oauth2.core.oidc.user.OidcUser>> googleOidcUserService,
            HostAccountGateFilter hostAccountGateFilter,
            SuperAdminAuthFilter superAdminAuthFilter) {
        this.googleEnabled = googleClientId != null && !googleClientId.isBlank();
        // No configured key: sign remember-me cookies with a per-boot random secret
        // rather than a constant anyone can read in the repository.
        this.rememberMeKey = rememberMeKey != null && !rememberMeKey.isBlank()
                ? rememberMeKey : java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
        this.googleUserService = googleUserService;
        this.googleOidcUserService = googleOidcUserService;
        this.hostAccountGateFilter = hostAccountGateFilter;
        this.superAdminAuthFilter = superAdminAuthFilter;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // The embeddable event page (/e/**) must be frameable on ANY external
        // site (that's the whole point of the sales widget's iframe mode), so
        // the default X-Frame-Options: DENY is written for every path EXCEPT
        // /e/**. All other pages stay clickjacking-protected.
        org.springframework.security.web.util.matcher.RequestMatcher notEmbed =
                request -> !RequestPaths.appPath(request).startsWith("/e/");
        // Belt-and-suspenders alongside the noindex meta tag + robots.txt disallow:
        // an HTTP header survives even a redirect or a crawler that ignores the
        // other two, and covers every /admin/** response, not just the rendered pages.
        org.springframework.security.web.util.matcher.RequestMatcher isAdmin =
                request -> RequestPaths.under(request, "/admin");
        http
            .headers(headers -> headers
                .frameOptions(frame -> frame.disable())
                .addHeaderWriter(new org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter(
                        notEmbed,
                        new org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter(
                                org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY)))
                .addHeaderWriter(new org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter(
                        isAdmin,
                        new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "X-Robots-Tag", "noindex, nofollow, noarchive"))))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/", "/browse", "/events/**", "/organizers/**",
                        "/auth/**", "/t/**", "/e/**", "/l/**", "/invite/*", "/newsletter", "/contact", "/about", "/help", "/pricing", "/how-it-works", "/features", "/solutions", "/guides", "/guides/**", "/privacy", "/terms", "/media/**", "/css/**", "/img/**", "/js/**",
                        "/favicon.ico", "/sw.js", "/robots.txt", "/sitemap.xml", "/llms.txt", "/actuator/health", "/error", "/set-lang", "/.well-known/**", "/api/events/suggest",
                        // /admin/** is gated by SuperAdminAuthFilter (a shared .env password, not a
                        // user account) rather than Spring Security's own authentication.
                        "/admin/**")
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
                .key(rememberMeKey)
                .tokenValiditySeconds(60 * 60 * 24 * 30))
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/?signedout"))
            // A CSRF failure under /admin (typically a stale token from a session that
            // expired, or from a page left open across an app restart — sessions are
            // in-memory) previously surfaced as a raw 403 whitelabel page. Redirecting
            // to the login form instead just asks for the password again.
            .exceptionHandling(handling -> handling.accessDeniedHandler((request, response, ex) -> {
                if (RequestPaths.under(request, "/admin")) {
                    response.sendRedirect(request.getContextPath() + "/admin/login");
                } else {
                    new org.springframework.security.web.access.AccessDeniedHandlerImpl().handle(request, response, ex);
                }
            }))
            .addFilterAfter(new CsrfEagerLoadFilter(), BasicAuthenticationFilter.class)
            .addFilterAfter(hostAccountGateFilter, BasicAuthenticationFilter.class)
            .addFilterAfter(superAdminAuthFilter, BasicAuthenticationFilter.class);
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
