package com.shivansh.incidentresponder.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * One incident as it is stored in MongoDB: what the Analyzer found, plus everything that
 * happens to it afterwards.
 * <p>
 * This deliberately carries its own copy of the {@link LogAnalysis} fields rather than
 * nesting one. The two types answer to different pressures - {@code LogAnalysis} is a
 * message that is regenerated on every call and may change shape whenever the API does,
 * while a document written here is permanent and has to stay readable years later. Sharing
 * one type would mean every API change silently rewrote what "stored" means, and it would
 * force storage-only fields (the embedding vector arriving in the next step) out onto the
 * wire. The coupling lives in {@link #from} and {@link #toAnalysis} alone, so a change to
 * {@code LogAnalysis} breaks the build at exactly the two places where the decision to
 * follow it should be made.
 * <p>
 * Kept flat rather than nested so the document reads as one screen in Atlas. Note two
 * consequences of Spring Data mapping this instead of Jackson: {@code errorType} is written
 * as the constant name ({@code CACHE_UNAVAILABLE}), not the PascalCase wire format, and null
 * fields are omitted from the document entirely rather than stored as null.
 * <p>
 * Raw logs are not stored. They are up to 50,000 characters per request, and
 * {@code keyEvidence} is already the compact description of the failure - it is what week
 * 2's similarity search will embed.
 *
 * @param id              Mongo's {@code _id}, null until the document has been saved
 * @param errorType       failure class, from the closed {@link ErrorType} vocabulary
 * @param affectedService service where the failure originated, or {@code "unknown"}
 * @param severity        blast radius of the incident
 * @param firstOccurrence when the failure itself began, read out of the logs by the model.
 *                        Null when the logs carried no parseable timestamp, and - unlike
 *                        {@link #analyzedAt} - it is a judgement that can be wrong.
 * @param keyEvidence     log lines quoted verbatim from the input that support the diagnosis
 * @param confidence      0.0-1.0 score for how well the evidence supports the diagnosis
 * @param resolutionNotes how the incident was actually put right. Null until someone (a
 *                        human today, the Resolver Agent later) fills it in; nothing in this
 *                        step writes it. It is here so the document covers the whole
 *                        lifecycle rather than just the diagnosis.
 * @param analyzedAt      when this analysis was run, off the server clock. Never null, and
 *                        never inferred - that is what makes it trustworthy for ordering
 *                        and retention where {@code firstOccurrence} is not.
 */
@Document(collection = "incidents")
public record Incident(
        @Id String id,
        ErrorType errorType,
        String affectedService,
        Severity severity,
        Instant firstOccurrence,
        List<String> keyEvidence,
        double confidence,
        String resolutionNotes,
        Instant analyzedAt
) {

    /**
     * Builds an unsaved incident from a fresh analysis. The id is null on purpose: Mongo
     * assigns it on insert, and because this is an immutable record the id arrives on the
     * instance {@code save()} <em>returns</em>, not on the one passed in.
     */
    public static Incident from(LogAnalysis analysis, Instant analyzedAt) {
        return new Incident(
                null,
                analysis.errorType(),
                analysis.affectedService(),
                analysis.severity(),
                analysis.firstOccurrence(),
                analysis.keyEvidence(),
                analysis.confidence(),
                null,
                analyzedAt);
    }

    /** Reassembles the diagnosis half of this document for the API to return. */
    public LogAnalysis toAnalysis() {
        return new LogAnalysis(errorType, affectedService, severity, firstOccurrence, keyEvidence, confidence);
    }
}
