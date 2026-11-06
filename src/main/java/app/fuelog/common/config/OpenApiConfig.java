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
                        .description("C01 차량 연결, C02 차량 홈·수동 기록, C03 사용자 확인, C04 운행 요약과 M06 확인함 API. /connection, /vehicle, /trip-user, /recording-intervals, /sync, /mobile-inbox 경로는 설치별 무작위 Bearer 자격 증명을 사용합니다. 개인 운행 요약·전달에는 X-Trip-User-Candidate도 필요합니다. /trip-user/revoke는 해당 모바일 사용자 JWT도 받습니다. /mobile 경로는 Supabase JWT를 사용합니다. 기존 /pairing-requests 경로는 설치 ID·비밀값 헤더를 사용합니다.")
                        .version("v1"));
    }
}
