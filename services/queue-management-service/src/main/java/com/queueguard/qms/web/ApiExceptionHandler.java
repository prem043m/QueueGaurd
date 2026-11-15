package com.queueguard.qms.web;
import com.queueguard.common.dto.ApiResponse; import com.queueguard.common.exception.BusinessException; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestControllerAdvice public class ApiExceptionHandler { @ExceptionHandler(BusinessException.class) ResponseEntity<ApiResponse<Void>> handle(BusinessException e){return ResponseEntity.status(e.getStatus()).body(ApiResponse.fail(e.getMessage()));} }
