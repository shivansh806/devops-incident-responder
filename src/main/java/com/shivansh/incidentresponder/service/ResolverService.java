package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.agent.ResolverAgent;
import com.shivansh.incidentresponder.agent.ResolverOutput;
import com.shivansh.incidentresponder.agent.ResolverPromptText;
import com.shivansh.incidentresponder.embedding.SimilarIncident;
import com.shivansh.incidentresponder.embedding.SimilarIncidentSearch;
import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import dev.langchain4j.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * How long to wait before the single retry of a rate-limited Resolver call.
     * <p>
     * <b>Derived from measurement, not picked.</b> Groq's per-minute bucket holds 8,000 tokens
     * and refills continuously at 8,000/60 = ~133 tokens a second. The deficit inside one event
     * is {@code analyzerTokens + resolverTokens - 8000}: measured live at 4,715 + 3,585 = 8,300,
     * a deficit of 300, which Groq itself priced at 2.25 seconds. Against the largest measured
     * Analyzer call (5,121) the deficit is 706, or 5.3 seconds.
     * <p>
     * 15 seconds covers a deficit of ~2,000 tokens - an Analyzer plus Resolver totalling 10,000
     * against a measured 8,300-8,706. That margin is for longer log dumps and more verbose
     * retrieved precedent, both of which move the number.
     * <p>
     * <b>It is only ever paid on the failure path.</b> A call that fits waits nothing, and after
     * a cache hit the Analyzer costs zero tokens so the Resolver has the whole bucket and never
     * reaches this. That is the whole reason the wait lives here rather than as a fixed delay in
     * {@code IncidentService.analyzeAndRecord}, which is shared with the synchronous REST
     * endpoint and would pay the delay on every request whether it needed it or not.
     */
    private final Duration rateLimitBackoff;

    public ResolverService(ResolverAgent resolverAgent,
                           SimilarIncidentSearch similarIncidentSearch,
                           @Value("${incident.resolver.rate-limit-backoff:15s}") Duration rateLimitBackoff) {
        this.resolverAgent = resolverAgent;
        this.similarIncidentSearch = similarIncidentSearch;
        this.rateLimitBackoff = rateLimitBackoff;
    }

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
            output = callAgent(analysis, matches);
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

    /**
     * One call, retried once and only when the failure was a rate limit.
     *
     * <h2>Why this exists</h2>
     * A single incident is two model calls back to back, and together they do not fit in the
     * per-minute bucket. The Analyzer goes first and takes the larger share, so <b>the Resolver
     * is always the call that gets refused</b> - and because this class absorbs its own
     * failures, the symptom is an incident stored with a diagnosis and no recommendation, with
     * nothing anywhere reporting a problem. Confirmed live; see {@code docs/caching.md}.
     *
     * <h2>Why LangChain4j's own retry does not cover it</h2>
     * It retries rate limits already - {@code RetryUtils.DEFAULT_RETRY_POLICY} is
     * {@code maxRetries=2, delayMillis=500, backoffExp=1.5}, so it waits roughly 500ms then
     * 750ms and gives up after about 1.25 seconds. The measured requirement was 2.25. It was
     * close and it lost, and <b>the policy is not configurable</b>: {@code OpenAiChatModel}'s
     * builder exposes no retry knob in 1.18.1, nor does the Spring starter. So the wait has to
     * be here.
     *
     * <h2>Why one retry</h2>
     * The same reasoning the Kafka consumer and the baseline harness already settled on. A
     * per-minute limit clears in seconds, so one delayed attempt recovers it. A per-day limit
     * refuses every attempt however many are allowed, and each one costs wall clock on a thread
     * that is holding a Kafka partition.
     */
    private ResolverOutput callAgent(LogAnalysis analysis, List<SimilarIncident> matches) {
        String analysisText = ResolverPromptText.analysis(analysis);
        String precedentText = ResolverPromptText.pastIncidents(matches);
        try {
            return resolverAgent.resolve(analysisText, precedentText);
        } catch (RuntimeException first) {
            if (!isRateLimit(first)) {
                throw first;
            }
            log.warn("Resolver was rate limited - the Analyzer call for this same incident has "
                            + "already spent most of the per-minute budget. Waiting {}s and retrying once",
                    rateLimitBackoff.toSeconds());
            if (!sleep(rateLimitBackoff)) {
                throw first;
            }
            return resolverAgent.resolve(analysisText, precedentText);
        }
    }

    /**
     * Matches the exception type and, as a fallback, the message.
     * <p>
     * The type alone would be enough today - LangChain4j maps a 429 to
     * {@link RateLimitException} at the model boundary, which is what the live run threw. The
     * message check covers the case where something between here and there wraps it, which is
     * exactly the kind of change a library upgrade makes silently. Getting this wrong in the
     * false-negative direction costs a resolution; in the false-positive direction it costs one
     * pointless wait on a call that was going to fail anyway.
     */
    private static boolean isRateLimit(Throwable thrown) {
        for (Throwable cause = thrown; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof RateLimitException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return false if the wait was interrupted, in which case the caller must give up rather
     *         than retry. An interrupt here means the application is shutting down or the Kafka
     *         container is stopping the consumer; starting a fresh model call at that moment
     *         would bill for a result nobody will read.
     */
    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting out a Resolver rate limit, giving up the retry");
            return false;
        }
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
                decidingEvidence(output.decidingEvidence()),
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

    /**
     * An empty {@code decidingEvidence} does not discard the resolution, unlike an empty
     * {@code rootCause}. The field is an audit line rather than the answer, and losing a sound
     * recommendation because its justification is missing is the wrong trade.
     * <p>
     * It is logged at WARN because it means something specific: the field is declared first
     * precisely so the model has to write it before committing to a cause, so an empty one is
     * the mechanism not firing on that call. Worth being able to count.
     */
    private static String decidingEvidence(String stated) {
        if (stated == null || stated.isBlank()) {
            log.warn("Resolver omitted decidingEvidence - it committed to a cause without stating what decided it");
            return AgentResolution.NOT_STATED;
        }
        return stated.strip();
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
