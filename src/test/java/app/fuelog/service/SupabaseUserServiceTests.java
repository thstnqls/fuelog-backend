package app.fuelog.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SupabaseUserServiceTests {
    @Test void retrievesEmailByIdAndRejectsMismatchedResponses() throws Exception {
        UUID id=UUID.randomUUID();
        var body=new java.util.concurrent.atomic.AtomicReference<>("{\"id\":\""+id+"\",\"email\":\"driver@example.com\"}");
        var status=new java.util.concurrent.atomic.AtomicInteger(200);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            assertEquals("/auth/v1/admin/users/"+id,e.getRequestURI().getPath());
            assertEquals("server-test-key",e.getRequestHeaders().getFirst("apikey"));
            assertEquals("Bearer server-test-key",e.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        try {
            var service=new SupabaseUserService(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"server-test-key",HttpClient.newHttpClient());
            assertEquals(new SupabaseUserService.User(id,"driver@example.com"),service.find(id));
            body.set("{\"id\":\""+UUID.randomUUID()+"\",\"email\":\"other@example.com\"}");
            assertEquals(502,assertThrows(ResponseStatusException.class,()->service.find(id)).getStatusCode().value());
            status.set(404);assertEquals(404,assertThrows(ResponseStatusException.class,()->service.find(id)).getStatusCode().value());
            status.set(401);body.set("server-test-key");
            var error=assertThrows(ResponseStatusException.class,()->service.find(id));
            assertEquals(502,error.getStatusCode().value());assertFalse(error.toString().contains("server-test-key"));
        } finally {server.stop(0);}
    }
    @Test void missingServerKeyDoesNotAttemptLookup() {
        var service=new SupabaseUserService(new ObjectMapper(),"https://example.supabase.co","");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.find(UUID.randomUUID())).getStatusCode().value());
    }
}
