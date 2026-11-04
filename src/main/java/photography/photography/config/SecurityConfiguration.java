package photography.photography.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Route-level authorisation. Rules are evaluated top to bottom (first match wins), so the specific "me"/admin rules sit above the public catalogue GETs.
 * Row-level ownership (a customer can only read their own bookings, ...) is enforced again inside the services.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    private final JwtAuthentication jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    /** Same list is used for the WebSocket handshake (see WebSocketConfig). */
    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String[] allowedOrigins;

    public SecurityConfiguration(JwtAuthentication jwtAuthFilter, AuthenticationProvider authenticationProvider) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.authenticationProvider = authenticationProvider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies are used for authentication
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(req -> req
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/user/auth/register", "/user/auth/login", "/user/auth/logout", "/auth/otp/verify", "/auth/otp/resend", "/auth/password/forgot", "/auth/password/resend", "/auth/password/verify", "/auth/password/reset").permitAll()
                        // The WebSocket HANDSHAKE is public because browsers cannot set an Authorization header on it.
                        // The STOMP CONNECT frame is authenticated in StompAuthChannelInterceptor.
                        .requestMatchers("/ws/**").permitAll()
                        // photographer workspace
                        .requestMatchers("/photographers/me", "/photographers/me/**").hasAuthority("photographer")
                        // public catalogue (read only)
                        .requestMatchers(HttpMethod.GET, "/categories", "/content/**", "/packages/best-value", "/reviews/featured", "/uploads/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/photographers", "/photographers/summaries", "/photographers/*", "/photographers/*/similar",
                                "/photographers/*/services", "/photographers/*/packages", "/photographers/*/reviews", "/photographers/*/availability",
                                "/photographers/*/slots", "/photographers/*/open-days").permitAll()
                        .requestMatchers(HttpMethod.POST, "/photographers/*/view").permitAll()
                        .requestMatchers(HttpMethod.POST, "/uploads").hasAnyAuthority("photographer", "admin")
                        .requestMatchers(HttpMethod.POST, "/uploads/avatar").hasAnyAuthority("client", "photographer", "admin")
                        // administration
                        .requestMatchers("/admin/**", "/analytics/platform").hasAuthority("admin")
                        // legacy endpoints that list or change other people's accounts
                        .requestMatchers("/user/clients/all", "/user/photographers/all", "/user/suspended/all", "/user/reservations/all",
                                "/user/suspension/**", "/user/unsuspension/**", "/user/delete/user/**").hasAuthority("admin")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> json(response, 401, "UNAUTHENTICATED", "Sign in required."))
                        .accessDeniedHandler((request, response, ex) -> json(response, 403, "FORBIDDEN", "You are not allowed to do that.")))
                .sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void json(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins).map(String::trim).filter(o -> !o.isEmpty()).toList();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
