package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.embedding.SimilarIncident;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders the Resolver's two input blocks: the diagnosis, and the past incidents retrieved
 * for it.
 * <p>
 * This is prompt engineering expressed as code rather than as prose, and it is where two of
 * the four devices against bad precedent-handling actually live. The other two are rules in
 * {@link ResolverAgent#SYSTEM_PROMPT}; these two are structural, because a rule that
 * contradicts the visible shape of the data loses to the data.
 * <p>
 * <b>Device one: the ordering carries no relevance signal.</b> Candidates are sorted
 * <em>oldest first by when the failure began</em>, not by similarity. Telling a model "this
 * list is unordered" while handing it an obviously ordered list creates a conflict it
 * resolves badly - it re-reads position as rank. A chronological order is deterministic,
 * visibly not a ranking, and once the order is named the model cannot infer relevance from
 * it. Shuffling would also break the rank signal, but it would make the Resolver
 * non-reproducible, and an unmeasurable agent is worse than a biased one.
 * <p>
 * <b>Device two: the similarity score is labelled for what it measures.</b> The score is
 * shown - {@code SimilarIncident} carries it precisely so "this happened before" stays
 * distinguishable from "nothing here resembles this" - but labelled {@code symptom
 * similarity}, because that is all it is. {@code docs/retrieval.md} measured the rest: the
 * two past incidents that reached the <em>same</em> conclusion were the least similar pair
 * of their group, and the one that disagreed with both sat between them as everyone's
 * nearest neighbour. The prompt states that finding, so the number arrives already
 * discounted rather than as a ranking to obey.
 * <p>
 * <b>No filtering happens here.</b> Every retrieved candidate is rendered, however low it
 * scored. A cutoff would be a number guessed from one query over 21 documents written by one
 * person, and this project has twice recorded what acting on a single observation costs. The
 * limit at the call site is the only control on how many arrive.
 */
public final class ResolverPromptText {

    private static final String NONE_RETRIEVED =
            "No past incidents were retrieved. There is no precedent to draw on for this one.";

    /**
     * Stated again here, next to the data, and not only in the system prompt. The rule is
     * cheap to repeat and expensive to have missed, and this is the copy sitting closest to
     * the thing it describes.
     */
    private static final String ORDERING_NOTE = """
            Listed OLDEST FIRST, by the date each failure began. That is chronological order
            and nothing else - it is not a ranking, and position carries no relevance.
            """;

    private ResolverPromptText() {
    }

    /**
     * The diagnosis, flattened to labelled lines. Deliberately not JSON: the Resolver is
     * being asked to reason about this, not to parse it, and prose-shaped input keeps the
     * evidence lines readable as the log output they are.
     */
    public static String analysis(LogAnalysis analysis) {
        StringBuilder text = new StringBuilder()
                .append("failure: ").append(analysis.errorType().jsonValue()).append('\n')
                .append("service: ").append(analysis.affectedService()).append('\n')
                .append("severity: ").append(analysis.severity()).append('\n')
                .append("began: ").append(instant(analysis.firstOccurrence())).append('\n')
                .append("confidence in this diagnosis: ").append(score(analysis.confidence())).append('\n')
                .append("evidence, quoted verbatim from the logs:\n");

        appendEvidence(text, analysis.keyEvidence());
        return text.toString();
    }

    /**
     * The retrieved candidates, oldest first. Each carries its resolution notes in full -
     * those are the payload, and the discriminator that separates two contradictory
     * precedents is usually written there in plain words.
     */
    public static String pastIncidents(List<SimilarIncident> matches) {
        if (matches == null || matches.isEmpty()) {
            return NONE_RETRIEVED;
        }

        StringBuilder text = new StringBuilder(ORDERING_NOTE);
        for (SimilarIncident match : oldestFirst(matches)) {
            text.append('\n').append(render(match));
        }
        return text.toString();
    }

    /**
     * Nulls last, then by id, so the order is total and reproducible. A null
     * {@code firstOccurrence} is legitimate - it means the logs carried no parseable
     * timestamp - and an incident that cannot be dated has no place on a timeline, so it
     * goes to the end rather than being assumed ancient.
     */
    private static List<SimilarIncident> oldestFirst(List<SimilarIncident> matches) {
        return matches.stream()
                .sorted(Comparator
                        .comparing((SimilarIncident match) -> match.incident().firstOccurrence(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(match -> match.incident().id(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static String render(SimilarIncident match) {
        Incident incident = match.incident();

        StringBuilder text = new StringBuilder()
                .append('[').append(incident.id()).append("]\n")
                .append("  failure: ").append(incident.errorType().jsonValue())
                .append(" on ").append(incident.affectedService()).append('\n')
                .append("  began: ").append(instant(incident.firstOccurrence()))
                .append("   severity: ").append(incident.severity()).append('\n')
                .append("  symptom similarity to the current incident: ").append(score(match.score()))
                .append('\n')
                .append("  evidence:\n");

        appendEvidence(text, incident.keyEvidence());

        text.append("  how it was actually resolved:\n");
        if (incident.resolutionNotes() == null || incident.resolutionNotes().isBlank()) {
            // A retrieved incident with no notes is a symptom match with nothing to learn
            // from. Saying so beats an empty heading the model might fill in for itself.
            text.append("    Not recorded. This incident tells you the symptoms have occurred\n")
                    .append("    before, and nothing about what fixed them.\n");
        } else {
            text.append("    ").append(incident.resolutionNotes().strip()).append('\n');
        }
        return text.toString();
    }

    private static void appendEvidence(StringBuilder text, List<String> keyEvidence) {
        if (keyEvidence == null || keyEvidence.isEmpty()) {
            text.append("    (none recorded)\n");
            return;
        }
        for (String line : keyEvidence) {
            if (line != null && !line.isBlank()) {
                text.append("    - ").append(line.strip()).append('\n');
            }
        }
    }

    /** Two decimal places, and {@link Locale#ROOT} so a comma separator never appears. */
    private static String score(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String instant(Instant value) {
        return value == null ? "unknown - the logs carried no usable timestamp" : value.toString();
    }
}
