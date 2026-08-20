import { useState } from 'react';
import { postAnalyze } from '../api';

export default function SubmitForm({ onAnalyzed }) {
  const [logs, setLogs] = useState('');
  const [serviceName, setServiceName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function handleSubmit(event) {
    event.preventDefault();
    if (busy || !logs.trim()) return;

    setBusy(true);
    setError(null);
    try {
      const incident = await postAnalyze({ logs, serviceName });
      // The socket will push this same incident too. Merging the HTTP response is not
      // redundant: it is what makes the new row appear the instant the call returns,
      // and the id-keyed merge absorbs the duplicate.
      onAnalyzed(incident);
      setLogs('');
      setServiceName('');
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="px-4 py-4">
      <label htmlFor="logs" className="block text-sm font-medium text-slate-700">
        Paste logs
      </label>
      <textarea
        id="logs"
        value={logs}
        onChange={(e) => setLogs(e.target.value)}
        rows={6}
        spellCheck={false}
        placeholder={'2026-08-20T14:02:11Z ERROR [order-service] HikariPool-1 - Connection is not available…'}
        className="mt-1.5 w-full rounded border border-slate-300 bg-white px-3 py-2 font-mono text-sm text-slate-900 placeholder:text-slate-400 focus:border-slate-500 focus:outline-none"
      />

      <label htmlFor="service" className="mt-3 block text-sm font-medium text-slate-700">
        Service name <span className="font-normal text-slate-500">(optional)</span>
      </label>
      <input
        id="service"
        type="text"
        value={serviceName}
        onChange={(e) => setServiceName(e.target.value)}
        placeholder="leave blank to let the model infer it"
        className="mt-1.5 w-full rounded border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-slate-500 focus:outline-none"
      />
      {/* Absence is meaningful, not a missing value: blank means "infer the origin",
          a value overrides the model's affectedService. A multi-service dump is exactly
          the case where the caller does not know which service failed. */}
      <p className="mt-1.5 text-xs text-slate-500">
        A value here overrides the model's inferred service. Blank asks it to work out the
        origin from the logs.
      </p>

      <div className="mt-4 flex items-center gap-3">
        <button
          type="submit"
          disabled={busy || !logs.trim()}
          className="rounded bg-slate-800 px-4 py-2 text-sm font-medium text-white disabled:bg-slate-300"
        >
          {busy ? 'Analyzing…' : 'Analyze'}
        </button>
        {busy && (
          <span className="text-sm text-slate-500">
            Two model calls — this can take a minute.
          </span>
        )}
      </div>

      {error && (
        <p
          role="alert"
          className="mt-3 rounded border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-800"
        >
          {error}
        </p>
      )}

      {/* The daily Groq budget is 100k tokens and one submission spends roughly a tenth
          of it. Stating that in the UI is cheaper than discovering it as a 429. */}
      <p className="mt-3 text-xs text-slate-400">
        Each analysis costs ~9,800 tokens against a 100k/day budget — about ten a day.
        A repeat of an identical log dump is served from the Redis cache and costs
        nothing.
      </p>
    </form>
  );
}
