/**
 * The IntelliAssist embed loader.
 *
 * Runs on the customer's page. It owns three things and nothing else: the
 * iframe the chat is drawn in, the credential, and every network call.
 *
 *   <script src="https://api.example.com/widget/intelliassist.js"
 *           data-api-key="ia_..."
 *           data-api-url="https://api.example.com"
 *           data-title="Acme Assistant"
 *           data-color="#7C5AED"
 *           data-position="bottom-right"></script>
 *
 * Networking lives here rather than in the frame because the backend authorises
 * a widget by matching `Origin` against the bot's allowed domains, and a srcdoc
 * iframe sends `Origin: null`. Keeping fetch in the host page keeps that check
 * meaningful. See protocol.ts.
 */

import {
  isWidgetMessage,
  type Citation,
  type FrameMessage,
  type HostMessage,
  type WidgetConfig,
  CHANNEL,
} from "./protocol";

// Injected at build time: the compiled contents of frame.ts.
declare const __FRAME_SCRIPT__: string;

// `document.currentScript` is null inside any later callback, so it is read now.
const SCRIPT = document.currentScript as HTMLScriptElement | null;

if (!SCRIPT) {
  console.error("[IntelliAssist] must be loaded with a plain <script> tag");
} else {
  start(SCRIPT);
}

interface Settings extends WidgetConfig {
  apiKey: string;
  apiUrl: string;
  streaming: boolean;
}

function readSettings(script: HTMLScriptElement): Settings {
  const data = script.dataset;

  // `data-api-key` is the documented name; `data-bot-key` is accepted as an
  // alias so existing embeds keep working. There is deliberately no bot id:
  // the key already identifies exactly one bot, server-side, and accepting one
  // from the page would invite a caller to name a different bot.
  const apiKey = data.apiKey ?? data.botKey ?? "";

  return {
    apiKey,
    // Defaults to wherever the script was served from, which is correct when
    // the API hosts it — as the dashboard's snippet does.
    apiUrl: (data.apiUrl ?? new URL(script.src, location.href).origin).replace(/\/$/, ""),
    title: data.title ?? "Assistant",
    color: data.color ?? "#7C5AED",
    greeting: data.greeting ?? "Hi there! 👋 How can I help you today?",
    placeholder: data.placeholder ?? "Type your message...",
    position: data.position === "bottom-left" ? "bottom-left" : "bottom-right",
    streaming: data.streaming !== "false",
  };
}

/**
 * A stable, anonymous id so a visitor's messages group into one conversation
 * across page loads. It identifies nobody and is not sent anywhere else.
 */
