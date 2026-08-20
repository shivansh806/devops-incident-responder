package com.shivansh.incidentresponder.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.IncidentResponse;
import com.shivansh.incidentresponder.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What goes on the wire, and what a broken client costs.
 * <p>
 * Offline: no broker, no Groq, no Mongo, no real socket.
 */
class IncidentBroadcasterTest {

    /**
     * Carries a non-null embedding on purpose. A null one would make
     * {@link #neverPutsTheEmbeddingOnTheWire} pass for the wrong reason - the same trap
     * {@code IncidentControllerTest} guards on the REST side.
     */
    private static final Incident INCIDENT = new Incident(
            "651f2c9a4b1d3e0001a2b3c4",
            ErrorType.CONNECTION_POOL_EXHAUSTED,
            "payment-service",
            Severity.CRITICAL,
            Instant.parse("2026-08-20T02:14:33Z"),
            List.of("HikariPool-1 - Connection is not available, request timed out after 30000ms"),
            0.87,
            null,
            new AgentResolution(
                    "Acquisition time rose while execution held flat",
                    "Connections were held across a slow outbound gateway call",
                    List.of("Move the gateway call outside the transaction"),
                    List.of("INC-2103"),
                    0.81),
            Instant.parse("2026-08-20T02:15:00Z"),
            List.of(0.11, -0.42, 0.87));

    /**
     * The mapper Spring Boot injects into the broadcaster, taken from a throwaway context
     * running its autoconfiguration - not one built here to resemble it.
     * <p>
     * <b>That distinction is not fussiness, it is this test's own bug report.</b> The first
     * version built one with {@code Jackson2ObjectMapperBuilder.json().build()} and failed on
     * precisely the field {@link IncidentBroadcaster}'s javadoc warns about:
     * {@code WRITE_DATES_AS_TIMESTAMPS} is disabled by Spring <em>Boot</em>, not by the
     * builder, so the stand-in wrote {@code analyzedAt} as {@code 1.7871921E9} while the
     * application wrote an ISO string. The production code was right and the replica was
     * wrong - which is exactly the drift that sharing one mapper exists to prevent, reproduced
     * by accident in the test written to guard against it.
     */
    private static final ObjectMapper BOOT_OBJECT_MAPPER = bootObjectMapper();

    private final ObjectMapper objectMapper = BOOT_OBJECT_MAPPER;

