package app.fuelog.service;

import static app.fuelog.service.InstallationAuth.sha256;

import app.fuelog.repository.PairingRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** 로그인한 모바일 사용자와 기존 설치 헤더의 인증 주체를 확인한다. */
@Component
public class PairingActor {
    private final PairingRepository repository;

    public PairingActor(PairingRepository repository) {
        this.repository = repository;
    }

    public UUID requireUser(Jwt jwt) {
        if (jwt == null) unauthorized();
        if (!"authenticated".equals(jwt.getClaimAsString("role"))
                || !Boolean.FALSE.equals(jwt.getClaim("is_anonymous"))) forbidden();
        Map<String, Object> metadata = jwt.getClaimAsMap("app_metadata");
        if (metadata != null && metadata.get("actor_type") != null) forbidden();
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ex) {
            forbidden();
            return null;
        }
    }

    public UUID requireInstallation(UUID installation, String encodedSecret) {
        if (installation == null
                || encodedSecret == null
                || !encodedSecret.matches("[A-Za-z0-9_-]{43}")) unauthorized();
        byte[] secret;
        try {
            secret = Base64.getUrlDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException ex) {
            unauthorized();
            return null;
        }
        byte[] savedHash = repository.credentialHash(installation);
        if (secret.length != 32
                || savedHash == null
                || !MessageDigest.isEqual(savedHash, sha256(secret))) unauthorized();
        return installation;
    }

    private static void forbidden() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User login required");
    }

    private static void unauthorized() {
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Invalid installation credential");
    }
}
