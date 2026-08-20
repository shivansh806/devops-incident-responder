package com.shivansh.incidentresponder.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The whole server side of the stream, driven by a real WebSocket client over a real handshake.
 * <p>
 * This is the only test that exercises the parts no unit test reaches: that
 * {@code WebSocketConfig} actually puts the handler on the configured path, that the handshake
 * succeeds, and - the one that matters most - that a frame's payload is byte-identical to what
 * the REST endpoint returns for the same document. Two configurations can each be correct and
 * still disagree about a date format, which is exactly the class of mismatch that would surface
 * as a broken dashboard in week 4 rather than as a failing build. The same reasoning as
 * {@code SimulatedEventWireFormatTest}.
 * <p>
 * Offline. Mongo is mocked, the Kafka listener is held by the test properties, and nothing here
 * calls Groq - the broadcast is triggered directly rather than through the pipeline, because
 * putting a real analysis behind it would cost 9,800 tokens to test a socket.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "langchain4j.open-ai.chat-model.api-key=test-key",
                "incident.websocket.backlog=2"
        })
class IncidentStreamTest {

    private static final String LIVE_ID = "651f2c9a4b1d3e0001a2b3c4";

    @LocalServerPort
    private int port;

    @Autowired
    private IncidentBroadcaster broadcaster;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private IncidentRepository incidentRepository;

    @Test
    void greetsAConnectingClientAndReplaysTheBacklogAsHistory() throws Exception {
        given(incidentRepository.findRecent(any(Pageable.class)))
                .willReturn(List.of(seeded("INC-2103"), seeded("INC-2041")));

        try (CollectingClient client = connect()) {
            assertThat(client.next().get("type").asText()).isEqualTo(IncidentFrame.CONNECTED);
            assertThat(client.next().get("incident").get("id").asText()).isEqualTo("INC-2041");
            assertThat(client.next().get("incident").get("id").asText()).isEqualTo("INC-2103");
        }
    }

    @Test
    void pushesALiveIncidentToAClientThatConnectedBeforeIt() throws Exception {
        given(incidentRepository.findRecent(any(Pageable.class))).willReturn(List.of());

        try (CollectingClient client = connect()) {
            assertThat(client.next().get("type").asText()).isEqualTo(IncidentFrame.CONNECTED);

            broadcaster.broadcast(live());

            JsonNode frame = client.next();
            assertThat(frame.get("type").asText()).isEqualTo(IncidentFrame.INCIDENT);
            assertThat(frame.get("incident").get("id").asText()).isEqualTo(LIVE_ID);
        }
    }

    @Test
    void aFramesPayloadIsIdenticalToWhatTheRestEndpointReturns() throws Exception {
        given(incidentRepository.findRecent(any(Pageable.class))).willReturn(List.of());
        given(incidentRepository.findById(LIVE_ID)).willReturn(Optional.of(live()));

        try (CollectingClient client = connect()) {
            client.next();
            broadcaster.broadcast(live());
            JsonNode pushed = client.next().get("incident");

            String body = restTemplate.getForObject("/api/incidents/" + LIVE_ID, String.class);

            // Neither side could establish this alone. One contract for the frontend to learn is
            // the entire reason the frame carries an IncidentResponse rather than a push-shaped
            // type of its own, and it is only true if these two are the same bytes.
            assertThat(pushed).isEqualTo(objectMapper.readTree(body));
        }
    }

    @Test
    void aClientThatDisconnectsStopsCountingAsConnected() throws Exception {
        given(incidentRepository.findRecent(any(Pageable.class))).willReturn(List.of());

        CollectingClient client = connect();
        client.next();
        assertThat(broadcaster.connectionCount()).isEqualTo(1);

        client.close();

        // Polled rather than asserted immediately: the close travels back over the network and
        // afterConnectionClosed runs on the server's own thread.
        long deadline = System.currentTimeMillis() + 5_000;
        while (broadcaster.connectionCount() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(broadcaster.connectionCount()).isZero();
    }

    private CollectingClient connect() throws Exception {
        CollectingClient client = new CollectingClient(objectMapper);
        client.session = new StandardWebSocketClient()
                .execute(client, "ws://localhost:" + port + "/ws/incidents")
                .get(10, TimeUnit.SECONDS);
        return client;
    }

    /** A live incident: the Resolver ran and produced something. */
    private static Incident live() {
        return new Incident(LIVE_ID, ErrorType.CONNECTION_POOL_EXHAUSTED, "payment-service",
                Severity.CRITICAL, Instant.parse("2026-08-20T02:14:33Z"),
                List.of("HikariPool-1 - Connection is not available"), 0.87, null,
                new AgentResolution("Acquisition time rose while execution held flat",
                        "Connections were held across a slow outbound gateway call",
                        List.of("Move the gateway call outside the transaction"),
                        List.of("INC-2103"), 0.81),
                Instant.parse("2026-08-20T02:15:00Z"),
                // Present, and must not reach either transport.
                List.of(0.11, -0.42, 0.87));
    }

    /** A seeded incident: human write-up, no Resolver output. */
    private static Incident seeded(String id) {
        return new Incident(id, ErrorType.CACHE_UNAVAILABLE, "product-service", Severity.HIGH,
                Instant.parse("2026-02-17T09:12:44Z"), List.of("redis: connection refused"), 0.91,
                "Reconfigured the client to connect through the sentinel set", null,
                Instant.parse("2026-02-17T09:31:10Z"), null);
    }

    /** Collects frames off the socket so a test can wait for the next one. */
    private static final class CollectingClient extends TextWebSocketHandler implements AutoCloseable {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final ObjectMapper objectMapper;
        private WebSocketSession session;

        private CollectingClient(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }

        JsonNode next() throws Exception {
            String frame = received.poll(10, TimeUnit.SECONDS);
            assertThat(frame).as("expected a frame within 10s").isNotNull();
            return objectMapper.readTree(frame);
        }

        @Override
        public void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }
}
