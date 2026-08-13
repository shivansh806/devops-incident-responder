package com.shivansh.incidentresponder.embedding;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the one piece of text that represents an incident in vector space.
 * <p>
 * <b>Why both sides live in this class.</b> Similarity search only means anything if the
 * stored vectors and the query vector were produced from text of the same shape. A stored
 * document and an incoming {@link LogAnalysis} are different types, so the temptation is to
 * build each one where it is needed - and the moment those two recipes drift apart, every
 * search silently starts ranking on the difference between the recipes rather than on the
 * difference between the incidents. The two {@code of} overloads exist so that cannot happen:
 * they take different arguments and both funnel into {@link #build}.
 * <p>
 * <b>What goes in: errorType, affectedService, keyEvidence.</b> Nothing else. The set is
 * bounded by what the query side can offer - a new incident has a {@code LogAnalysis} and
 * nothing more, so any field the stored document has and the query does not is unusable no
 * matter how informative it looks. {@code resolutionNotes} is the one that hurts to leave
 * out, and it is exactly the one that cannot be used: embedding a written postmortem on the
 * stored side and raw symptoms on the query side would rank incidents by how much a fresh
 * log dump reads like prose.
 * <p>
 * The retrieval key is not the payload. Matching happens on symptoms; the document that
 * comes back still carries its {@code resolutionNotes} for the Resolver Agent to read. Those
 * are separate jobs and they do not have to use the same text.
 * <p>
 * <b>What is left out, and why.</b> {@code confidence} scores our certainty, not the failure.
 * {@code firstOccurrence} is a timestamp - two incidents in March are not similar, so it is
 * pure noise. {@code severity} is a four-value judgement and the least stable field in the
 * baseline (see {@code docs/baseline.md}); embedding it would let the query vector move for
 * reasons that have nothing to do with the symptom.
 * <p>
 * <b>Why {@code keyEvidence} carries the weight.</b> {@code errorType} is identical across
 * INC-2103, INC-2331 and INC-2464, whose fixes contradict each other. What separates them -
 * connection acquisition slow but queries fine, versus a query that degraded five minutes
 * before the pool drained - is written in their evidence lines, and is observable at query
 * time. That is the whole reason retrieval can beat filtering on {@code errorType}.
 * <p>
 * <b>Size.</b> all-MiniLM-L6-v2 truncates at roughly 256 tokens and says nothing when it
 * does. This recipe lands around 60-90 tokens on the seeded data; the full document, with
 * resolution prose, would run past the limit and lose its tail silently. There is no guard
 * here on purpose - the honest place to measure real lengths is where the model is actually
 * called, on real data, rather than guessing a cap now.
 */
public final class IncidentEmbeddingText {

    /**
     * What the Analyzer reports when it cannot identify a service. It is a sentinel meaning
     * "no information", so it is dropped rather than embedded - keeping it would pull every
     * unidentified incident together on the strength of a word that describes none of them.
     */
    private static final String UNKNOWN_SERVICE = "unknown";

    private IncidentEmbeddingText() {
    }

    /** The stored side: text for the vector written onto the document. */
    public static String of(Incident incident) {
        return build(incident.errorType(), incident.affectedService(), incident.keyEvidence());
    }

    /** The query side: text for the vector a new incident is searched with. */
    public static String of(LogAnalysis analysis) {
        return build(analysis.errorType(), analysis.affectedService(), analysis.keyEvidence());
    }

    /**
     * A headline naming the failure and where it happened, then the evidence lines verbatim,
     * one per line.
     * <p>
     * Evidence is stripped of surrounding whitespace but otherwise untouched - it is quoted
     * log output, and the numbers inside it ({@code active=10}, {@code waiting=47},
     * {@code 3ms -> 9800ms}) are the discriminating signal, not decoration.
     */
    private static String build(ErrorType errorType, String affectedService, List<String> keyEvidence) {
        StringBuilder text = new StringBuilder(headline(errorType, affectedService));

        // Absent evidence is legitimate rather than an error: a NotALogFile diagnosis is
        // defined to carry none, and the headline alone still describes it correctly.
        if (keyEvidence != null) {
            for (String line : keyEvidence) {
                if (line != null && !line.isBlank()) {
                    text.append('\n').append(line.strip());
                }
            }
        }

        return text.toString();
    }

    /**
     * {@code "connection pool exhausted on payment-service"}.
     * <p>
     * Spelled out in lowercase words rather than reusing {@link ErrorType#jsonValue()}, and
     * that is a deliberate choice worth not "simplifying" away. The embedding model's
     * tokeniser lowercases before it splits, so {@code ConnectionPoolExhausted} arrives as
     * the single unspaced string {@code connectionpoolexhausted} and gets chopped into
     * meaningless sub-word fragments. The constant name has the word boundaries already, as
     * underscores - turning those into spaces hands the model three real words it has
     * embeddings for. Same information, in a form the model can actually read.
     */
    private static String headline(ErrorType errorType, String affectedService) {
        // Not defensive: nothing can legitimately produce an incident without a failure
        // class. ErrorType.fromModel never returns null and IncidentSeeder rejects a seed
        // that omits it, so a null here is a bug upstream and should say so loudly rather
        // than quietly embed a headless string that still looks like a valid vector.
        Objects.requireNonNull(errorType, "errorType is required to build embedding text");
        String failure = errorType.name().toLowerCase(Locale.ROOT).replace('_', ' ');

        if (affectedService == null
                || affectedService.isBlank()
                || affectedService.strip().equalsIgnoreCase(UNKNOWN_SERVICE)) {
            return failure;
        }
        return failure + " on " + affectedService.strip();
    }
}
