package app.fuelog.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger 공통 안내와 모바일·장치 인증 입력을 정의한다. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI fuelogOpenAPI() {
        return new OpenAPI()
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "supabaseBearer",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .description(
                                                        "모바일 로그인 후 받은 Supabase access token을 입력하세요."
                                                                + " Bearer 접두어는 Swagger가 붙입니다.")
                                                .scheme("bearer")
                                                .bearerFormat("JWT"))
                                .addSecuritySchemes(
                                        "installationBearer",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .description(
                                                        "장치 설치 비밀값의 base64url 문자열을 입력하세요. 모바일 JWT와"
                                                                + " 다르며 Bearer 접두어는 Swagger가 붙입니다.")
                                                .scheme("bearer")
                                                .bearerFormat("Installation secret")))
                .info(
                        new Info()
                                .title("Fuelog API")
                                .description(
                                        """
                                차량 장치 앱과 모바일 앱이 사용하는 Fuelog 백엔드 API입니다. 경로 앞에 /api를 붙이지 않습니다.

                                ### 인증값 선택
                                - **모바일:** Supabase 로그인 access token을 Authorization: Bearer로 보냅니다. /mobile/**와 /auth/me에서 사용합니다.
                                - **장치:** 설치 비밀값을 Authorization: Bearer로 보냅니다. 차량 연결·홈·기록·주유·정비·설정 API에서 사용합니다.
                                - **현재 사용자:** GET /installations/me의 authenticated_user_id가 마지막 QR 인증 사용자입니다. 추가 구간 QR 없이 이 사용자의 개인 홈을 조회합니다.
                                - **새 운행:** 시작 본문의 subjectId를 현재 인증 사용자에 고정합니다. 기존 candidateId 요청은 과거 기록 호환용입니다.

                                ### 설치 등록과 연결
                                /installations는 설치 ID와 32바이트 비밀값을 한 번 발급합니다. 이 비밀값은 장치 Bearer에도 사용할 수 있습니다.
                                /connection/requests는 앱이 생성한 32바이트 이상 비밀값의 최초 등록도 지원합니다.
                                기존 /pairing-requests와 /installations/me는 X-Installation-Id, X-Installation-Secret 헤더를 사용합니다.
                                두 연결 방식의 QR 형식이 다르므로 각각 대응하는 모바일 승인 API를 사용하세요.

                                ### 응답 읽기
                                - C01~C07은 차량 화면 구분, M으로 시작하는 번호는 모바일 화면 구분입니다.
                                - 문자열 시각은 UTC ISO-8601입니다. 예: 2026-11-07T01:00:00Z. epoch milliseconds는 UTC 기준 밀리초 숫자입니다.
                                - 거리 단위는 km, 연료 단위는 L이며 금액의 통화 필드도 확인하세요.
                                - operationId 등의 재시도 키는 같은 요청을 다시 보낼 때 유지합니다. 중복 저장을 막는 동작을 멱등 처리라고 합니다.
                                - 장치·모바일 업무 API 오류는 보통 {"code":"오류코드"}입니다. 기존 페어링 API는 ProblemDetail 형식을 사용합니다.
                                - /connection/reset은 사용 중단되었습니다. /connection/disconnect와 모바일 관리자 승인 API를 사용하세요.
                                """)
                                .version("v1"));
    }
}
