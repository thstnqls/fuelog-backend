package app.fuelog.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;

@Service
public class SupabaseUserService {
    public record User(UUID user_id, String email) {}
    private final ObjectMapper json;
    private final String base, key;
    private final HttpClient http;

    @Autowired
    public SupabaseUserService(ObjectMapper json, @Value("${SUPABASE_URL:}") String base,
            @Value("${SUPABASE_SERVICE_ROLE_KEY:}") String key) {
        this(json, base, key, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }
    SupabaseUserService(ObjectMapper json, String base, String key, HttpClient http) {
        this.json=json; this.base=base.replaceAll("/+$", ""); this.key=key; this.http=http;
    }
    public User find(UUID id) {
        if (base.isBlank() || key.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_LOOKUP_NOT_CONFIGURED");
        try {
            var request=HttpRequest.newBuilder(URI.create(base+"/auth/v1/admin/users/"+id))
                    .timeout(Duration.ofSeconds(5)).header("apikey",key).header("Authorization","Bearer "+key).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"USER_NOT_FOUND");
            if (response.statusCode()!=200) throw new IllegalStateException();
            var user=json.readTree(response.body());
            if (!id.toString().equals(user.path("id").asText())) throw new IllegalArgumentException();
            var email=user.path("email");
            if (!email.isMissingNode() && !email.isNull() && !email.isString()) throw new IllegalArgumentException();
            String value=email.isString()?email.asText().trim():null;
            if (value!=null && (value.length()>320 || value.chars().anyMatch(Character::isISOControl))) throw new IllegalArgumentException();
            return new User(id,value==null || value.isBlank()?null:value);
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Upstream bodies and exceptions can contain credentials; expose only a fixed error.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"USER_LOOKUP_UNAVAILABLE");
        }
    }
}
