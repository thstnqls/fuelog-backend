package app.fuelog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Fuelog HTTP API 서버의 실행 진입점. */
@SpringBootApplication
public class FuelogApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuelogApplication.class, args);
    }
}
