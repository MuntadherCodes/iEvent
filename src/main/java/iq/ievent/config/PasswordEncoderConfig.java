package iq.ievent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Split out of SecurityConfig: UserService depends on PasswordEncoder, and
 * SecurityConfig now depends on filters (HostAccountGateFilter,
 * SuperAdminAuthFilter) that depend on UserService — defining the encoder
 * bean inside SecurityConfig itself closed that into a circular reference.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
