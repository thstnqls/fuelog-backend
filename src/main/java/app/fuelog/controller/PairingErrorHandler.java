package app.fuelog.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 기존 페어링 API의 DB 중복 충돌을 HTTP 409 응답으로 변환한다. */
@RestControllerAdvice(assignableTypes = PairingController.class)
public class PairingErrorHandler {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrityConflict() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setDetail("Pairing conflicts with an existing vehicle or request");
        return problem;
    }
}
