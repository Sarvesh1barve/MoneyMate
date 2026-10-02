package com.moneymate;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

public class ApiError extends RuntimeException {
  final int status;
  final Object detail;

  public ApiError(int status, String message) {
    this(status, message, Map.of());
  }

  public ApiError(int status, String message, Object detail) {
    super(message);
    this.status = status;
    this.detail = detail;
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new ApiError(400, message);
  }
}

@RestControllerAdvice
class ErrorAdvice {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> api(ApiError e) {
    return ResponseEntity.status(e.status)
        .body(Map.of("message", e.getMessage(), "detail", e.detail));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    java.time.DateTimeException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("message", "Invalid input. Check all fields."));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> constraint(Exception e) {
    return ResponseEntity.status(409)
        .body(Map.of("message", "This change conflicts with existing data."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    return ResponseEntity.status(500)
        .body(Map.of("message", "The server could not complete this request."));
  }
}
