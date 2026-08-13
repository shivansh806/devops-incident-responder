package com.shivansh.incidentresponder.controller;

import com.shivansh.incidentresponder.model.IncidentResponse;
import com.shivansh.incidentresponder.service.IncidentNotFoundException;
import com.shivansh.incidentresponder.service.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    /** Reads back one stored incident, in the same shape {@code POST /api/analyze} returned. */
    @GetMapping("/{id}")
    public IncidentResponse findOne(@PathVariable String id) {
        return IncidentResponse.of(incidentService.findById(id));
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    ResponseEntity<Map<String, String>> handleNotFound(IncidentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