    private static ObjectMapper bootObjectMapper() {
        AtomicReference<ObjectMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(ObjectMapper.class)));
        return mapper.get();
    }

    private IncidentBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new IncidentBroadcaster(objectMapper);
    }

    @Test
    void pushesALiveIncidentToEveryConnectedClient() throws Exception {
        RecordingSession first = connect("session-1");
        RecordingSession second = connect("session-2");

        broadcaster.broadcast(INCIDENT);

        assertThat(first.frames()).hasSize(1);
        assertThat(second.frames()).hasSize(1);
        assertThat(objectMapper.readTree(first.frames().getFirst()).get("type").asText())
                .isEqualTo(IncidentFrame.INCIDENT);
    }

    @Test
    void sendsExactlyTheShapeTheRestEndpointReturns() throws Exception {
        RecordingSession client = connect("session-1");

        broadcaster.broadcast(INCIDENT);

        // Not "looks similar" - identical. The whole justification for the envelope carrying an
        // IncidentResponse rather than a bespoke push type is that the frontend learns one
        // object. Any field this drifts on is a second contract nobody decided to create.
        JsonNode payload = objectMapper.readTree(client.frames().getFirst()).get("incident");
        assertThat(payload).isEqualTo(objectMapper.valueToTree(IncidentResponse.of(INCIDENT)));
    }

    @Test
    void neverPutsTheEmbeddingOnTheWire() throws Exception {
        RecordingSession client = connect("session-1");

        broadcaster.broadcast(INCIDENT);

        assertThat(client.frames().getFirst()).doesNotContain("embedding");
    }

    @Test
    void writesInstantsAsIsoStringsRatherThanEpochNumbers() throws Exception {
        RecordingSession client = connect("session-1");

        broadcaster.broadcast(INCIDENT);

        // The single field most likely to make the push and the REST contract disagree without
        // anyone noticing: at Jackson's own default an Instant serialises as a number, and the
        // dashboard would need two date parsers for one type.
        JsonNode payload = objectMapper.readTree(client.frames().getFirst()).get("incident");
        assertThat(payload.get("analyzedAt").asText()).isEqualTo("2026-08-20T02:15:00Z");
    }

    @Test
    void sendsANullResolutionAsAPresentNullSoTheClientCanReadIt() throws Exception {
        RecordingSession client = connect("session-1");
        Incident unresolved = withoutResolution(INCIDENT);

        broadcaster.broadcast(unresolved);

        // Present-and-null, not absent. On a live frame this is the Resolver having tried and
        // failed, and a client should not have to tell "the field is null" apart from "the
        // field is missing" to find that out. IncidentFrame's own nulls are suppressed; the
        // payload's are not, and that asymmetry is the point.
        JsonNode payload = objectMapper.readTree(client.frames().getFirst()).get("incident");
        assertThat(payload.has("resolution")).isTrue();
        assertThat(payload.get("resolution").isNull()).isTrue();
    }

    @Test
    void dropsAClientWhoseSendFailedAndStillReachesTheOthers() throws Exception {
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.getId()).thenReturn("session-broken");
        willThrow(new IOException("broken pipe")).given(broken).sendMessage(any(TextMessage.class));
        broadcaster.register(broken);
        RecordingSession healthy = connect("session-healthy");

        broadcaster.broadcast(INCIDENT);

        assertThat(healthy.frames()).hasSize(1);
        // Dropped rather than retried. A socket that cannot be written to now will not recover
        // on its own, and keeping it means paying this failure once per incident forever.
        assertThat(broadcaster.connectionCount()).isEqualTo(1);
        verify(broken).close();
    }

    @Test
    void aFailedSendNeverReachesThePipeline() {
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.getId()).thenReturn("session-broken");
        broadcaster.register(broken);

        // Two Groq calls have been billed and the incident is already in Mongo by the time this
        // runs. A closed browser tab must not be able to undo that.
        assertThatCode(() -> {
            willThrow(new IOException("broken pipe")).given(broken).sendMessage(any(TextMessage.class));
            broadcaster.broadcast(INCIDENT);
        }).doesNotThrowAnyException();
    }

    @Test
    void aRuntimeFailureInsideASendIsAbsorbedToo() throws Exception {
        WebSocketSession hostile = mock(WebSocketSession.class);
        when(hostile.getId()).thenReturn("session-hostile");
        willThrow(new IllegalStateException("session closed")).given(hostile).sendMessage(any(TextMessage.class));
        broadcaster.register(hostile);

        assertThatCode(() -> broadcaster.broadcast(INCIDENT)).doesNotThrowAnyException();
        assertThat(broadcaster.connectionCount()).isZero();
    }

    @Test
    void doesNothingWhenNobodyIsConnected() {
        assertThatCode(() -> broadcaster.broadcast(INCIDENT)).doesNotThrowAnyException();
        assertThat(broadcaster.connectionCount()).isZero();
    }

    @Test
    void deregistersADecoratedSessionGivenTheRawOneSpringHandsBackOnClose() throws Exception {
        WebSocketSession raw = mock(WebSocketSession.class);
        when(raw.getId()).thenReturn("session-1");
        when(raw.isOpen()).thenReturn(true);
        // What the handler actually registers. The decorator does not equal its delegate, so a
        // Set<WebSocketSession> would never remove anything here and every closed tab would
        // leak a session, one slow send at a time, until the first broadcast noticed.
        broadcaster.register(new ConcurrentWebSocketSessionDecorator(raw, 5_000, 512 * 1024));

        broadcaster.deregister(raw);

        assertThat(broadcaster.connectionCount()).isZero();
        broadcaster.broadcast(INCIDENT);
        verify(raw, never()).sendMessage(any(TextMessage.class));
    }

    private RecordingSession connect(String id) throws Exception {
        RecordingSession session = new RecordingSession(id);
        broadcaster.register(session.session());
        return session;
    }

    private static Incident withoutResolution(Incident incident) {
        return new Incident(incident.id(), incident.errorType(), incident.affectedService(),
                incident.severity(), incident.firstOccurrence(), incident.keyEvidence(),
                incident.confidence(), incident.resolutionNotes(), null, incident.analyzedAt(),
                incident.embedding());
    }

    /** A mock session that keeps every payload written to it. */
    private record RecordingSession(WebSocketSession session, List<String> frames) {

        RecordingSession(String id) throws Exception {
            this(mock(WebSocketSession.class), new ArrayList<>());
            when(session.getId()).thenReturn(id);
            List<String> captured = frames;
            willAnswer(call -> captured.add(((TextMessage) call.getArgument(0)).getPayload()))
                    .given(session).sendMessage(any(TextMessage.class));
        }
    }
}
