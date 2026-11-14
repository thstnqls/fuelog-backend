package app.fuelog;

import app.fuelog.common.config.SecurityConfig;
import app.fuelog.controller.InstallationUserController;
import app.fuelog.dto.PairingDtos;
import app.fuelog.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InstallationUserController.class)
@Import({SecurityConfig.class, InstallationUserTests.WebSecurity.class})
class InstallationUserTests {
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class WebSecurity {}
    @Autowired MockMvc mvc;
    @MockitoBean InstallationAuth auth;
    @MockitoBean PairingService pairing;
    @MockitoBean SupabaseUserService users;
    @MockitoBean JwtDecoder decoder;
    @Test void onlyTheCurrentlyConnectedUserCanBeLookedUp() throws Exception {
        UUID installation=UUID.randomUUID(),user=UUID.randomUUID();
        when(auth.require("Bearer device")).thenReturn(new InstallationAuth.Identity(installation,new byte[32]));
        when(auth.require(null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var linked=new PairingDtos.Installation(installation,UUID.randomUUID(),true,user,"alias","EV");
        when(pairing.installation(installation)).thenReturn(linked);
        when(users.find(user)).thenReturn(new SupabaseUserService.User(user,"driver@example.com"));
        mvc.perform(get("/installation/users/"+user)).andExpect(status().isUnauthorized());
        mvc.perform(get("/installation/users/"+UUID.randomUUID()).header("Authorization","Bearer device")).andExpect(status().isForbidden());
        verifyNoInteractions(users);
        mvc.perform(get("/installation/users/"+user).header("Authorization","Bearer device"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.user_id").value(user.toString())).andExpect(jsonPath("$.email").value("driver@example.com"));
        when(pairing.installation(installation)).thenReturn(new PairingDtos.Installation(installation,linked.vehicle_id(),false,null,null,"EV"));
        mvc.perform(get("/installation/users/"+user).header("Authorization","Bearer device")).andExpect(status().isForbidden());
        verify(users,times(1)).find(user);
    }
    @Test void disconnectWhileSupabaseIsRespondingRejectsTheEmail() throws Exception {
        UUID installation=UUID.randomUUID(),user=UUID.randomUUID(),vehicle=UUID.randomUUID();
        when(auth.require("Bearer device")).thenReturn(new InstallationAuth.Identity(installation,new byte[32]));
        when(pairing.installation(installation)).thenReturn(new PairingDtos.Installation(installation,vehicle,true,user,"alias","EV"),
                new PairingDtos.Installation(installation,vehicle,false,null,null,"EV"));
        when(users.find(user)).thenReturn(new SupabaseUserService.User(user,"driver@example.com"));
        mvc.perform(get("/installation/users/"+user).header("Authorization","Bearer device")).andExpect(status().isForbidden());
    }
}
