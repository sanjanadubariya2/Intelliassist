"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "./api";

export interface AsyncState<T> {
  data: T | null;
  /** True only while there is nothing to show yet. */
  loading: boolean;
  /** True while a reload runs behind data that is already on screen. */
  refreshing: boolean;
  error: string | null;
  reload: () => void;
}

function sameDeps(a: unknown[] | null, b: unknown[]) {
  return a !== null && a.length === b.length && a.every((value, index) => Object.is(value, b[index]));
}

/**
 * Loads something once and re-runs when `deps` change.
 *
 * Three things it gets right that a bare `useEffect` usually does not:
 *
 * - a slow response that arrives after the inputs changed is discarded rather
 *   than overwriting newer data;
 * - `reload` re-runs without a fake dependency;
 * - a reload keeps the current data on screen. Only a change of inputs clears
 *   it, because only then is the old data wrong. Without this, every poll and
 *   every save flashed the whole screen to a spinner and threw away whatever
 *   the user had scrolled to or just been told.
 */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  // Held in a ref so changing the callback identity does not re-fire the
  // effect. Callers write inline arrow functions, which change every render.
  const loadRef = useRef(load);
  loadRef.current = load;

  const previousDeps = useRef<unknown[] | null>(null);
  const hasData = useRef(false);

  useEffect(() => {
    let current = true;

    const inputsChanged = !sameDeps(previousDeps.current, deps);
    previousDeps.current = deps;

    if (inputsChanged || !hasData.current) {
      // Different inputs: what is on screen describes something else.
      setData(null);
      hasData.current = false;
      setLoading(true);
    } else {
      setRefreshing(true);
    }

    loadRef
      .current()
      .then((result) => {
        if (!current) return;
        setData(result);
        hasData.current = true;
        setError(null);
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
        if (!current) return;
        setLoading(false);
        setRefreshing(false);
      });

    return () => {
      // The inputs changed or the component went away: whatever this request
      // returns is stale, so it must not be written.
      current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, nonce]);

  const reload = useCallback(() => setNonce((value) => value + 1), []);

  // A failed background refresh keeps the last good data; the error is only
  // surfaced when there is nothing else to show.
  return { data, loading, refreshing, error: data !== null ? null : error, reload };
}

/**
 * Polls while `active` is true.
 *
 * Used to watch a document move from PENDING to INDEXED: indexing happens on a
 * worker, so the only way to see the change is to ask again. Pauses while the
 * tab is hidden, so a forgotten tab does not poll the API all night.
 */
export function usePolling(callback: () => void, intervalMs: number, active: boolean) {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => {
      if (typeof document !== "undefined" && document.hidden) return;
      callbackRef.current();
    }, intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs, active]);
}
