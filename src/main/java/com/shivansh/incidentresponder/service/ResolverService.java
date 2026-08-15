package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.agent.ResolverAgent;
import com.shivansh.incidentresponder.agent.ResolverOutput;
import com.shivansh.incidentresponder.agent.ResolverPromptText;
import com.shivansh.incidentresponder.embedding.SimilarIncident;
import com.shivansh.incidentresponder.embedding.SimilarIncidentSearch;
import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieves precedent, runs the Resolver Agent over it, and turns the raw reply into a
 * trustworthy {@link AgentResolution}.
 * <p>
 * The counterpart to {@link AnalyzerService}, and it defends against the same thing: model
 * output is untrusted input. Confidence can arrive above 1.0, lists can be null or hold
 * nulls, and {@code similarIncidents} can name an incident that was never supplied. Callers
 * get a normalised value or nothing.
 * <p>
 * <b>Two failures, degraded differently.</b> Neither is allowed to lose an analysis the LLM
 * has already been billed for:
 * <ul>
 *   <li><b>Retrieval fails</b> - Atlas unreachable, index missing, nothing back filled yet.
 *       The Resolver still runs, with an empty candidate set. The prompt has a branch for
 *       exactly this, and a broken vector index should cost precedent, not the whole
 *       recommendation.</li>
 *   <li><b>The agent call fails</b> - Groq 429, timeout, unparseable reply. Returns null.
 *       The incident is still stored and returned with its diagnosis, which is precisely
 *       what week 1 delivered. On a 100,000 token daily budget a rate limit is an expected
 *       condition, not an exception worth failing a request over.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolverService {

    /**
     * How many past incidents the Resolver is shown.
     * <p>
     * <b>Three, because three is the number that was measured.</b> Against the live index,
     * with a synthetic new incident rather than a stored one, all three contrasting
     * connection-pool resolutions arrive inside the first three slots - the hardest case the
     * corpus contains, and the one the whole design is built around. Full numbers in
     * {@code docs/retrieval.md}.
     * <p>
     * <b>Why not four.</b> The fourth slot on that query is INC-2378, the thread-pool
     * incident that is really a connection-pool incident, and it is a genuinely useful match.
     * But {@link SimilarIncidentSearch#findSimilar} has no relevance floor: it returns
     * {@code limit} documents whatever they score. For an incident with real precedent slot
     * four is a 0.85 match; for a novel one it is a 0.6 match formatted identically, and an
     * irrelevant precedent that reads as authoritative is a worse failure than a missing
     * corroborating one. The cross-type win also survives at three in the direction that
     * matters - INC-2378's own query returns the pool trio as its entire top three.
     * <p>
     * No score threshold accompanies this, deliberately. A cutoff would be a number
     * calibrated on one query over 21 documents written by one person, which is the kind of
     * single-observation fix this project has twice watched evaporate. Retrieval is local and
     * deterministic, so raising this later costs one line and a free re-measurement.
     */
    static final int SIMILAR_INCIDENT_LIMIT = 3;

    private final ResolverAgent resolverAgent;
    private final SimilarIncidentSearch similarIncidentSearch;

    /**
     * @param analysis a finished diagnosis, straight from the Analyzer
     * @return the recommendation, or {@code null} when the agent could not produce a usable
     *         one. Null rather than an empty {@link AgentResolution} because the two mean
     *         different things: an empty one would assert that the model considered this and
     *         had nothing to say.
     */
    public AgentResolution resolve(LogAnalysis analysis) {
        List<SimilarIncident> matches = retrieve(analysis);

        ResolverOutput output;
        try {
            output = resolverAgent.resolve(
                    ResolverPromptText.analysis(analysis),
                    ResolverPromptText.pastIncidents(matches));
        } catch (RuntimeException e) {
            // Deliberately not rethrown. See the class javadoc: the diagnosis is worth more
            // than the recommendation, and it has already been paid for.
            log.error("Resolver agent call failed, keeping the diagnosis without a resolution", e);
            return null;
        }
        if (output == null) {
            log.error("Resolver agent returned no result, keeping the diagnosis without a resolution");
            return null;
        }

        return toResolution(output, analysis, suppliedIds(matches));
    }

    private List<SimilarIncident> retrieve(LogAnalysis analysis) {
        try {
            List<SimilarIncident> matches = similarIncidentSearch.findSimilar(analysis, SIMILAR_INCIDENT_LIMIT);
            log.info("Retrieved {} past incidents as precedent for {} on {}",
                    matches.size(), analysis.errorType(), analysis.affectedService());
            return matches;
        } catch (RuntimeException e) {
            log.error("Similar-incident retrieval failed, resolving without precedent", e);
            return List.of();
        }
    }

    /**
     * A blank root cause fails the whole resolution rather than being patched over. The
     * actions rest on the cause; a list of steps with nothing stating what they address is
     * the shape of output this agent is specifically built to avoid producing, and shipping
     * it under a plausible-looking confidence would be worse than shipping nothing.
     */
    private AgentResolution toResolution(ResolverOutput output, LogAnalysis analysis, Set<String> suppliedIds) {
        String rootCause = output.rootCause() == null ? null : output.rootCause().strip();
        if (rootCause == null || rootCause.isEmpty()) {
            log.error("Resolver returned no rootCause, discarding the resolution");
            return null;
        }

        AgentResolution resolution = new AgentResolution(
                rootCause,
                cleanActions(output.suggestedActions()),
                citedIds(output.similarIncidents(), suppliedIds),
                clampConfidence(output.confidence()));

        if (resolution.confidence() > analysis.confidence()) {
            // The prompt forbids this; a prompt is a request, not a guarantee. Left as the
            // model reported it rather than silently clamped - a Resolver surer of the fix
            // than the Analyzer is of the diagnosis is a finding worth being able to count,
            // and a quiet correction here would hide it.
            log.warn("Resolver confidence {} exceeds the diagnosis confidence {}",
                    resolution.confidence(), analysis.confidence());
        }

        log.info("Resolution: confidence={} actions={} drewOn={}",
                resolution.confidence(), resolution.suggestedActions().size(), resolution.similarIncidents());
        return resolution;
    }

    private static List<String> cleanActions(List<String> actions) {
        if (actions == null) {
            log.warn("Resolver omitted suggestedActions");
            return List.of();
        }
        return actions.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(action -> !action.isEmpty())
                .toList();
    }

    /**
     * Keeps only ids that were actually put in front of the model, in the order it cited
     * them, without duplicates.
     * <p>
     * A citation is a claim about provenance, and an id the model invented makes the answer
     * look grounded in history that does not exist. That is a more dangerous kind of wrong
     * than a bad recommendation, because it is the part a reader would check the answer
     * against. The prompt's worked example uses {@code EXAMPLE-A} and {@code EXAMPLE-B}
     * precisely so that an echoed example id is caught here rather than looking like a real
     * incident.
     */
    private static List<String> citedIds(List<String> cited, Set<String> suppliedIds) {
        if (cited == null || cited.isEmpty()) {
            return List.of();
        }

        Set<String> kept = new LinkedHashSet<>();
        List<String> invented = new ArrayList<>();
        for (String id : cited) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.strip();
            if (suppliedIds.contains(trimmed)) {
                kept.add(trimmed);
            } else {
                invented.add(trimmed);
            }
        }

        if (!invented.isEmpty()) {
            log.warn("Resolver cited {} incident id(s) that were never supplied, dropping them: {}",
                    invented.size(), invented);
        }
        return List.copyOf(kept);
    }

    private static Set<String> suppliedIds(List<SimilarIncident> matches) {
        return matches.stream()
                .map(SimilarIncident::incident)
                .map(Incident::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static double clampConfidence(Double confidence) {
        if (confidence == null) {
            log.warn("Resolver omitted confidence, defaulting to 0.0");
            return 0.0;
        }
        return Math.clamp(confidence, 0.0, 1.0);
    }
}
