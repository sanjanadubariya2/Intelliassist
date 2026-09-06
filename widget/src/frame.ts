/**
 * Everything the visitor sees. Runs inside the widget's iframe.
 *
 * It renders and nothing else: it holds no API key, knows no endpoint, and
 * makes no network request. Answers arrive from the host page over
 * `postMessage` (see protocol.ts for why the split exists).
 *
 * The iframe is what makes the customer's CSS irrelevant. A shadow root stops
 * their rules from matching ours, but still inherits the document's font
 * metrics, `!important` rules on `*`, and any global `@media print`. A separate
 * document inherits nothing at all.
 */

import {
  isWidgetMessage,
  type Citation,
  type FrameMessage,
  type HostMessage,
  type WidgetConfig,
  CHANNEL,
} from "./protocol";

const config: WidgetConfig = {
  title: "Assistant",
  color: "#7C5AED",
  greeting: "Hi there! 👋 How can I help you today?",
  placeholder: "Type your message...",
  position: "bottom-right",
};

let open = false;
let busy = false;
/** The bubble currently being streamed into, if any. */
let streaming: HTMLElement | null = null;
let answer = "";

// ------------------------------------------------------------------- send --

function toHost(message: FrameMessage) {
  // "*" is safe here: the payload carries no secret, and a srcdoc frame has no
  // concrete origin to target anyway.
  window.parent.postMessage(message, "*");
}

// ------------------------------------------------------------------- html --

const CSS = `
*, *::before, *::after { box-sizing: border-box; }
html, body {
  margin: 0; height: 100%;
  background: transparent;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  font-size: 14px; line-height: 1.5; color: #17172b;
}
body { display: flex; flex-direction: column; justify-content: flex-end; }
body.left { align-items: flex-start; }
body:not(.left) { align-items: flex-end; }

/* --------------------------------------------------------------- launcher */
.launcher {
  width: 56px; height: 56px; flex: none;
  border: 0; border-radius: 50%;
  background: var(--accent); color: #fff;
  cursor: pointer; display: grid; place-items: center;
  box-shadow: 0 6px 20px rgba(0,0,0,.18);
  transition: transform .18s ease;
}
.launcher:hover { transform: scale(1.06); }
.launcher:focus-visible { outline: 3px solid var(--accent); outline-offset: 3px; }
.launcher svg { width: 26px; height: 26px; }

/* ----------------------------------------------------------------- panel */
.panel {
  width: 100%; height: 100%;
  background: #fff; border-radius: 14px;
  box-shadow: 0 12px 48px rgba(0,0,0,.22);
  display: flex; flex-direction: column; overflow: hidden;
  animation: pop .16s ease-out;
}
@keyframes pop { from { opacity: 0; transform: scale(.96) translateY(8px); } }
@media (prefers-reduced-motion: reduce) {
  .panel { animation: none; }
  .launcher { transition: none; }
}

.header {
  background: var(--accent); color: #fff; flex: none;
  padding: 14px 16px; display: flex; align-items: center; gap: 10px;
}
.header .name { font-weight: 600; font-size: 15px; flex: 1; }
.header button {
  background: transparent; border: 0; color: #fff; cursor: pointer;
  padding: 4px; border-radius: 6px; display: grid; place-items: center; opacity: .85;
}
.header button:hover { opacity: 1; background: rgba(255,255,255,.16); }
.header svg { width: 16px; height: 16px; }

.log {
  flex: 1; overflow-y: auto; padding: 16px; background: #fafafa;
  display: flex; flex-direction: column; gap: 12px;
}

.msg {
  max-width: 82%; padding: 10px 13px; border-radius: 14px;
  white-space: pre-wrap; overflow-wrap: anywhere;
}
.msg.user { align-self: flex-end; background: var(--accent); color: #fff; border-bottom-right-radius: 4px; }
.msg.bot { align-self: flex-start; background: #fff; border: 1px solid #ececf3; border-bottom-left-radius: 4px; }
.msg.error { align-self: flex-start; background: #fff5f5; border: 1px solid #ffd7d7; color: #8a1f1f; }

.dots { display: flex; gap: 4px; align-items: center; padding: 4px 2px; }
.dots span {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--accent); opacity: .45; animation: blink 1.3s infinite ease-in-out;
}
.dots span:nth-child(2) { animation-delay: .18s; }
.dots span:nth-child(3) { animation-delay: .36s; }
@keyframes blink { 0%,80%,100% { opacity:.3; transform: translateY(0); } 40% { opacity:1; transform: translateY(-3px); } }
@media (prefers-reduced-motion: reduce) { .dots span { animation: none; opacity: .6; } }

/* Sources are shown, not hidden: a grounded answer is only worth more than a
   guess if the reader can check it. */
.sources { align-self: flex-start; max-width: 82%; display: flex; flex-direction: column; gap: 6px; }
.sources .label { font-size: 11px; color: #77778f; }
.source {
  display: flex; align-items: center; gap: 8px;
  background: #fff; border: 1px solid #ececf3; border-radius: 10px; padding: 8px 10px;
}
.source .icon { width: 22px; height: 22px; flex: none; display: grid; place-items: center; background: #fdecec; border-radius: 5px; }
.source .icon svg { width: 13px; height: 13px; color: #d33; }
.source .file { font-size: 12.5px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source .page { font-size: 11px; color: #8a8aa0; }

.composer { border-top: 1px solid #ececf3; background: #fff; flex: none; padding: 10px 12px; }
.composer form { display: flex; align-items: flex-end; gap: 8px; }
.composer textarea {
  flex: 1; border: 0; resize: none; outline: none; font: inherit; color: inherit;
  max-height: 96px; padding: 8px 4px; background: transparent;
}
.composer button {
  border: 0; background: var(--accent); color: #fff; flex: none;
  width: 34px; height: 34px; border-radius: 50%; cursor: pointer;
  display: grid; place-items: center;
}
.composer button:disabled { opacity: .4; cursor: not-allowed; }
.composer svg { width: 16px; height: 16px; }

.footer { text-align: center; font-size: 11px; color: #a0a0b8; padding: 0 0 8px; }
.footer b { color: var(--accent); font-weight: 600; }

@media (max-width: 480px) {
  .panel { border-radius: 0; }
}
`;

