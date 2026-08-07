package com.shivansh.incidentresponder.controller;

import com.shivansh.incidentresponder.model.AnalyzeRequest;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.service.AnalysisFailedException;
import com.shivansh.incidentresponder.service.AnalyzerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalyzeController {

    private final AnalyzerService analyzerService;

    @PostMapping("/analyze")
    public LogAnalysis analyze(@RequestBody AnalyzeRequest request) {
        return analyzerService.analyze(request.logs(), request.serviceName());
    }

    /** Caller's fault: missing or oversized logs. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleInvalidRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** Upstream's fault: the model call or its parsing failed. 502 rather than 500. */
    @ExceptionHandler(AnalysisFailedException.class)
    ResponseEntity<Map<String, String>> handleAnalysisFailure(AnalysisFailedException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
