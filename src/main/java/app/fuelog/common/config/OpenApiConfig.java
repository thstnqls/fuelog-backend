package app.fuelog.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI fuelogOpenAPI() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("supabaseBearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .info(new Info()
                        .title("Fuelog API")
                        .description("차량 앱 설치 등록과 모바일 사용자 차량 페어링 API. 모바일은 Supabase Bearer JWT, 차량 앱은 설치 ID·비밀값 헤더를 사용합니다.")
                        .version("v1"));
    }
}
