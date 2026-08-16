package com.shivansh.incidentresponder.simulator;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Fills a template file in from {@code simulator/} with the details of one simulated incident.
 * <p>
 * The point of the substitution is that two events of the same failure class must not be the
 * same log. Identical text would embed to an identical vector, and the retrieval corpus would
 * fill with near-duplicates of the simulator's own output - so services, hosts, request ids,
 * timestamps and every metric number vary per event.
 *
 * <h2>Tokens</h2>
 * <table>
 *   <tr><td>{@code {svc}}</td><td>the service the failure originates in</td></tr>
 *   <tr><td>{@code {caller}}</td><td>a service on the receiving end</td></tr>
 *   <tr><td>{@code {host}}</td><td>pod-style host id, constant within one event</td></tr>
 *   <tr><td>{@code {id}}</td><td>8 hex characters, <b>fresh at every occurrence</b> - request ids</td></tr>
 *   <tr><td>{@code {t+90}}, {@code {t-3600}}</td><td>base time shifted by seconds, ISO-8601 UTC</td></tr>
 *   <tr><td>{@code {t+7|+05:30}}</td><td>the same instant written at that offset, not UTC</td></tr>
 *   <tr><td>{@code {#40-120}}</td><td>random integer in range, fresh at every occurrence</td></tr>
 *   <tr><td>{@code {=order:100000-999999}}</td><td>random integer, but <b>the same value everywhere the
 *       name appears</b> in one event</td></tr>
 * </table>
 * The last two are the difference between a log that reads as real and one that does not. A GC
 * pause should differ line to line; the export job id in "started", "loaded" and "failed" is
 * one job and must not.
 * <p>
 * The offset form exists for exactly one template: {@code disk-space-exhausted.log} emits
 * {@code +05:30} timestamps, so the Analyzer has to convert to UTC to report
 * {@code firstOccurrence}. That is the trap {@code docs/baseline.md} records as sample 4.
 * <p>
 * The pattern matches only these shapes, which is what lets
 * {@code expired-certificate.log} be JSON-structured without its own braces being eaten.
 */
final class IncidentLogRenderer {

    private static final Pattern TOKEN = Pattern.compile(
            "\\{(?:(?<plain>svc|caller|host|id)"
                    + "|t(?<shift>[+-]\\d+)(?:\\|(?<zone>[+-]\\d{2}:\\d{2}))?"
                    + "|#(?<rmin>\\d+)-(?<rmax>\\d+)"
                    + "|=(?<name>[a-z]+):(?<smin>\\d+)-(?<smax>\\d+))}");

    private static final String HEX = "0123456789abcdef";

    private IncidentLogRenderer() {
    }

    /**
     * @param base    the instant {@code {t+0}} resolves to. Everything else is relative to it,
     *                so one value shifts the whole log.
     * @param service value for {@code {svc}}
     * @param caller  value for {@code {caller}}
     * @param random  supplied rather than created, so a test can render deterministically
     */
    static String render(String template, Instant base, String service, String caller, RandomGenerator random) {
        Instant anchor = base.truncatedTo(ChronoUnit.MILLIS);
        String host = "%s-%s-%s".formatted(service, hex(random, 6), hex(random, 4));

        // Values that must repeat within this event. Populated on first use of each name.
        Map<String, String> stable = new HashMap<>();

        return TOKEN.matcher(template).replaceAll(match -> switch (kind(match)) {
            case "svc" -> service;
            case "caller" -> caller;
            case "host" -> host;
            case "id" -> hex(random, 8);
            case "shift" -> timestamp(anchor, Long.parseLong(match.group("shift")), match.group("zone"));
            case "range" -> String.valueOf(between(random, match.group("rmin"), match.group("rmax")));
            case "stable" -> stable.computeIfAbsent(match.group("name"),
                    ignored -> String.valueOf(between(random, match.group("smin"), match.group("smax"))));
            default -> match.group();
        });
    }

    private static String kind(MatchResult match) {
        if (match.group("plain") != null) {
            return match.group("plain");
        }
        if (match.group("shift") != null) {
            return "shift";
        }
        if (match.group("rmin") != null) {
            return "range";
        }
        return "stable";
    }

    /** Rendered at {@code zone} when one is given, otherwise as a UTC instant. */
    private static String timestamp(Instant anchor, long shiftSeconds, String zone) {
        Instant at = anchor.plusSeconds(shiftSeconds);
        if (zone == null) {
            return DateTimeFormatter.ISO_INSTANT.format(at);
        }
        return OffsetDateTime.ofInstant(at, ZoneOffset.of(zone))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static int between(RandomGenerator random, String min, String max) {
        int lo = Integer.parseInt(min);
        int hi = Integer.parseInt(max);
        return lo >= hi ? lo : random.nextInt(lo, hi + 1);
    }

    private static String hex(RandomGenerator random, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(HEX.charAt(random.nextInt(HEX.length())));
        }
        return out.toString();
    }
}