function readVisitorId(): string {
  const KEY = "intelliassist.visitor";
  try {
    const existing = localStorage.getItem(KEY);
    if (existing) return existing;
    const fresh = `v-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
    localStorage.setItem(KEY, fresh);
    return fresh;
  } catch {
    // Private mode or blocked site data: a per-page id still works.
    return `v-${Math.random().toString(36).slice(2)}`;
  }
}

function start(script: HTMLScriptElement) {
  const settings = readSettings(script);
  if (!settings.apiKey) {
    console.error("[IntelliAssist] data-api-key is required");
    return;
  }

  const visitorId = readVisitorId();
  let conversationId: string | null = null;

  // ------------------------------------------------------------- iframe --

  const frame = document.createElement("iframe");
  frame.title = "Chat";
  frame.setAttribute("aria-label", settings.title);
  // No allow-same-origin: the frame gets an opaque origin and so cannot reach
  // the host page's DOM, cookies, or storage even though we author its markup.
  frame.setAttribute("sandbox", "allow-scripts allow-forms");

  Object.assign(frame.style, {
    position: "fixed",
    border: "0",
    // Above effectively everything, but below the 2147483647 that some
    // cookie banners claim, so we never cover a consent dialog.
    zIndex: "2147483000",
    colorScheme: "normal",
    background: "transparent",
    display: "block",
  } as Partial<CSSStyleDeclaration>);

  let expanded = false;
  size(false);

  frame.srcdoc =
    "<!doctype html><html><head><meta charset='utf-8'>" +
    "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
    "</head><body><script>" +
    __FRAME_SCRIPT__ +
    "<\/script></body></html>";

  document.body.appendChild(frame);

  /** The iframe is only as large as it needs to be, so it blocks nothing. */
  function size(expanded: boolean) {
    const mobile = window.innerWidth <= 480;
    const style = frame.style;

    if (expanded && mobile) {
      Object.assign(style, {
        inset: "0",
        width: "100%",
        height: "100%",
        left: "0",
        right: "0",
        top: "0",
        bottom: "0",
      } as Partial<CSSStyleDeclaration>);
      return;
    }

    const side = settings.position === "bottom-left" ? "left" : "right";
    const other = side === "left" ? "right" : "left";

    style.top = "auto";
    style.inset = "";
    style[other as "left" | "right"] = "auto";
    style.bottom = expanded ? "20px" : "20px";
    style[side as "left" | "right"] = "20px";
    // Extra height when collapsed leaves room for the launcher's shadow.
    style.width = expanded ? "min(384px, calc(100vw - 40px))" : "76px";
    style.height = expanded ? "min(600px, calc(100vh - 40px))" : "76px";
  }

  window.addEventListener("resize", () => size(expanded));

  function toFrame(message: HostMessage) {
    frame.contentWindow?.postMessage(message, "*");
  }

  // ------------------------------------------------------------ messages --

  window.addEventListener("message", (event: MessageEvent) => {
    // The frame has an opaque origin, so `event.origin` is "null" and useless.
    // Identity is the check that actually holds: only our own frame is obeyed.
    if (event.source !== frame.contentWindow) return;
    if (!isWidgetMessage(event.data)) return;

    const message = event.data as FrameMessage;

    switch (message.type) {
      case "ready":
        toFrame({
          channel: CHANNEL,
          type: "config",
          config: {
            title: settings.title,
            color: settings.color,
            greeting: settings.greeting,
            placeholder: settings.placeholder,
            position: settings.position,
          },
        });
        break;

      case "open":
        expanded = true;
        size(true);
        break;

      case "close":
        expanded = false;
        size(false);
        break;

      case "send":
        void ask(message.text);
        break;
    }
  });

  // ------------------------------------------------------------- network --

  function endpoint(path: string) {
    return `${settings.apiUrl}/api/v1/public${path}`;
  }

  function headers(): Record<string, string> {
    return {
      "Content-Type": "application/json",
      // Public by design: it ships in the page source of every customer site.
      // Safety comes from the server checking it against the bot's allowed
      // domains, not from hiding it.
      "X-Bot-Key": settings.apiKey,
    };
  }

  function fail(error: unknown) {
    console.error("[IntelliAssist]", error);
    toFrame({
      channel: CHANNEL,
      type: "error",
      message: "Sorry, I'm unable to answer right now. Please try again in a moment.",
    });
  }

  async function ask(text: string) {
    try {
      if (settings.streaming) await stream(text);
      else await send(text);
    } catch (error) {
      fail(error);
    }
  }

  /** One request, one answer. The fallback when SSE cannot get through. */
  async function send(text: string) {
    const path = conversationId ? `/conversations/${conversationId}/chat` : "/chat";

    const response = await fetch(endpoint(path), {
      method: "POST",
      headers: headers(),
      body: JSON.stringify({ content: text, visitorId }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    const body = (await response.json()) as {
      conversationId: string;
      answer: string;
      citations: Citation[];
    };

    conversationId = body.conversationId;
    toFrame({
      channel: CHANNEL,
      type: "answer",
      text: body.answer,
      citations: body.citations ?? [],
    });
  }

  /**
   * Streamed answer.
   *
   * `EventSource` is unusable: it cannot POST and cannot set `X-Bot-Key`, so
   * the SSE frames are parsed out of the fetch body by hand.
   */
  async function stream(text: string) {
    const path = conversationId
      ? `/conversations/${conversationId}/chat/stream`
      : "/chat/stream";

    const response = await fetch(endpoint(path), {
      method: "POST",
      headers: headers(),
      body: JSON.stringify({ content: text, visitorId }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    if (!response.body) throw new Error("no response body");

    // Sent before the first token: SSE events come straight from the AI
    // service, which has no concept of a conversation, so the id cannot ride
    // in the body. Without it every message would open a new conversation.
    const streamed = response.headers.get("X-Conversation-Id");
    if (streamed) conversationId = streamed;

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let failed = false;

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Frames are separated by a blank line; a partial one waits in the
      // buffer for the rest of it.
      const frames = buffer.split("\n\n");
      buffer = frames.pop() ?? "";

      for (const raw of frames) {
        let name: string | null = null;
        let payload: Record<string, unknown> | null = null;

        for (const line of raw.split("\n")) {
          if (line.startsWith("event: ")) name = line.slice(7).trim();
          else if (line.startsWith("data: ")) {
            try {
              payload = JSON.parse(line.slice(6)) as Record<string, unknown>;
            } catch {
              payload = null;
            }
          }
        }
        if (!name || !payload) continue;

        if (name === "token") {
          toFrame({ channel: CHANNEL, type: "token", text: String(payload.text ?? "") });
        } else if (name === "citations") {
          toFrame({
            channel: CHANNEL,
            type: "citations",
            citations: (payload.citations as Citation[]) ?? [],
          });
        } else if (name === "error") {
          failed = true;
          toFrame({
            channel: CHANNEL,
            type: "error",
            message: "Sorry, I couldn't finish that answer. Please try again.",
          });
        }
      }
    }

    if (!failed) toFrame({ channel: CHANNEL, type: "done" });
  }

  // Lets a host page drive the widget from its own button.
  (window as unknown as Record<string, unknown>).IntelliAssist = {
    open: () => toFrame({ channel: CHANNEL, type: "setOpen", open: true }),
    close: () => toFrame({ channel: CHANNEL, type: "setOpen", open: false }),
  };
}
