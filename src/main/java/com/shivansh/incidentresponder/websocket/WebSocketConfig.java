package com.shivansh.incidentresponder.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Puts {@link IncidentWebSocketHandler} on a URL.
 *
 * <h2>Raw WebSocket, not STOMP</h2>
 * {@code spring-boot-starter-websocket} offers both: this API, and a STOMP message broker
 * behind {@code @EnableWebSocketMessageBroker}. STOMP is a second wire protocol layered on the
 * socket - frames, destinations, subscription lifecycle - and using it would mean week 4 ships
 * a STOMP client library and learns that protocol <b>on top of</b> the JSON contract, which is
 * the opposite of the one-contract goal.
 * <p>
 * What STOMP is actually for is routing: many destinations, per-user queues, server-side
 * subscription filtering. This endpoint is one broadcast of one type to every dashboard. There
 * is nothing to route. So the browser's built-in {@code new WebSocket(url)} is the whole client,
 * with no dependency at all.
 * <p>
 * SockJS is declined for the same reason and one more: it exists to emulate WebSocket over
 * XHR-polling on browsers that lack it, and every browser week 4 targets has had it for a
 * decade.
 *
 * <h2>Allowed origins are not CORS</h2>
 * The handshake is an HTTP request but it is <b>not</b> subject to CORS, so the browser will not
 * stop a cross-origin connection - the server has to. Spring's default is to allow same-origin
 * only, which would silently refuse week 4's Vite dev server on port 5173 with a handshake
 * failure that reads like a wrong URL. The dev ports are listed here rather than being opened
 * with a wildcard: {@code setAllowedOrigins("*")} lets any page on the internet open a stream of
 * this system's incident data, and it is the kind of default that survives into production
 * because nothing ever appears to be wrong with it.
 * <p>
 * <b>A non-empty list replaces the same-origin default, it does not extend it.</b> Spring only
 * falls back to a same-origin check when no origins are configured at all, so listing the Vite
 * port silently locks out {@code http://localhost:8080} - this application's own origin, and
 * the one a browser console is on when someone first tries the endpoint by hand. It is listed
 * for that reason. A command-line client sends no {@code Origin} header and is allowed either
 * way, which is why {@code websocat} works while DevTools would not have.
 * <p>
 * Override with {@code incident.websocket.allowed-origins} when the frontend moves.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final IncidentWebSocketHandler incidentWebSocketHandler;

    @Value("${incident.websocket.path:/ws/incidents}")
    private String path;

    @Value("${incident.websocket.allowed-origins:http://localhost:5173,http://localhost:3000,http://localhost:8080}")
    private String[] allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(incidentWebSocketHandler, path).setAllowedOrigins(allowedOrigins);
    }
}