const ICONS: Record<string, string> = {
  chat: '<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>',
  close: '<path d="M18 6 6 18M6 6l12 12"/>',
  minimize: '<path d="M5 12h14"/>',
  send: '<path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/>',
};

function icon(name: keyof typeof ICONS | string): string {
  return (
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    ICONS[name] +
    "</svg>"
  );
}

function el(tag: string, className?: string, text?: string): HTMLElement {
  const node = document.createElement(tag);
  if (className) node.className = className;
  // textContent, never innerHTML: answers and filenames come from a model and
  // from uploaded documents, and neither is trusted markup.
  if (text != null) node.textContent = text;
  return node;
}

// ------------------------------------------------------------------ build --

let launcher: HTMLButtonElement;
let panel: HTMLElement;
let log: HTMLElement;
let input: HTMLTextAreaElement;
let sendButton: HTMLButtonElement;

function build() {
  const style = document.createElement("style");
  style.textContent = CSS;
  document.head.appendChild(style);

  launcher = el("button", "launcher") as HTMLButtonElement;
  launcher.type = "button";
  launcher.setAttribute("aria-label", "Open chat");
  launcher.innerHTML = icon("chat");
  launcher.addEventListener("click", () => setOpen(true));

  panel = el("div", "panel");
  panel.setAttribute("role", "dialog");
  panel.hidden = true;

  const header = el("div", "header");
  const name = el("div", "name", config.title);
  header.appendChild(name);

  for (const [label, glyph] of [
    ["Minimise chat", "minimize"],
    ["Close chat", "close"],
  ] as const) {
    const button = el("button") as HTMLButtonElement;
    button.type = "button";
    button.setAttribute("aria-label", label);
    button.innerHTML = icon(glyph);
    button.addEventListener("click", () => setOpen(false));
    header.appendChild(button);
  }

  log = el("div", "log");
  log.setAttribute("role", "log");
  log.setAttribute("aria-live", "polite");

  const composer = el("div", "composer");
  const form = el("form") as HTMLFormElement;

  input = el("textarea") as HTMLTextAreaElement;
  input.rows = 1;
  input.placeholder = config.placeholder;
  input.setAttribute("aria-label", "Message");
  input.addEventListener("input", () => {
    input.style.height = "auto";
    input.style.height = `${input.scrollHeight}px`;
  });
  input.addEventListener("keydown", (event) => {
    // Enter sends, Shift+Enter is a newline — as in every other chat box.
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      form.requestSubmit();
    }
  });

  sendButton = el("button") as HTMLButtonElement;
  sendButton.type = "submit";
  sendButton.setAttribute("aria-label", "Send");
  sendButton.innerHTML = icon("send");

  form.append(input, sendButton);
  form.addEventListener("submit", onSubmit);

  const footer = el("div", "footer");
  footer.innerHTML = "Powered by <b>IntelliAssist</b>";
  composer.append(form, footer);

  panel.append(header, log, composer);
  document.body.append(panel, launcher);

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && open) setOpen(false);
  });
}

