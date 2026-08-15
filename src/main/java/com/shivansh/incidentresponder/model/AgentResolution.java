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
        String rootCause,
        List<String> suggestedActions,
        List<String> similarIncidents,
        double confidence
) {
}
