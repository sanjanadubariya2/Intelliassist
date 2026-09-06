/**
 * The message contract between the host page and the widget's iframe.
 *
 * The split is forced by security, not preference. The backend authorises a
 * widget by matching the request's `Origin` against the bot's allowed domains.
 * An iframe written with `srcdoc` has an opaque origin, so its requests carry
 * `Origin: null` and would be rejected — and pointing the iframe at the API's
 * own origin only swaps one wrong value for another.
 *
 * So the frame renders and the parent fetches: network calls stay in the host
 * page's context, where `Origin` is the customer's real domain, and the two
 * halves talk over `postMessage`.
 *
 * Neither side can check `event.origin` — it is `"null"` for a srcdoc frame —
 * so both verify the *identity* of the sender window instead, which cannot be
 * spoofed by another frame on the page.
 */

/** Appearance and behaviour, read from the embedding script tag. */
export interface WidgetConfig {
  title: string;
  color: string;
  greeting: string;
  placeholder: string;
  position: "bottom-right" | "bottom-left";
}

/** One source behind an answer. Mirrors the backend's CitationResponse. */
export interface Citation {
  filename: string;
  pageNumber: number | null;
}

/** Frame → host. */
export type FrameMessage =
  | { channel: "intelliassist"; type: "ready" }
  | { channel: "intelliassist"; type: "open" }
  | { channel: "intelliassist"; type: "close" }
  | { channel: "intelliassist"; type: "send"; text: string };

/** Host → frame. */
export type HostMessage =
  | { channel: "intelliassist"; type: "config"; config: WidgetConfig }
  | { channel: "intelliassist"; type: "setOpen"; open: boolean }
  | { channel: "intelliassist"; type: "token"; text: string }
  | { channel: "intelliassist"; type: "citations"; citations: Citation[] }
  | { channel: "intelliassist"; type: "answer"; text: string; citations: Citation[] }
  | { channel: "intelliassist"; type: "done" }
  | { channel: "intelliassist"; type: "error"; message: string };

export const CHANNEL = "intelliassist";

/** Guards against the unrelated postMessage traffic a busy page carries. */
export function isWidgetMessage(data: unknown): data is { channel: string; type: string } {
  return (
    typeof data === "object" &&
    data !== null &&
    (data as { channel?: unknown }).channel === CHANNEL &&
    typeof (data as { type?: unknown }).type === "string"
  );
}
