package datphong.vn.config;

import datphong.vn.security.JwtAuthenticationFilter;
import datphong.vn.security.RestErrorHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Lớp bảo vệ thứ nhất (endpoint). Lớp thứ hai là @PreAuthorize (chức năng), lớp thứ ba là kiểm tra
 * chủ sở hữu/data scope trong service và query (chống IDOR).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final RestErrorHandlers errorHandlers;
    private final AppProperties props;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(11);
    }

    /**
     * JwtAuthenticationFilter là @Component nên Spring Boot sẽ tự đăng ký thêm một lần ở mức servlet container.
     * Tắt đăng ký đó: filter chỉ chạy BÊN TRONG security filter chain (đúng thứ tự, không xử lý JWT hai lần).
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter f) {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/auth/**", "/public/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Prometheus: chỉ khi có đúng token scrape (actuator nhạy cảm không được public)
                        .requestMatchers("/actuator/prometheus").access((auth, ctx) -> new AuthorizationDecision(
                                !props.getMetricsToken().isBlank()
                                        && props.getMetricsToken().equals(ctx.getRequest().getHeader("X-Metrics-Token"))))
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // Webhook: không JWT, được bảo vệ bằng HMAC + timestamp/replay window + eventId unique
                        .requestMatchers("/payments/fakebank/webhook").permitAll()
                        // GUEST: tìm kiếm / xem chi tiết / báo giá / đọc đánh giá công khai
                        .requestMatchers(HttpMethod.GET, "/properties", "/properties/**", "/rooms/search", "/rooms/*/quote").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(errorHandlers).accessDeniedHandler(errorHandlers))
                .headers(h -> h.frameOptions(f -> f.deny()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
