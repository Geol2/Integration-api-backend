package com.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4부터 기본 JSON 라이브러리가 Jackson 3(tools.jackson)으로 바뀌어,
 * Jackson 2(com.fasterxml) ObjectMapper 빈을 더 이상 자동으로 만들어 주지 않습니다.
 * WebPushService 등은 아직 Jackson 2 ObjectMapper를 주입받으므로 여기서 직접 만들어 줍니다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
