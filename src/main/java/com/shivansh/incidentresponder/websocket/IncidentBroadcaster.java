package com.shivansh.incidentresponder.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.IncidentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the open dashboard connections and writes finished incidents to all of them.
 *
 * <h2>Failing open, always</h2>
 * <b>A dead socket must never fail a diagnosis.</b> Every method here catches, logs and carries
 * on - the same trade {@code AnalysisCache} makes for Redis and {@code ResolverService} makes
 * for retrieval. It is the reason {@code IncidentService} calls {@link #broadcast} without a
 * try/catch: the guarantee lives here, in one place, rather than being restated at every call
 * site where it could be restated differently.
 * <p>
 * The work being protected is expensive and already paid for. By the time this runs, two Groq
 * calls have been billed and the incident is in Mongo. Losing that to a browser tab someone
 * closed would be the worst trade in the pipeline.
 *
 * <h2>Why it uses the application's ObjectMapper</h2>
 * Injected, not constructed - and that is deliberately the <em>opposite</em> of what
 * {@code IncidentEventParser} and {@code AnalysisCache} do. Those two need semantics that
 * differ from the REST path, so they configure their own. This one needs output that is
 * <b>identical</b> to the REST path, because "the frontend learns one contract" is the whole
 * point of the frame payload being an {@link IncidentResponse}. A private mapper here would
 * make that identity a matter of two configurations happening to agree, and the first thing to
 * break would be {@code Instant}: left at Jackson's default it serialises as an epoch number
 * rather than the ISO-8601 string {@code GET /api/incidents/{id}} returns, and the dashboard
 * would need two date parsers for one type. Sharing the mapper makes it identical by
 * construction. {@code JacksonConfig}'s unknown-property handler is a <em>de</em>serialisation
 * concern and has no effect on anything written here.
 *
 * <h2>Sessions</h2>
 * Keyed by {@link WebSocketSession#getId()} rather than held in a set, because what is stored
 * is a {@code ConcurrentWebSocketSessionDecorator} wrapping the session Spring hands back on
 * close - the wrapper does not equal its delegate, so removal by object would silently never
 * remove anything and every closed tab would leak a session. See
 * {@link IncidentWebSocketHandler} for why the decorator is not optional.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentBroadcaster {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    /**
     * Pushes a stored incident to every connected client as an {@code incident} frame.
     * <p>
     * Takes the stored {@link Incident} rather than a frame so that callers on the pipeline
     * never touch the wire format: {@code IncidentService} hands over what it just saved and
     * knows nothing about types, envelopes or sessions. The embedding vector cannot leak here
     * for the same reason it cannot leak from the REST endpoints - {@link IncidentResponse#of}
     * hand-picks fields, so a new storage field has to be exposed on purpose.
     * <p>
     * <b>Never throws.</b> Not for a serialisation failure, not for a broken pipe, not for a
     * client that stopped reading.
     */
    public void broadcast(Incident incident) {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            send(IncidentFrame.incident(IncidentResponse.of(incident)), sessions.values());
        } catch (RuntimeException e) {
            // Only reachable if IncidentResponse.of or the fan-out itself fails; the per-session
            // sends already have their own guard. Caught anyway - see the class javadoc.
            log.error("Broadcasting incident {} failed, the incident is stored regardless",
                    incident.id(), e);
        }
    }

    /** Writes one frame to one client. Used by the handler for the on-connect replay. */
    void send(IncidentFrame frame, WebSocketSession session) {
        send(frame, List.of(session));
    }

    private void send(IncidentFrame frame, Collection<WebSocketSession> targets) {
        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.error("Could not serialise a '{}' frame, dropping it", frame.type(), e);
            return;
        }

        for (WebSocketSession session : targets) {
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                // A send that fails has already lost this client - either the connection is
                // gone or the decorator hit its buffer limit and terminated it. Drop the
                // session rather than trying again on the next incident: a socket that cannot
                // be written to now will not recover on its own, and keeping it means paying
                // this failure once per incident forever.
                log.warn("Dropping WebSocket session {} after a failed send: {}",
                        session.getId(), e.toString());
                deregister(session);
                closeQuietly(session);
            }
        }
    }

    void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("Dashboard connected on session {} ({} now open)", session.getId(), sessions.size());
    }

    void deregister(WebSocketSession session) {
        if (sessions.remove(session.getId()) != null) {
            log.info("Dashboard disconnected on session {} ({} still open)", session.getId(), sessions.size());
        }
    }

    /** Open connections. Exposed for tests and for a log line, not for the pipeline to branch on. */
    public int connectionCount() {
        return sessions.size();
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
            // Already unwritable; that is why we are here.
        }
    }
}
