package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.model.AgentResolution;
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
    private final ResolverService resolverService;
    private final IncidentRepository incidentRepository;

    /**
     * Analyses the logs, resolves the diagnosis against past incidents, and stores both.
     * <p>
     * The two agents run in sequence and the order is the point: the Resolver's input is the
     * Analyzer's output, and retrieval happens in between, keyed on the diagnosis. Note that
     * the search cannot return the incident being analysed - it is not saved until after, and
     * would carry no embedding even then.
     * <p>
     * <b>The two failures are traded differently.</b> A storage failure fails the whole call,
     * losing work the LLM was already billed for: from week 2 on the value of this endpoint
     * is the collection it builds up, so an incident that quietly failed to persist is worse
     * than a visible error. A Resolver failure does not fail the call - it degrades to a null
     * resolution, which is exactly the week 1 response and still worth storing. See
     * {@link ResolverService}.
     *
     * @param serviceName the originating service if the caller knows it, else null
     * @return the saved incident, carrying its new id
     */
    public Incident analyzeAndRecord(String rawLogs, String serviceName) {
        LogAnalysis analysis = analyzerService.analyze(rawLogs, serviceName);
        AgentResolution resolution = resolverService.resolve(analysis);

        // The id lands on the returned instance, not on the argument - Incident is a record,
        // so Spring Data cannot mutate it and rebuilds it instead. Returning the argument
        // here would hand back an incident with a null id.
        Incident saved = incidentRepository.save(Incident.from(analysis, resolution, Instant.now()));

        log.info("Recorded incident {}: {} on {} ({})", saved.id(), saved.errorType(), saved.affectedService(),
                resolution == null ? "no resolution" : "resolved");
        return saved;
    }

    /**
     * @throws IncidentNotFoundException if nothing is stored under that id
     */
    public Incident findById(String id) {
        return incidentRepository.findById(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }
}
