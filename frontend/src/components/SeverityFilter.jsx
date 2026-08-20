import { SEVERITIES } from '../format';

export default function SeverityFilter({ selected, onChange, counts }) {
  function toggle(severity) {
    onChange(
      selected.includes(severity)
        ? selected.filter((s) => s !== severity)
        : [...selected, severity],
    );
  }

  const allOn = selected.length === 0;

  return (
    <div className="flex flex-wrap items-center gap-2">
      <button
        type="button"
        onClick={() => onChange([])}
        aria-pressed={allOn}
        className={`rounded border px-2.5 py-1 text-sm ${
          allOn
            ? 'border-slate-800 bg-slate-800 text-white'
            : 'border-slate-300 bg-white text-slate-700 hover:border-slate-400'
        }`}
      >
        All
      </button>
      {SEVERITIES.map((severity) => {
        const on = selected.includes(severity);
        return (
          <button
            key={severity}
            type="button"
            onClick={() => toggle(severity)}
            aria-pressed={on}
            className={`rounded border px-2.5 py-1 text-sm ${
              on
                ? 'border-slate-800 bg-slate-800 text-white'
                : 'border-slate-300 bg-white text-slate-700 hover:border-slate-400'
            }`}
          >
            {severity}
            <span className={on ? 'ml-1.5 text-slate-300' : 'ml-1.5 text-slate-400'}>
              {counts[severity] ?? 0}
            </span>
          </button>
        );
      })}
    </div>
  );
}
