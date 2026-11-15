package com.queueguard.qms.web;
import org.springframework.web.bind.annotation.*; import java.util.Map;
@RestController public class HealthController { @GetMapping("/health") public Map<String,String> health(){return Map.of("status","UP","service","queue-management-service");} }
