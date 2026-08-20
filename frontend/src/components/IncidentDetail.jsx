import { errorTypeLabel, formatConfidence, formatInstant, severityStyle } from '../format';

// AgentResolution's public constants. They are wire vocabulary the frontend switches on -
// spelled out here for the same reason they are spelled out in the record: they are read
// in more than one place and a typo would fail silently as prose.
const PRECEDENTS_AGREE = 'PRECEDENTS AGREE';
const NO_RELEVANT_PRECEDENT = 'NO RELEVANT PRECEDENT';
const NOT_STATED = 'NOT STATED';

const DECIDING_EVIDENCE_STATES = {
  [PRECEDENTS_AGREE]: 'The retrieved precedents agreed, so nothing had to be discriminated.',
  [NO_RELEVANT_PRECEDENT]: 'No retrieved incident was relevant enough to decide between.',
  [NOT_STATED]: 'The Resolver did not state what separated the precedents.',
};

function Section({ title, children }) {
  return (
    <section className="border-t border-slate-200 px-6 py-5">
      <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-slate-500">
        {title}
      </h3>
      {children}
    </section>
  );
}

function Field({ label, value }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="mt-0.5 text-slate-900">{value}</dd>
    </div>
  );
}

export default function IncidentDetail({ incident, byId }) {
  if (!incident) {
    return (
      <div className="flex h-full items-center justify-center px-6 py-16">
        <p className="text-slate-500">Select an incident to see the full diagnosis.</p>
      </div>
    );
  }

  const analysis = incident.analysis ?? {};
  const resolution = incident.resolution;

  return (
    <article>
      <header className="px-6 py-5">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-xl font-semibold text-slate-900">
            {errorTypeLabel(analysis.errorType)}
          </h2>
          <span
            className={`rounded px-2 py-0.5 text-sm font-medium ring-1 ring-inset ${severityStyle(
              analysis.severity,
            )}`}
          >
            {analysis.severity ?? '—'}
          </span>
        </div>
        <p className="mt-1 text-slate-600">
          {analysis.affectedService ?? 'unknown service'}
        </p>
        <p className="mt-2 font-mono text-xs text-slate-400">{incident.id}</p>
      </header>

      <Section title="Diagnosis">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-3">
          <Field label="Analyzed at" value={formatInstant(incident.analyzedAt)} />
          {/* firstOccurrence is read out of the logs by the model. It can be null and it
              can be wrong, which is why nothing sorts by it. */}
          <Field label="First occurrence" value={formatInstant(analysis.firstOccurrence)} />
          <Field label="Analyzer confidence" value={formatConfidence(analysis.confidence)} />
        </dl>
      </Section>

      <Section title="Key evidence">
        {analysis.keyEvidence?.length ? (
          <ul className="space-y-2">
            {analysis.keyEvidence.map((line, i) => (
              <li
                key={i}
                className="rounded border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-sm text-slate-800"
              >
                {line}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-slate-500">None recorded.</p>
        )}
      </Section>

      {resolution ? (
        <>
          <Section title="Root cause">
            <p className="text-slate-900">{resolution.rootCause || '—'}</p>
            <p className="mt-2 text-sm text-slate-500">
              Resolver confidence {formatConfidence(resolution.confidence)}
            </p>
          </Section>

          <Section title="Deciding evidence">
            {/* The field the Resolver emits first, on purpose: it is what separated the
                retrieved precedents from each other. Three of its values are states
                rather than prose and are labelled as such. */}
            {DECIDING_EVIDENCE_STATES[resolution.decidingEvidence] ? (
              <p className="text-slate-600">
                <span className="font-medium text-slate-900">
                  {resolution.decidingEvidence}
                </span>{' '}
                — {DECIDING_EVIDENCE_STATES[resolution.decidingEvidence]}
              </p>
            ) : (
              <p className="text-slate-900">{resolution.decidingEvidence || '—'}</p>
            )}
          </Section>

          <Section title="Suggested actions">
            {resolution.suggestedActions?.length ? (
              <ol className="space-y-2">
                {resolution.suggestedActions.map((action, i) => (
                  <li key={i} className="flex gap-3">
                    <span className="mt-0.5 w-5 shrink-0 text-sm text-slate-400">{i + 1}.</span>
                    <span className="text-slate-900">{action}</span>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="text-slate-500">None suggested.</p>
            )}
          </Section>

          <Section title="Precedents drawn on">
            {resolution.similarIncidents?.length ? (
              <ul className="space-y-2">
                {resolution.similarIncidents.map((refId) => {
                  // Retrieval returns ids. Most will be seeded incidents the dashboard
                  // already holds from the connect backlog, so they are shown with their
                  // detail; one that is not in memory is shown as a bare id rather than
                  // fetched, because the list is a citation, not a second feed.
                  const cited = byId.get(refId);
                  return (
                    <li key={refId} className="rounded border border-slate-200 px-3 py-2">
                      <span className="font-mono text-sm text-slate-700">{refId}</span>
                      {cited && (
                        <span className="ml-2 text-sm text-slate-600">
                          {errorTypeLabel(cited.analysis?.errorType)} ·{' '}
                          {cited.analysis?.affectedService}
                        </span>
                      )}
                      {cited?.resolutionNotes && (
                        <p className="mt-1.5 text-sm text-slate-600">{cited.resolutionNotes}</p>
                      )}
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p className="text-slate-500">
                Resolved without precedent — retrieval returned nothing usable.
              </p>
            )}
          </Section>
        </>
      ) : (
        <Section title="Resolution">
          {/*
            A null resolution means two opposite things and the frame type is the only
            thing that separates them (docs/websocket.md). Rendering both as one empty
            box would tell whoever is on call that the system gave up on an incident a
            human actually fixed in February.
          */}
          {incident.source === 'history' ? (
            <div>
              <p className="text-slate-600">
                Resolved by a person before this system existed — the Resolver was never
                asked. Their write-up:
              </p>
              <p className="mt-3 rounded border border-slate-200 bg-slate-50 px-3 py-2 text-slate-900">
                {incident.resolutionNotes || 'No notes recorded.'}
              </p>
            </div>
          ) : (
            <p className="rounded border border-amber-300 bg-amber-50 px-3 py-2 text-amber-900">
              The Resolver ran and produced nothing usable — most likely the Groq rate
              limit surviving its retry. The diagnosis above was still billed for and
              kept.
            </p>
          )}
        </Section>
      )}
    </article>
  );
}
