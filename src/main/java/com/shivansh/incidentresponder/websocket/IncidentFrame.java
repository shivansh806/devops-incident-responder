package com.shivansh.incidentresponder.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.IncidentResponse;

/**
 * One message on the incident stream: a type, and - for the two types that carry one - the
 * incident, in exactly the shape {@code POST /api/analyze} returns.
 *
 * <h2>Why there is an envelope at all</h2>
 * The stream was meant to be bare {@link IncidentResponse} objects, so the frontend would have
 * one contract to learn. One field was added, and it is here to answer a question the bare
 * object cannot: <b>what a null {@code resolution} means.</b>
 * <p>
 * {@link IncidentResponse#resolution()} is null in two situations that look identical on the
 * wire and mean opposite things:
 * <ul>
 *   <li><b>The Resolver ran and failed</b> - a 429 that survived the retry, a timeout, an
 *       unusable reply, a blank root cause. {@code ResolverService} absorbs all of these and
 *       returns null so the diagnosis is not lost with them.</li>
 *   <li><b>The Resolver was never asked</b> - the twenty seeded incidents, which were loaded
 *       straight into Mongo and carry a human write-up in {@code resolutionNotes} instead.</li>
 * </ul>
 * On {@code GET /api/incidents/{id}} that ambiguity is tolerable and is documented as such: the
 * caller named the id, so they know what they asked for. <b>On a push stream nobody asked for
 * anything</b>, and a dashboard that renders both as one empty box tells whoever is on call
 * that the system tried and gave up on an incident a human fixed in February.
 * <p>
 * The two cases separate perfectly by <em>which sender the frame came from</em>, so that is
 * what the type says. It is not a new field on {@link IncidentResponse}: that type is shared
 * with REST, and a push-only problem should not change the REST contract. It also would have
 * had exactly one possible value on the live path today, which is the shape of field that gets
 * filled in wrongly later.
 *
 * <h2>The three types</h2>
 * <table border="1">
 *   <tr><th>{@code type}</th><th>carries</th><th>a null {@code resolution} means</th></tr>
 *   <tr><td>{@code connected}</td><td>{@code backlog}</td><td>-</td></tr>
 *   <tr><td>{@code history}</td><td>{@code incident}</td><td><b>never asked</b></td></tr>
 *   <tr><td>{@code incident}</td><td>{@code incident}</td><td><b>tried and failed</b></td></tr>
 * </table>
 * <p>
 * {@code incident} carries no pending case. {@code IncidentService.analyzeAndRecord} is
 * synchronous - the Resolver has finished, one way or the other, before the incident is stored,
 * and the push happens after storage. So a live frame with no resolution is never "still
 * thinking", and the client needs no spinner state for one. If an asynchronous Resolver is ever
 * added, <em>that</em> is when a fourth type earns its place.
 *
 * <h2>{@code connected}</h2>
 * Sent once per connection, and it exists because of the quota. At roughly ten events a day the
 * gap between incidents is hours, and a silent socket is indistinguishable from a dead one. One
 * frame on connect proves the channel works, and its {@code backlog} says how many
 * {@code history} frames were sent. It is <b>not</b> guaranteed to arrive first - see
 * {@link IncidentWebSocketHandler} for why nothing on this stream is ordered.
 *
 * @param type     one of {@code connected}, {@code history}, {@code incident}. A string rather
 *                 than an enum because it is a wire value the frontend switches on, and
 *                 {@link AgentResolution}'s own constants set the precedent for spelling wire
 *                 vocabulary out where it is read.
 * @param backlog  how many {@code history} frames accompany this {@code connected} frame; null
 *                 on the other two types
 * @param incident the payload, byte-identical to what the REST endpoints return; null on
 *                 {@code connected}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentFrame(String type, Integer backlog, IncidentResponse incident) {

    public static final String CONNECTED = "connected";
    public static final String HISTORY = "history";
    public static final String INCIDENT = "incident";

    /** The hello frame. Proves the socket is alive when there is nothing else to say. */
    public static IncidentFrame connected(int backlog) {
        return new IncidentFrame(CONNECTED, backlog, null);
    }

    /** Replayed on connect. A null resolution here means the Resolver was never asked. */
    public static IncidentFrame history(IncidentResponse incident) {
        return new IncidentFrame(HISTORY, null, incident);
    }

    /** Pushed live. A null resolution here means the Resolver ran and failed. */
    public static IncidentFrame incident(IncidentResponse incident) {
        return new IncidentFrame(INCIDENT, null, incident);
    }
}
