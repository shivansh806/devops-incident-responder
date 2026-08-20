// Relative, so the browser calls its own origin. In dev the Vite proxy forwards it to
// Spring on 8080; in production the built bundle is served by Spring and the same path
// already resolves. Neither case is cross-origin, which is why no CORS config exists.

/**
 * Two Groq calls run inside this request - the Analyzer and the Resolver - and the
 * Resolver waits 15s and retries when it is rate limited. The backend's own budget for
 * one event is ~310s worst case (docs/ingestion.md), so the client must not give up
 * first. A browser's default fetch has no timeout at all; this one is deliberate and
 * generous rather than absent.
 */
const ANALYZE_TIMEOUT_MS = 300_000;

export async function postAnalyze({ logs, serviceName }) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ANALYZE_TIMEOUT_MS);

  try {
    const response = await fetch('/api/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // serviceName is optional and absence is meaningful: absent means "infer the
      // origin", a value overrides the model's affectedService. An empty input box
      // must therefore send null, never "".
      body: JSON.stringify({
        logs,
        serviceName: serviceName?.trim() ? serviceName.trim() : null,
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      // Both handled failures return { "error": "..." } - 400 for an invalid request,
      // 502 when the Analyzer itself failed. Surface the message rather than the code.
      let message = `Request failed (${response.status})`;
      try {
        const body = await response.json();
        if (body?.error) message = body.error;
      } catch {
        // Not JSON - keep the status-code message.
      }
      throw new Error(message);
    }

    return await response.json();
  } catch (e) {
    if (e.name === 'AbortError') {
      throw new Error(`Timed out after ${ANALYZE_TIMEOUT_MS / 1000}s with no response.`);
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
