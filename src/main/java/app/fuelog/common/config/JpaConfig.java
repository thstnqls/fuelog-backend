package app.fuelog.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA 엔티티의 생성·수정 시각 자동 기록을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
public class JpaConfig {}
