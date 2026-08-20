package com.shivansh.incidentresponder.websocket;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * What happens to a client that connects mid-stream.
 * <p>
 * Offline: the repository and the broadcaster are both mocked, so no Mongo and no socket.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentWebSocketHandlerTest {

    @Mock
    private IncidentBroadcaster broadcaster;

    @Mock
    private IncidentRepository incidentRepository;

    @Captor
    private ArgumentCaptor<IncidentFrame> frames;

    @Captor
    private ArgumentCaptor<Pageable> pageRequest;

    @Test
    void registersTheSessionBeforeReadingTheBacklog() {
        given(incidentRepository.findRecent(any(Pageable.class))).willReturn(List.of(incident("INC-2041")));

        handler(10).afterConnectionEstablished(session("session-1"));

        // THE test in this class. An incident that finishes between these two calls reaches the
        // client twice in this order and not at all in the other, and every frame carries an id
        // so a duplicate is a replaced row while a gap is invisible and unrecoverable.
        InOrder order = inOrder(broadcaster, incidentRepository);
        order.verify(broadcaster).register(any(WebSocketSession.class));
        order.verify(incidentRepository).findRecent(any(Pageable.class));
    }

    @Test
    void greetsWithAConnectedFrameStatingHowMuchHistoryFollows() {
        given(incidentRepository.findRecent(any(Pageable.class)))
                .willReturn(List.of(incident("INC-2041"), incident("INC-2103")));

        handler(10).afterConnectionEstablished(session("session-1"));

        then(broadcaster).should(times(3))
                .send(frames.capture(), any(WebSocketSession.class));
        // At ~10 events a day a silent socket is indistinguishable from a dead one, so a client
        // is told the channel works even when there is nothing at all to show.
        assertThat(frames.getAllValues().getFirst().type()).isEqualTo(IncidentFrame.CONNECTED);
        assertThat(frames.getAllValues().getFirst().backlog()).isEqualTo(2);
        assertThat(frames.getAllValues().getFirst().incident()).isNull();
    }

    @Test
    void replaysTheBacklogAsHistoryFramesOldestFirst() {
        // Returned newest-first, which is what "the most recent two" means to the query.
        given(incidentRepository.findRecent(any(Pageable.class)))
                .willReturn(List.of(incident("newest"), incident("oldest")));

        handler(10).afterConnectionEstablished(session("session-1"));

        then(broadcaster).should(times(3))
                .send(frames.capture(), any(WebSocketSession.class));
        List<IncidentFrame> history = frames.getAllValues().subList(1, 3);
        // Sent oldest-first so a client appending naively still ends with the newest at the
        // bottom. It must not RELY on that - see the ordering note on the handler - but there
        // is no reason to hand it the harder case for free.
        assertThat(history).extracting(IncidentFrame::type)
                .containsOnly(IncidentFrame.HISTORY);
        assertThat(history).extracting(frame -> frame.incident().id())
                .containsExactly("oldest", "newest");
    }

    @Test
    void tagsReplayedIncidentsAsHistorySoANullResolutionIsNotReadAsAFailure() {
        // Exactly a seeded incident: written up by a human, never seen by the Resolver. On a
        // database with no live traffic the whole backlog looks like this, and drawing them as
        // failed resolutions would say the system gave up on incidents that were fixed months
        // ago.
        given(incidentRepository.findRecent(any(Pageable.class))).willReturn(List.of(incident("INC-2041")));

        handler(10).afterConnectionEstablished(session("session-1"));

        then(broadcaster).should(times(2))
                .send(frames.capture(), any(WebSocketSession.class));
        IncidentFrame replayed = frames.getAllValues().get(1);
        assertThat(replayed.type()).isEqualTo(IncidentFrame.HISTORY);
        assertThat(replayed.incident().resolution()).isNull();
    }

    @Test
    void asksForTheMostRecentlyAnalysedIncidentsUpToTheConfiguredSize() {
        given(incidentRepository.findRecent(any(Pageable.class))).willReturn(List.of());

        handler(4).afterConnectionEstablished(session("session-1"));

        then(incidentRepository).should().findRecent(pageRequest.capture());
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(4);
        // analyzedAt, not firstOccurrence. The latter is read out of the logs by the model, can
        // be null and can be wrong; this one comes off the server clock.
        assertThat(pageRequest.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "analyzedAt"));
    }

    @Test
    void aBacklogOfZeroIsALiveOnlyStreamAndDoesNotTouchMongoAtAll() {
        handler(0).afterConnectionEstablished(session("session-1"));

        then(incidentRepository).should(never()).findRecent(any(Pageable.class));
        then(broadcaster).should().send(frames.capture(), any(WebSocketSession.class));
        assertThat(frames.getValue().type()).isEqualTo(IncidentFrame.CONNECTED);
        assertThat(frames.getValue().backlog()).isZero();
    }

    @Test
    void connectsWithALiveOnlyStreamWhenMongoCannotBeReached() {
        given(incidentRepository.findRecent(any(Pageable.class)))
                .willThrow(new IllegalStateException("no Mongo"));

        assertThatCode(() -> handler(10).afterConnectionEstablished(session("session-1")))
                .doesNotThrowAnyException();

        // A dashboard that opens empty and fills as incidents arrive is degraded. One that
        // cannot connect at all is broken. The session is registered before the query, so it is
        // already live by the time this fails.
        then(broadcaster).should().register(any(WebSocketSession.class));
        then(broadcaster).should().send(frames.capture(), any(WebSocketSession.class));
        assertThat(frames.getValue().backlog()).isZero();
    }

    @Test
    void deregistersOnClose() {
        WebSocketSession session = session("session-1");

        handler(10).afterConnectionClosed(session, CloseStatus.NORMAL);

        then(broadcaster).should().deregister(session);
    }

    @Test
    void deregistersOnTransportError() {
        WebSocketSession session = session("session-1");

        handler(10).handleTransportError(session, new java.io.IOException("reset"));

        then(broadcaster).should().deregister(session);
    }

    private IncidentWebSocketHandler handler(int backlog) {
        return new IncidentWebSocketHandler(broadcaster, incidentRepository, backlog);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    /** A seeded-shaped incident: human notes, no Resolver output, no embedding. */
    private static Incident incident(String id) {
        return new Incident(id, ErrorType.CACHE_UNAVAILABLE, "product-service", Severity.HIGH,
                Instant.parse("2026-02-17T09:12:44Z"), List.of("redis: connection refused"), 0.91,
                "Reconfigured the client to connect through the sentinel set", null,
                Instant.parse("2026-02-17T09:31:10Z"), null);
    }
}
