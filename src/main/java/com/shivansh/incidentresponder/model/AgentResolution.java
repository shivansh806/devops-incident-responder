package com.shivansh.incidentresponder.model;

import java.util.List;

/**
 * The Resolver Agent's answer: what actually broke, and what to do about it.
 * <p>
 * The counterpart to {@link LogAnalysis}, and deliberately the other half of the split.
 * {@code LogAnalysis} says what happened and is forbidden from suggesting fixes; everything
 * here is a recommendation and none of it re-states the diagnosis. Two agents, two types,
 * one boundary - which is what makes this a multi-agent system rather than one prompt asked
 * to do both jobs and graded on neither.
 * <p>
 * <b>This is not {@code Incident.resolutionNotes} and must never be written into it.</b>
 * Those notes are what a human recorded after actually fixing the incident, and they are the
 * precedent every future retrieval reads. Writing a generated recommendation there would
 * feed the Resolver its own guesses back as evidence, and the corpus would drift towards
 * whatever the model already believes. The two fields look similar and mean opposite things:
 * one is a record, this is a proposal.
 *
 * @param decidingEvidence the observation that separated the retrieved precedents, and which
 *                         one it selected - or one of the defined values below when there was
 *                         nothing to separate. Exposed rather than kept internal because it is
 *                         the most useful line in the whole response for someone paged at 3am:
 *                         a recommendation you can audit is worth more than one you cannot.
 *                         Never null.
 * @param rootCause        the single mechanism that caused the failure, in plain English, for
 *                         someone paged at 3am who has not read the logs. One cause - if the
 *                         evidence cannot separate two candidates, that fact is the answer,
 *                         not a list of both.
 * @param suggestedActions what to do, in the order to do it. The first entry is the one to
 *                         act on now. One coherent plan for one cause, never one action per
 *                         candidate cause.
 * @param similarIncidents ids of the past incidents this answer actually rests on, including
 *                         any it explicitly reasoned against - rejecting a precedent shapes
 *                         the answer as much as following one. Empty is a legitimate value
 *                         and means nothing in the history applied. Validated against the ids
 *                         actually supplied, so a hallucinated id never reaches the API.
 * @param confidence       0.0-1.0, for how well the evidence and precedent support this
 *                         recommendation. A different question from {@link LogAnalysis#confidence()},
 *                         which scores the diagnosis this rests on.
 */
public record AgentResolution(
        String decidingEvidence,
        String rootCause,
        List<String> suggestedActions,
        List<String> similarIncidents,
        double confidence
) {

    /**
     * {@code decidingEvidence} when the retrieved precedents point the same way, so there is
     * nothing to discriminate between.
     * <p>
     * A required field with nothing to say is a field that gets filled in anyway. An invented
     * discriminator is worse than an absent one - it is a fabricated justification, and unlike
     * a fabricated incident id there is no supplied set to check it against. So the prompt
     * gives the model somewhere honest to go, and says in as many words that declaring there
     * is no discriminator beats manufacturing one.
     */
    public static final String PRECEDENTS_AGREE = "PRECEDENTS AGREE";

    /** {@code decidingEvidence} when nothing was retrieved, or nothing retrieved applies. */
    public static final String NO_RELEVANT_PRECEDENT = "NO RELEVANT PRECEDENT";

    /**
     * {@code decidingEvidence} when the model left the field empty. Substituted by
     * {@code ResolverService} rather than failing the resolution: the field is an audit line,
     * and losing a sound recommendation because its justification is missing is the wrong
     * trade. It is logged, because an empty one means the mechanism did not fire on that call.
     */
    public static final String NOT_STATED = "NOT STATED";
}
