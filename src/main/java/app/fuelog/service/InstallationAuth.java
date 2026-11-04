package app.fuelog.service;

import app.fuelog.repository.PairingRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InstallationAuth {
    private final PairingRepository repository;

    public InstallationAuth(PairingRepository repository) { this.repository = repository; }

    public record Identity(UUID id, byte[] secret) {}

    public Identity require(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ")) unauthorized();
        String encoded = bearer.substring(7);
        if (!encoded.matches("[A-Za-z0-9_-]{43,}")) unauthorized();
        byte[] secret;
        try { secret = Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { unauthorized(); return null; }
        if (secret.length < 32) unauthorized();
        UUID id = repository.installationByCredentialHash(sha256(secret));
        if (id == null) unauthorized();
        return new Identity(id, secret);
    }

    public static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void unauthorized() {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_INSTALLATION_CREDENTIAL");
    }
}
