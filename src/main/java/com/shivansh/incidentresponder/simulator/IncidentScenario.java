package com.shivansh.incidentresponder.simulator;

import java.util.List;

/**
 * The failure classes the simulator can produce, one per template file under
 * {@code src/main/resources/simulator/}.
 * <p>
 * <b>No expected answer is recorded here, and none appears in the template files.</b> That is
 * the same rule {@code docs/baseline.md} sets for the sample logs: anything inside a log goes
 * into the prompt, so a hint would make the demo a performance rather than a demonstration.
 * The constant names describe what was <em>simulated</em>, not what the Analyzer is supposed
 * to answer - it may reasonably disagree, and that is a result rather than a bug.
 * <p>
 * <b>Why each scenario has a pool of services.</b> Repeating one service name would make every
 * event of a given class embed to nearly the same vector, and retrieval would start returning
 * the simulator's own previous events as precedent. Varying the service keeps the corpus from
 * collapsing onto itself.
 *
 * @param resource        template file name, relative to {@code simulator/} on the classpath
 * @param services        candidate values for {@code {svc}} - the service the failure
 *                        originates in
 * @param callers         candidate values for {@code {caller}} - a service on the receiving
 *                        end. Never the origin.
 * @param declaresService whether the emitted event carries a {@code service} field.
 *                        <p>
 *                        False for the two scenarios whose logs are tagged with the
 *                        <em>victim</em> rather than the origin. Setting {@code service} there
 *                        would assert the wrong answer and override the model - the
 *                        origin-versus-reporter judgement is precisely what those templates
 *                        exist to exercise, and a producer that is not sure should say
 *                        nothing. See {@code IncidentEvent.service}.
 */
public enum IncidentScenario {

    CONNECTION_POOL_EXHAUSTION(
            "connection-pool-exhausted.log",
            List.of("payment-service", "order-service", "billing-service", "subscription-service"),
            List.of("checkout-service", "web-bff", "mobile-bff"),
            true),

    OUT_OF_MEMORY(
            "out-of-memory.log",
            List.of("report-service", "export-service", "analytics-service"),
            List.of("admin-portal", "scheduler"),
            true),

    /** Logs are tagged with the caller throughout; the origin is the upstream it calls. */
    UPSTREAM_TIMEOUT(
            "upstream-timeout.log",
            List.of("inventory-service", "stock-service", "pricing-service"),
            List.of("checkout-service", "order-service", "cart-service"),
            false),

    DISK_SPACE_EXHAUSTED(
            "disk-space-exhausted.log",
            List.of("media-service", "upload-service", "archive-service"),
            List.of("web-bff", "studio-portal"),
            true),

    /** A 401 flood tagged with the gateway; the cause is two lines deep in the auth service. */
    EXPIRED_CERTIFICATE(
            "expired-certificate.log",
            List.of("auth-service", "identity-service"),
            List.of("api-gateway", "edge-proxy"),
            false),

    THREAD_DEADLOCK(
            "thread-deadlock.log",
            List.of("pricing-service", "rating-service", "quote-service"),
            List.of("catalog-service", "web-bff"),
            true),

    SLOW_QUERY(
            "slow-query.log",
            List.of("catalog-service", "search-service", "product-service"),
            List.of("web-bff", "mobile-bff"),
            true),

    CACHE_UNAVAILABLE(
            "cache-unavailable.log",
            List.of("profile-service", "session-service", "preferences-service"),
            List.of("api-gateway", "web-bff"),
            true);

    private final String resource;
    private final List<String> services;
    private final List<String> callers;
    private final boolean declaresService;

    IncidentScenario(String resource, List<String> services, List<String> callers, boolean declaresService) {
        this.resource = resource;
        this.services = services;
        this.callers = callers;
        this.declaresService = declaresService;
    }

    public String resource() {
        return resource;
    }

    public List<String> services() {
        return services;
    }

    public List<String> callers() {
        return callers;
    }

    public boolean declaresService() {
        return declaresService;
    }
}
