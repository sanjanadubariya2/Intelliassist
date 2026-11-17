"use client";

import type { DocumentStatus } from "@/lib/api";
import { Badge, type Tone } from "@/components/ui";

/**
 * Every status the backend can report, in one place.
 *
 * Two screens used to keep their own copy of this map, both missing the three
 * in-progress stages, so `STATUS[status].tone` threw and took the screen down
 * the moment a document started extracting.
 */
const STATUS: Record<DocumentStatus, { label: string; tone: Tone }> = {
  PENDING: { label: "Queued", tone: "neutral" },
  EXTRACTING: { label: "Reading", tone: "warn" },
  CHUNKING: { label: "Splitting", tone: "warn" },
  EMBEDDING: { label: "Indexing", tone: "warn" },
  INDEXED: { label: "Ready", tone: "ok" },
  FAILED: { label: "Failed", tone: "bad" },
};

export function DocumentStatusBadge({ status }: { status: DocumentStatus }) {
  // Anything added upstream later still renders rather than crashing.
  const entry = STATUS[status] ?? { label: status, tone: "neutral" as Tone };
  return <Badge tone={entry.tone}>{entry.label}</Badge>;
}
