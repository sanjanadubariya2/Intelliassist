"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "./api";

export interface AsyncState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

/**
 * Loads something once and re-runs when `deps` change.
 *
 * Two things it gets right that a bare `useEffect` usually does not: a slow
 * response that arrives after the inputs changed is discarded rather than
 * overwriting newer data, and `reload` re-runs without a fake dependency.
 */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  // Held in a ref so changing the callback identity does not re-fire the
  // effect. Callers write inline arrow functions, which change every render.
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    let current = true;
    setLoading(true);
    setError(null);

    loadRef
      .current()
      .then((result) => {
        if (current) setData(result);
      })
      .catch((caught) => {
        if (!current) return;
        setError(
          caught instanceof ApiError
            ? caught.message
            : caught instanceof Error
              ? caught.message
              : "Something went wrong.",
        );
      })
      .finally(() => {
        if (current) setLoading(false);
      });

    return () => {
      // The inputs changed or the component went away: whatever this request
      // returns is stale, so it must not be written.
      current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, nonce]);

  const reload = useCallback(() => setNonce((value) => value + 1), []);

  return { data, loading, error, reload };
}

/**
 * Polls while `active` is true.
 *
 * Used to watch a document move from PENDING to INDEXED: indexing happens on a
 * worker, so the only way to see the change is to ask again.
 */
export function usePolling(callback: () => void, intervalMs: number, active: boolean) {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => callbackRef.current(), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs, active]);
}
