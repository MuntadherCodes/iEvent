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

    /**
     * Forces the deferred CSRF token to materialize BEFORE the view starts rendering.
     * Without this, pages that contain a POST form for a visitor with no session yet
     * (login/register) blow up mid-render: the response buffer has already flushed
     * when the CSRF processor tries to create the session, so the session cookie can
     * no longer be set and rendering dies with an IllegalStateException appended as
     * an error page fragment (observed locally as "ERROR 200" inside the login page).
     */
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
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/", "/browse", "/events/**", "/organizers/**",
                        "/auth/**", "/t/**", "/css/**", "/img/**", "/js/**",
                        "/favicon.ico", "/actuator/health", "/error")
                .permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/auth/login")
                .loginProcessingUrl("/auth/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .defaultSuccessUrl("/", false)
                .failureUrl("/auth/login?error"))
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/?signedout"))
            .addFilterAfter(new CsrfEagerLoadFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }
}
