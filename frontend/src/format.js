// Presentation helpers. Nothing here changes meaning - anything that interprets the
// contract (what a null resolution means, what order incidents go in) lives in
// useIncidentStream.js and IncidentDetail.jsx, where it can be read next to the reasoning.

/** ErrorType arrives as its jsonValue - "ConnectionPoolExhausted". Space it for reading. */
export function errorTypeLabel(errorType) {
  if (!errorType) return 'Unknown';
  return errorType.replace(/([a-z0-9])([A-Z])/g, '$1 $2');
}

export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

// Muted, print-like, and legible after video compression. Deliberately not saturated:
// a wall of neon badges is where a demo stops looking like an operations tool.
export const SEVERITY_STYLES = {
  CRITICAL: 'bg-red-50 text-red-800 ring-red-300',
  HIGH: 'bg-orange-50 text-orange-800 ring-orange-300',
  MEDIUM: 'bg-amber-50 text-amber-900 ring-amber-300',
  LOW: 'bg-slate-100 text-slate-700 ring-slate-300',
};

export function severityStyle(severity) {
  return SEVERITY_STYLES[severity] ?? SEVERITY_STYLES.LOW;
}

/** Full timestamp, for the detail pane. */
export function formatInstant(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
}

/** Compact timestamp, for the list. */
export function formatShort(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const today = new Date().toDateString() === d.toDateString();
  return today
    ? d.toLocaleTimeString(undefined, { timeStyle: 'short' })
    : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function formatConfidence(confidence) {
  if (typeof confidence !== 'number') return '—';
  return `${Math.round(confidence * 100)}%`;
}
