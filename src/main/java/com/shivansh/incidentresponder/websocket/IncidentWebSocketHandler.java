package com.shivansh.incidentresponder.websocket;

import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.IncidentResponse;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The dashboard's end of the stream: accepts a connection, replays a little history onto it,
 * and hands it to {@link IncidentBroadcaster} for everything that happens afterwards.
 *
 * <h2>What a client sees on connect, and why it is not nothing</h2>
 * <b>A small backlog, decided by the quota rather than by taste.</b> At roughly ten events a day
 * - see the cost note in CLAUDE.md - the gap between two incidents is measured in hours. A
 * live-only stream would leave the dashboard blank for most of a working day, and a blank
 * dashboard cannot be told apart from a broken one. Replaying the most recent few means the
 * first paint has content, and the {@code connected} frame means an empty one is still provably
 * alive.
 * <p>
 * <b>On a fresh database that backlog is entirely seeded history.</b> The newest seeded
 * {@code analyzedAt} is 2026-08-09 and the seed runs back to February, so ten slots fill with
 * {@code INC-} documents long before any live incident is competing for them. That is not a
 * defect - they are real resolved incidents and worth showing - but it is exactly why the
 * replay is tagged {@code history} rather than pushed as ordinary incidents. Every one of them
 * has a null {@code resolution} that means "never asked", and week 4 must not draw them as
 * failures. See {@link IncidentFrame}.
 *
 * <h2>Registered first, read second - and the duplicate that buys</h2>
 * Between reading the backlog and joining the broadcast there is a window, and an incident
 * finishing inside it goes one of two ways depending on the order:
 * <ul>
 *   <li><b>Read, then register</b> - it is in neither. The client never learns it existed and
 *       has no way to find out.</li>
 *   <li><b>Register, then read</b> - it is in both. The client is told twice.</li>
 * </ul>
 * The second is chosen. Every frame carries the incident's {@code id}, so a dashboard keyed on
 * id absorbs a duplicate by replacing a row; a dropped incident is invisible and unrecoverable.
 * That ordering is the one thing in this class worth a test, and
 * {@code IncidentWebSocketHandlerTest} pins it.
 *
 * <h2>Nothing on this stream is ordered</h2>
 * The consequence of the above: a live {@code incident} frame can overtake the replay, so
 * arrival order is not chronological and {@code connected} is not guaranteed to arrive first.
 * <b>Clients sort by {@code analyzedAt} and never by arrival.</b> This would be true anyway -
 * WebSocket orders frames from one sender, and there are two here. The replay itself is sent
 * oldest-first so that a client appending naively still ends up with the newest at the bottom.
 *
 * <h2>Why the session is wrapped</h2>
 * {@link WebSocketSession#sendMessage} is <b>not thread-safe</b>, and this endpoint has two
 * writers by design: the connection thread sending the replay, and the Kafka consumer thread
 * broadcasting. Concurrent sends interleave and corrupt frames.
 * {@link ConcurrentWebSocketSessionDecorator} serialises them behind a queue.
 * <p>
 * It also solves a problem the raw session would push onto the pipeline. {@code broadcast} is
 * called inline from {@code IncidentService.analyzeAndRecord}, which is shared with
 * {@code POST /api/analyze} - so on a raw session <b>one slow dashboard would add its own
 * latency to every REST request</b>. The decorator's send-time and buffer limits cap that: a
 * client that stops reading is terminated rather than allowed to hold the pipeline or grow the
 * server's heap without bound.
 */
@Slf4j
@Component
public class IncidentWebSocketHandler extends TextWebSocketHandler {

    /**
     * How long a single send may take before the session is treated as lost, in milliseconds.
     * <p>
     * This is the ceiling a stuck client can add to {@code POST /api/analyze}, which is what
     * sizes it. Five seconds is generous against a pipeline whose own two model calls take
     * upwards of a minute, and short enough that a half-open TCP connection does not sit in
     * the request path until the OS notices.
     */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /**
     * How much unsent data may queue for one client before it is terminated, in bytes.
     * <p>
     * An incident frame is a few kilobytes - {@code keyEvidence} and {@code suggestedActions}
     * are the bulk of it, and the embedding is not on the wire. 512KB is therefore well over a
     * hundred frames behind, which at this event rate is more than a day. A client that far
     * behind is not slow, it is gone.
     */
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final IncidentBroadcaster broadcaster;
    private final IncidentRepository incidentRepository;

    /**
     * How many past incidents a connecting client is sent.
     * <p>
     * Ten is a first paint, not a history view - enough that the dashboard opens with something
     * on it, few enough that the query and the fan-out stay trivial. Reading the archive is
     * what {@code GET /api/incidents/{id}} and week 4's list view are for; a socket is a poor
     * pagination API. Set {@code incident.websocket.backlog=0} for a live-only stream, which is
     * also how to see what the blank-dashboard problem above actually looks like.
     */
    private final int backlogSize;

    public IncidentWebSocketHandler(IncidentBroadcaster broadcaster,
                                    IncidentRepository incidentRepository,
                                    @Value("${incident.websocket.backlog:10}") int backlogSize) {
        this.broadcaster = broadcaster;
        this.incidentRepository = incidentRepository;
        this.backlogSize = backlogSize;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);

        // FIRST. Everything below this line takes time, and an incident finishing during it
        // must reach this client. See the class javadoc: a duplicate is recoverable, a gap
        // is not.
        broadcaster.register(concurrent);

        List<IncidentResponse> backlog = backlog();
        broadcaster.send(IncidentFrame.connected(backlog.size()), concurrent);
        for (IncidentResponse incident : backlog) {
            broadcaster.send(IncidentFrame.history(incident), concurrent);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // The raw session, not the decorator that was registered - which is exactly why the
        // broadcaster keys its map on session id rather than holding the objects in a set.
        broadcaster.deregister(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // The stream is one-way. A client with something to say uses POST /api/analyze, which
        // is a request with a response and an error contract; inventing a second command
        // channel here would be a second API with neither. Logged rather than ignored so a
        // frontend sending into the void finds out from the server log.
        log.debug("Ignoring {} bytes sent by session {} - the incident stream is read-only",
                message.getPayloadLength(), session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error on session {}, dropping it: {}", session.getId(), exception.toString());
        broadcaster.deregister(session);
    }

    /**
     * The most recent incidents, oldest-first.
     * <p>
     * Fails open like everything else on this path: an unreachable Mongo costs the replay, not
     * the connection. A dashboard that opens empty and then fills as incidents arrive is a
     * degraded dashboard; one that cannot connect at all is a broken one.
     */
    private List<IncidentResponse> backlog() {
        if (backlogSize <= 0) {
            return List.of();
        }
        try {
            List<Incident> recent = new ArrayList<>(incidentRepository.findRecent(
                    PageRequest.of(0, backlogSize, Sort.by(Sort.Direction.DESC, "analyzedAt"))));
            // Queried newest-first because that is what "most recent ten" means; sent
            // oldest-first because that is the order a client wants to append in.
            Collections.reverse(recent);
            return recent.stream().map(IncidentResponse::of).toList();
        } catch (RuntimeException e) {
            log.error("Could not read the incident backlog, connecting with a live-only stream", e);
            return List.of();
        }
    }
}
