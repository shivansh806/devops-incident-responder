import { errorTypeLabel, formatShort, severityStyle } from '../format';

export default function IncidentList({ incidents, selectedId, onSelect }) {
  if (incidents.length === 0) {
    return (
      <p className="px-4 py-6 text-slate-500">
        No incidents match this filter.
      </p>
    );
  }

  return (
    <ul className="divide-y divide-slate-200">
      {incidents.map((incident) => {
        const selected = incident.id === selectedId;
        const analysis = incident.analysis ?? {};
        return (
          <li key={incident.id}>
            <button
              type="button"
              onClick={() => onSelect(incident.id)}
              aria-current={selected}
              className={`w-full px-4 py-3 text-left ${
                selected ? 'bg-slate-100' : 'bg-white hover:bg-slate-50'
              }`}
            >
              <div className="flex items-baseline gap-2">
                <span className="font-medium text-slate-900">
                  {errorTypeLabel(analysis.errorType)}
                </span>
                <span
                  className={`rounded px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset ${severityStyle(
                    analysis.severity,
                  )}`}
                >
                  {analysis.severity ?? '—'}
                </span>
                <span className="ml-auto shrink-0 text-xs text-slate-500">
                  {formatShort(incident.analyzedAt)}
                </span>
              </div>
              <div className="mt-1 flex items-baseline gap-2 text-sm text-slate-600">
                <span className="truncate">{analysis.affectedService ?? 'unknown service'}</span>
                {/* Seeded history keeps a human INC- id; live incidents get Mongo's
                    ObjectId. That difference is the only thing separating the two, so
                    it is shown rather than a badge invented for it. */}
                {incident.source === 'history' && (
                  <span className="ml-auto shrink-0 text-xs text-slate-400">past</span>
                )}
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
