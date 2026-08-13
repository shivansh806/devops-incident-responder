package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Owns the incident lifecycle: analyse a log dump, and keep the result.
 * <p>
 * This sits above {@link AnalyzerService} rather than inside it. Analysis is a pure
 * question-and-answer with the model and stays independently testable; recording is what
 * turns an answer into an incident. Week 3's Kafka consumer needs exactly this pair and
 * must not have to reimplement it from the controller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final AnalyzerService analyzerService;
    private final IncidentRepository incidentRepository;

    /**
     * Analyses the logs and stores the result.
     * <p>
     * A storage failure fails the whole call, losing an analysis the LLM was already billed
     * for. That is the intended trade: from week 2 on, the value of this endpoint is the
     * collection it builds up, so an incident that quietly failed to persist is worse than
     * a visible error.
     *
     * @param serviceName the originating service if the caller knows it, else null
     * @return the saved incident, carrying its new id
     */
    public Incident analyzeAndRecord(String rawLogs, String serviceName) {
        LogAnalysis analysis = analyzerService.analyze(rawLogs, serviceName);

        // The id lands on the returned instance, not on the argument - Incident is a record,
        // so Spring Data cannot mutate it and rebuilds it instead. Returning the argument
        // here would hand back an incident with a null id.
        Incident saved = incidentRepository.save(Incident.from(analysis, Instant.now()));

        log.info("Recorded incident {}: {} on {}", saved.id(), saved.errorType(), saved.affectedService());
        return saved;
    }

    /**
     * @throws IncidentNotFoundException if nothing is stored under that id
     */
    public Incident findById(String id) {
        return incidentRepository.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }
}
