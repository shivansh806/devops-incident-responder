package com.shivansh.incidentresponder.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The origin allowlist, and the one value of it that is easy to get catastrophically wrong.
 * <p>
 * Offline: nothing here opens a socket. It asserts the array that reaches
 * {@code setAllowedOrigins}, because that array is what decides whether the week 4 container
 * serves a dashboard or refuses every connection to it.
 */
class WebSocketConfigTest {

    private static WebSocketConfig configuredWith(String[] origins) {
        WebSocketConfig config = new WebSocketConfig(null);
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        return config;
    }

    @Test
    void keepsTheConfiguredDevOrigins() {
        WebSocketConfig config = configuredWith(
                new String[] {"http://localhost:5173", "http://localhost:8080"});

        assertThat(config.effectiveOrigins())
                .containsExactly("http://localhost:5173", "http://localhost:8080");
    }

    /**
     * The container case. An empty array is what makes Spring fall back to same-origin, which
     * is the correct - and stricter - rule once the bundle and the API share one process.
     */
    @Test
    void aBlankPropertyYieldsAnEmptyArrayRatherThanOneBlankOrigin() {
        assertThat(configuredWith(new String[] {""}).effectiveOrigins()).isEmpty();
        assertThat(configuredWith(new String[] {"   "}).effectiveOrigins()).isEmpty();
        assertThat(configuredWith(new String[0]).effectiveOrigins()).isEmpty();
        assertThat(configuredWith(null).effectiveOrigins()).isEmpty();
    }

    /**
     * The failure this guard exists for, stated as the thing that must not happen.
     * <p>
     * A one-element array holding {@code ""} is a <b>non-empty</b> allowlist containing an
     * origin no browser ever sends. Spring would not fall back to same-origin; it would compare
     * every incoming Origin against {@code ""} and refuse all of them - including the
     * same-origin connection the container topology depends on. The dashboard would load and
     * then fail to open its socket, which reads as a broken backend.
     */
    @Test
    void neverEmitsABlankOriginBecauseThatWouldRefuseEveryConnection() {
        WebSocketConfig config = configuredWith(
                new String[] {"http://localhost:5173", "", "  ", "http://localhost:8080"});

        assertThat(config.effectiveOrigins())
                .doesNotContain("", "  ")
                .containsExactly("http://localhost:5173", "http://localhost:8080");
    }

    @Test
    void trimsWhitespaceLeftBySplittingACommaSeparatedProperty() {
        WebSocketConfig config = configuredWith(
                new String[] {" http://localhost:5173 ", "\thttp://localhost:8080"});

        assertThat(config.effectiveOrigins())
                .containsExactly("http://localhost:5173", "http://localhost:8080");
    }
}
