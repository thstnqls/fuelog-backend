package app.fuelog.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** 장치·모바일 API 예외를 HTTP 상태와 code 필드로 변환하며 내부 예외 내용은 숨긴다. */
@RestControllerAdvice(
        assignableTypes = {
            ConnectionController.class,
            MobileConnectionController.class,
            ConnectionSettingsController.class,
            VehicleHomeController.class,
            MobileTripUserController.class,
            TripUserCandidateController.class,
            TripSummaryController.class,
            MobileInboxController.class,
            FuelCandidateController.class,
            MaintenanceController.class,
            MobileMaintenanceController.class
        })
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConnectionErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> known(ResponseStatusException ex) {
        String reason = ex.getReason();
        String code = reason != null && reason.matches("[A-Z_]+") ? reason : "REQUEST_FAILED";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("code", code));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Map<String, String>> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CONNECTION_CONFLICT"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR"));
    }
}
