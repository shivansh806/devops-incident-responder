package com.shivansh.incidentresponder.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;
import java.util.Objects;

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
 *
 * <h2>An empty list is the correct setting when everything is one origin</h2>
 * In the container topology (week 4 step 3) the bundle and the API are served by this same
 * process, so the browser loads {@code http://localhost:8080} and opens a socket back to
 * {@code http://localhost:8080}. Origin equals target. Spring's rule when <b>no</b> origins are
 * configured is same-origin only, which is exactly right there and <b>stricter</b> than any
 * list this class could carry - so the container sets
 * {@code INCIDENT_WEBSOCKET_ALLOWED_ORIGINS=} and lets the default take over.
 * <p>
 * Listing {@code http://localhost:8080} explicitly would be <b>worse than empty</b>, not
 * equivalent: it breaks the moment the app is reached as {@code 127.0.0.1:8080} or over a LAN
 * address, where the same-origin default simply follows whatever host served the page. And
 * leaving the dev ports in a shipped image is the "dead origin config that outlives its reason
 * and gets widened by someone who cannot tell what it is for" failure that {@code
 * addCorsMappings} was rejected for in docs/frontend.md.
 * <p>
 * <b>Hence {@link #effectiveOrigins()}.</b> "Empty" has to survive a trip through an environment
 * variable, and a blank value that converted to {@code [""]} rather than {@code []} would be a
 * <em>non-empty</em> list containing an origin no browser ever sends - so every connection
 * would be refused, <em>including the same-origin one</em>, and the dashboard would fail
 * silently in exactly the topology this is meant to serve. Blanks are filtered rather than
 * trusted, so the empty case is reached by construction instead of by conversion behaviour.
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
        registry.addHandler(incidentWebSocketHandler, path).setAllowedOrigins(effectiveOrigins());
    }

    /**
     * The configured origins with blanks removed - so a blank or empty property yields a
     * genuinely empty array and Spring falls back to same-origin, rather than a one-element
     * array containing {@code ""} that would refuse every connection. See the class javadoc.
     */
    String[] effectiveOrigins() {
        if (allowedOrigins == null) {
            return new String[0];
        }
        return Arrays.stream(allowedOrigins)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }
}
