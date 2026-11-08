package app.fuelog;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Fuelog HTTP API 서버의 실행 진입점. */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class FuelogApplication {

    @PostConstruct
    void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(FuelogApplication.class, args);
    }
}
