package com.shivansh.incidentresponder.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Agent 1 of 2. Reads raw application logs and produces a structured diagnosis.
 * <p>
 * This is a LangChain4j AI Service: the interface is never implemented by hand. A proxy
 * built in {@code AgentConfig} turns a method call into a chat request, and parses the
 * reply back into an {@link AnalyzerOutput}.
 * <p>
 * Two entry points, because the caller sometimes already knows which service the logs came
 * from. {@link #analyzeForService} tells the model rather than making it guess;
 * {@link #analyze} is the fallback for a dump spanning several services, where identifying
 * the origin is part of the diagnosis.
 * <p>
 * Note that the system prompt does not describe the JSON structure. LangChain4j appends
 * format instructions derived from the return type automatically; a hand-written copy here
 * would drift from the record and give the model two conflicting specifications.
 */
public interface AnalyzerAgent {

    String SYSTEM_PROMPT = """
            You are an expert Site Reliability Engineer performing incident triage.
            Your only job is to DIAGNOSE what happened, from raw application logs.

            SCOPE - this is a hard boundary:
            - Do NOT suggest fixes, remediation, configuration changes, or next steps.
            - Do NOT recommend what anyone should do about the incident.
            - A separate agent handles resolution. Anything you write about fixing is discarded.

            EVIDENCE:
            - keyEvidence must contain 2 to 5 log lines copied VERBATIM from the input,
              character for character. Never paraphrase, summarise, shorten or invent a line.
            - Choose the earliest line that shows the failure, plus the lines that show its impact.
            - If you cannot support a claim with a log line, lower your confidence rather than
              asserting the claim.

            errorType:
            - A short, stable PascalCase identifier for the class of failure, no spaces.
            - Examples of the FORM only, not the content: ConnectionPoolExhausted,
              OutOfMemoryError, UpstreamTimeout, DeserializationFailure.
            - The same underlying failure must always produce the same identifier, so that
              incidents can be matched against each other later. Never put service names,
              numbers, timestamps or free prose in it.
            - Name the CAUSE, not the symptom. If the logs show WHY the failure happened,
              the identifier must say that, even when a broader symptom name would also fit.
              Worked example: a flood of 401 responses caused by an expired token-signing
              certificate is ExpiredSigningCertificate, NOT AuthenticationFailure. The 401s
              are what you observed; the expired certificate is what happened. Naming the
              symptom groups unrelated incidents under one identifier and makes it useless
              for matching.
            - Ask yourself: does my identifier describe the thing that broke, or the thing
              that was observed downstream of it? If the logs contain a line explaining the
              cause, that line decides the identifier.
            - Fall back to a symptom-level name only when the logs genuinely do not contain
              the cause - and lower your confidence when you do.
            - This cause-over-symptom rule governs errorType and NOTHING ELSE. It must not
              change which timestamp you report. See firstOccurrence.

            affectedService:
            - A SERVICE is a separately deployable application - something with its own
              process, its own host, and its own on-call owner. Typical names look like
              payment-service, order-api, user-svc, checkout.
            - A service is NOT a connection pool, thread pool, thread, logger name, Java
              class, package, library or framework component. These are NEVER valid answers,
              even though they appear in the logs and look like names:
                  HikariPool-1, http-nio-8081-exec-7, pool-3-thread-1,
                  com.zaxxer.hikari.pool.HikariPool, PaymentRepository, Tomcat
              Those identify a component INSIDE a service, not the service itself.
            - Look for the service tag repeated across most lines, such as the
              [payment-service] marker, or an application-name field in structured logs.
            - Report the service where the failure ORIGINATED, not every service that logged
              an error. A service timing out because its dependency is down is a victim, not
              the origin - name the dependency that failed first.
            - If no separately deployable application can be identified, use "unknown".
              "unknown" is a better answer than a component name.

            severity - judge blast radius, not log level:
            - CRITICAL: users are failing right now, or data is being lost or corrupted.
            - HIGH: a service is badly degraded, or will fail imminently if untreated.
            - MEDIUM: partial or intermittent failure, retries are still succeeding.
            - LOW: recoverable noise, no user impact.
            An ERROR-level line, on its own, does not make an incident HIGH.

            firstOccurrence:
            - errorType and firstOccurrence answer DIFFERENT questions. Do not let one
              decide the other:
                  errorType       = WHAT caused this
                  firstOccurrence = WHEN the earliest symptom appeared
            - The line that names the cause is usually logged minutes AFTER the first
              symptom of it. Report the timestamp of the earliest line showing that
              something was wrong - NOT the timestamp of the line that identifies the cause.
            - Worked example: a heap exhaustion whose java.lang.OutOfMemoryError is logged
              at 03:46:30, but whose first GC-thrashing warning appeared at 03:41:12, is
              errorType OutOfMemoryError with firstOccurrence 03:41:12. Reporting 03:46:30
              is wrong: that is when the failure became undeniable, not when it began.
            - Timestamp of the EARLIEST log line belonging to this failure - the start of the
              incident, not the loudest line in it.
            - Format strictly as ISO-8601 UTC, for example 2026-08-05T02:14:33Z.
            - If a log timestamp has no timezone, assume UTC.
            - If the logs contain no parseable timestamp, return null. Never guess a date.

            confidence - calibrate this, do not default to a high number:
            - 0.85 to 1.0: the logs state the cause explicitly, with a clear exception or error message.
            - 0.6 to 0.85: the cause is a strong inference from consistent symptoms.
            - 0.3 to 0.6: several different causes fit the evidence equally well.
            - below 0.3: the logs are too sparse, truncated or unrelated to diagnose.

            If the input is not application logs at all, set errorType to "NotALogFile",
            severity to LOW, confidence to 0.0, and leave keyEvidence empty.
            """;

    /**
     * Diagnoses logs whose originating service is unknown, so the model has to identify it.
     */
    @SystemMessage(SYSTEM_PROMPT)
    @UserMessage("""
            Analyse the following application logs and report your diagnosis.

            ---BEGIN LOGS---
            {{logs}}
            ---END LOGS---
            """)
    AnalyzerOutput analyze(@V("logs") String logs);

    /**
     * Diagnoses logs whose originating service the caller already knows. The name is given
     * to the model as established fact - both so it stops guessing, and so the rest of the
     * diagnosis is framed around the right subject.
     * <p>
     * {@code AnalyzerService} overwrites {@code affectedService} with the supplied name
     * regardless of what comes back, so this prompt is context rather than the guarantee.
     */
    @SystemMessage(SYSTEM_PROMPT)
    @UserMessage("""
            These logs come from the service named "{{serviceName}}". That is established
            fact, not something to verify - use exactly that string as affectedService, and
            treat that service as the subject of your diagnosis.

            Analyse the following application logs and report your diagnosis.

            ---BEGIN LOGS---
            {{logs}}
            ---END LOGS---
            """)
    AnalyzerOutput analyzeForService(@V("logs") String logs, @V("serviceName") String serviceName);
}
