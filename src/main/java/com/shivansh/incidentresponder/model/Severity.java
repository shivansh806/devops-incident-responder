package com.shivansh.incidentresponder.model;

/**
 * How much damage the incident is doing, judged by blast radius rather than by log level.
 * Ordered least to most severe so natural ordering can be used for sorting and filtering.
 */
public enum Severity {

    /** Recoverable noise. No user impact. */
    LOW,

    /** Partial or intermittent failure. Retries are still succeeding. */
    MEDIUM,

    /** A service is badly degraded, or will fail imminently if untreated. */
    HIGH,

    /** Users are failing right now, or data is being lost or corrupted. */
    CRITICAL
}