function applyConfig() {
  document.documentElement.style.setProperty("--accent", config.color);
  document.body.classList.toggle("left", config.position === "bottom-left");
  const name = panel.querySelector(".name");
  if (name) name.textContent = config.title;
  input.placeholder = config.placeholder;
}

function setOpen(next: boolean) {
  open = next;
  panel.hidden = !next;
  launcher.hidden = next;

  // The host owns the iframe's size: it must grow before the panel is visible,
  // or the panel is clipped to launcher dimensions.
  toHost({ channel: CHANNEL, type: next ? "open" : "close" });

  if (next) {
    if (!log.childElementCount) addMessage(config.greeting, "bot");
    input.focus();
  }
}

// --------------------------------------------------------------- messages --

function addMessage(text: string, kind: "user" | "bot" | "error"): HTMLElement {
  const node = el("div", `msg ${kind}`, text);
  log.appendChild(node);
  log.scrollTop = log.scrollHeight;
  return node;
}

function addTyping(): HTMLElement {
  const wrap = el("div", "msg bot");
  const dots = el("div", "dots");
  dots.innerHTML = "<span></span><span></span><span></span>";
  wrap.appendChild(dots);
  log.appendChild(wrap);
  log.scrollTop = log.scrollHeight;
  return wrap;
}

function addSources(citations: Citation[]) {
  if (!citations.length) return;

  const wrap = el("div", "sources");
  wrap.appendChild(el("div", "label", citations.length === 1 ? "Source" : "Sources"));

  for (const citation of citations) {
    const card = el("div", "source");
    const iconBox = el("div", "icon");
    iconBox.innerHTML = icon("file");

    const meta = el("div", "meta");
    meta.appendChild(el("div", "file", citation.filename || "Document"));
    if (citation.pageNumber != null) {
      meta.appendChild(el("div", "page", `Page ${citation.pageNumber}`));
    }

    card.append(iconBox, meta);
    wrap.appendChild(card);
  }

  log.appendChild(wrap);
  log.scrollTop = log.scrollHeight;
}

let typing: HTMLElement | null = null;

function setBusy(next: boolean) {
  busy = next;
  sendButton.disabled = next;
  input.disabled = next;
}

function onSubmit(event: Event) {
  event.preventDefault();
  const text = input.value.trim();
  if (!text || busy) return;

  addMessage(text, "user");
  input.value = "";
  input.style.height = "auto";
  setBusy(true);

  streaming = null;
  answer = "";
  typing = addTyping();

  toHost({ channel: CHANNEL, type: "send", text });
}

function finish() {
  if (typing?.isConnected) typing.remove();
  typing = null;
  streaming = null;
  setBusy(false);
}

// ------------------------------------------------------------- host events --

window.addEventListener("message", (event: MessageEvent) => {
  // A srcdoc frame's parent has no checkable origin, so identity is the guard:
  // only the window that embedded us may drive the UI.
  if (event.source !== window.parent) return;
  if (!isWidgetMessage(event.data)) return;

  const message = event.data as HostMessage;

  switch (message.type) {
    case "config":
      Object.assign(config, message.config);
      applyConfig();
      break;

    case "setOpen":
      setOpen(message.open);
      break;

    case "token":
      if (!streaming) {
        if (typing?.isConnected) typing.remove();
        streaming = addMessage("", "bot");
      }
      answer += message.text;
      streaming.textContent = answer;
      log.scrollTop = log.scrollHeight;
      break;

    case "answer":
      finish();
      addMessage(message.text, "bot");
      addSources(message.citations);
      break;

    case "citations":
      addSources(message.citations);
      break;

    case "done":
      finish();
      break;

    case "error":
      finish();
      addMessage(message.message, "error");
      break;
  }
});

build();
applyConfig();
toHost({ channel: CHANNEL, type: "ready" });
