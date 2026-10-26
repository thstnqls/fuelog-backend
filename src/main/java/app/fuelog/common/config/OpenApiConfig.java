package app.fuelog.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI fuelogOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fuelog API")
                        .description("Fuelog 데이터 저장 API")
                        .version("v1"));
    }
}
