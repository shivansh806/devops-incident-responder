import { useMemo, useState } from 'react';
import { useIncidentStream } from './useIncidentStream';
import IncidentList from './components/IncidentList';
import IncidentDetail from './components/IncidentDetail';
import SeverityFilter from './components/SeverityFilter';
import SubmitForm from './components/SubmitForm';

const STATUS_LABELS = {
  connecting: 'Connecting',
  open: 'Live',
  closed: 'Disconnected',
  error: 'Connection failed',
};

const STATUS_STYLES = {
  connecting: 'bg-slate-100 text-slate-700 ring-slate-300',
  open: 'bg-emerald-50 text-emerald-800 ring-emerald-300',
  closed: 'bg-slate-100 text-slate-700 ring-slate-300',
  error: 'bg-red-50 text-red-800 ring-red-300',
};

export default function App() {
  const { status, detail, incidents, backlogCount, upsert } = useIncidentStream();
  const [selectedId, setSelectedId] = useState(null);
  const [severities, setSeverities] = useState([]);

  // Counts come from the unfiltered list so the filter buttons keep showing what is
  // there rather than what survived the filter.
  const counts = useMemo(() => {
    const acc = {};
    for (const incident of incidents) {
      const severity = incident.analysis?.severity;
      if (severity) acc[severity] = (acc[severity] ?? 0) + 1;
    }
    return acc;
  }, [incidents]);

  const visible = useMemo(
    () =>
      severities.length === 0
        ? incidents
        : incidents.filter((i) => severities.includes(i.analysis?.severity)),
    [incidents, severities],
  );

  const byId = useMemo(() => new Map(incidents.map((i) => [i.id, i])), [incidents]);

  // Reading the selection out of the live map rather than holding the object means a
  // selected incident updates in place if a later frame replaces it.
  const selected = selectedId ? byId.get(selectedId) : null;

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white px-6 py-4">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <h1 className="text-lg font-semibold">Incident Responder</h1>
          <span
            className={`rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${STATUS_STYLES[status]}`}
          >
            {STATUS_LABELS[status]}
          </span>
          {status === 'open' && backlogCount !== null && (
            <span className="text-sm text-slate-500">
              {incidents.length} incidents · {backlogCount} replayed on connect
            </span>
          )}
          {status !== 'open' && detail && (
            <span className="text-sm text-slate-500">{detail}</span>
          )}
        </div>
      </header>

      <div className="mx-auto flex max-w-[1600px] flex-col gap-6 px-6 py-6 lg:flex-row lg:items-start">
        <div className="w-full lg:w-[420px] lg:shrink-0">
          <section className="rounded border border-slate-200 bg-white">
            <h2 className="border-b border-slate-200 px-4 py-3 text-sm font-semibold text-slate-700">
              Submit logs
            </h2>
            <SubmitForm onAnalyzed={(incident) => {
              upsert(incident, 'live');
              setSelectedId(incident.id);
            }} />
          </section>

          <section className="mt-6 rounded border border-slate-200 bg-white">
            <div className="border-b border-slate-200 px-4 py-3">
              <h2 className="text-sm font-semibold text-slate-700">Incidents</h2>
              <p className="mt-0.5 text-xs text-slate-500">
                Newest first, by analysis time.
              </p>
              <div className="mt-3">
                <SeverityFilter
                  selected={severities}
                  onChange={setSeverities}
                  counts={counts}
                />
              </div>
            </div>
            <div className="max-h-[calc(100vh-8rem)] overflow-y-auto">
              <IncidentList
                incidents={visible}
                selectedId={selectedId}
                onSelect={setSelectedId}
              />
            </div>
          </section>
        </div>

        <main className="min-w-0 flex-1 rounded border border-slate-200 bg-white">
          <IncidentDetail incident={selected} byId={byId} />
        </main>
      </div>
    </div>
  );
}
