package app.fuelog.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/installations", "/pairing-requests",
                                "/pairing-requests/*/confirm", "/pairing-requests/*/cancel").permitAll()
                        .requestMatchers(HttpMethod.GET, "/installations/me", "/pairing-requests/current",
                                "/pairing-requests/*").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${fuelog.auth.jwk-set-uri}") String jwkUri,
                          @Value("${fuelog.auth.issuer}") String issuer,
                          @Value("${fuelog.auth.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(jwt -> {
            var result = defaults.validate(jwt);
            if (result.hasErrors()) return result;
            if (!jwt.getAudience().contains(audience))
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
            return OAuth2TokenValidatorResult.success();
        });
        return decoder;
    }
}
