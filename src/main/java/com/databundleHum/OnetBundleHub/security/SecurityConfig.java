package com.databundleHum.OnetBundleHub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Security configuration.
 *
 * IMPORTANT — authority vs role:
 *   JwtAuthFilter sets authorities as the full string from the JWT claim,
 *   e.g. "ROLE_RESELLER", "ROLE_SUPER_ADMIN".
 *
 *   hasRole("RESELLER")           → Spring prepends ROLE_ → looks for "ROLE_RESELLER"  ✓
 *   hasAuthority("ROLE_RESELLER") → exact match                                         ✓
 *
 *   We use hasAuthority() throughout to be explicit and avoid any ambiguity,
 *   especially since @EnableMethodSecurity is also active.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter      jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper       objectMapper;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:4028,http://localhost:5173,https://data-bay-gh.vercel.app,https://databaygh.shop,https://www.databaygh.shop,https://datapack-lac.vercel.app,https://cosigner-mold-april.ngrok-free.dev}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authEx) ->
                                writeJsonError(response, HttpStatus.UNAUTHORIZED,
                                        "Missing or invalid authentication token"))
                        .accessDeniedHandler((request, response, accessEx) ->
                                writeJsonError(response, HttpStatus.FORBIDDEN,
                                        "You do not have permission to access this resource"))
                )

                .headers(headers -> headers
                        .contentTypeOptions(withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )

                .authorizeHttpRequests(auth -> auth

                        // ── Public auth endpoints ────────────────────────────────────
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/reset-password"
                        ).permitAll()

                        // ── Public pricing ───────────────────────────────────────────
                        // Admin's public price table only — never exposes buying price
                        // or reseller-custom pricing. Safe and required to be callable
                        // by guests (see PricingController#getPublicPricing javadoc).
                        // /effective is intentionally NOT listed here — it stays behind
                        // isAuthenticated() since it resolves reseller-specific pricing.
                        .requestMatchers(HttpMethod.GET, "/api/v1/pricing/public").permitAll()

                        // Checker (BECE/WASSCE) equivalent of the above — same reasoning,
                        // publicPriceGhc only, never reseller cost. See
                        // CheckerPublicPricingResponse's Javadoc.
                        .requestMatchers(HttpMethod.GET, "/api/checkers/pricing").permitAll()

                        // ── Public order status + guest checkout ─────────────────────
                        .requestMatchers(HttpMethod.GET,  "/api/v1/orders/status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/guest").permitAll()

                        // ── Paystack webhook ─────────────────────────────────────────
                        .requestMatchers("/api/webhooks/**").permitAll()

                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/pricing/public",
                                "/api/v1/pricing/store/**"
                        ).permitAll()
                        // ── Public storefront ────────────────────────────────────────
                        // Browse (GET) and guest checkout (POST .../orders/guest) are
                        // fully public. Wallet checkout (POST .../orders/wallet) is
                        // caught by anyRequest().authenticated() below.
                        .requestMatchers(HttpMethod.GET,  "/api/v1/storefront/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/storefront/*/orders/guest").permitAll()

                        // ── Affiliate & reseller referral redirects ──────────────────
                        .requestMatchers(HttpMethod.GET, "/a/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/ref/**").permitAll()

                        // ── Swagger / OpenAPI ────────────────────────────────────────
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml"
                        ).permitAll()

                        // ── Admin area ───────────────────────────────────────────────
                        // hasAuthority = exact match on the authority string set by
                        // JwtAuthFilter ("ROLE_SUPER_ADMIN").
                        .requestMatchers("/api/v1/admin/**")
                        .hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers("/api/admin/**")
                        .hasAuthority("ROLE_SUPER_ADMIN")

                        // ── Reseller area ────────────────────────────────────────────
                        // POST /apply is open to any authenticated user (they're applying).
                        // Everything else under /reseller/** needs ROLE_RESELLER.
                        .requestMatchers(HttpMethod.POST, "/api/v1/reseller/apply")
                        .authenticated()
                        .requestMatchers("/api/v1/reseller/**")
                        .hasAuthority("ROLE_RESELLER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/reseller/wallet")
                        .hasAuthority("ROLE_RESELLER")

                        // ── Wallet ───────────────────────────────────────────────────
                        .requestMatchers("/api/v1/wallet/**").authenticated()

                        // ── Everything else requires authentication ───────────────────
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeJsonError(HttpServletResponse response,
                                HttpStatus status,
                                String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "status",  status.value(),
                "error",   status.getReasonPhrase(),
                "message", message
        )));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Accept", "x-paystack-signature", "ngrok-skip-browser-warning"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}