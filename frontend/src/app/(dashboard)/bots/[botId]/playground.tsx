"use client";

import { useEffect, useRef, useState } from "react";
import { FileText, RotateCcw, Send } from "lucide-react";
import { api, streamMessage, type Citation } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Avatar, Badge, Button, Card, LogoMark, Spinner, cx } from "@/components/ui";

interface Turn {
  role: "user" | "assistant";
  content: string;
  citations?: Citation[];
  /** Set once the answer finishes with no citations at all. */
  ungrounded?: boolean;
  failed?: boolean;
}

const STARTERS = [
  "What do you offer?",
  "How long does delivery take?",
  "What is your refund policy?",
];

/**
 * A place to try the bot exactly as a visitor would, without embedding it.
 *
 * Streams over the dashboard endpoint rather than the public one, because the
 * dashboard is authenticated by JWT and has no bot key, and minting a key just
 * to test would leave a real credential lying around.
 */
export function BotPlayground({ botId, botName }: { botId: string; botName?: string }) {
  const { user } = useAuth();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);

  const scroller = useRef<HTMLDivElement>(null);
  const abort = useRef<AbortController | null>(null);

  // Scroll the transcript itself, not the page. `scrollIntoView` on the last
  // message dragged the whole window down on every streamed token.
  useEffect(() => {
    const element = scroller.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [turns]);

  // A stream left running after navigating away would keep writing to state
  // that no longer exists.
  useEffect(() => () => abort.current?.abort(), []);

  async function ask(question: string) {
    if (!question || busy) return;

    setInput("");
    setBusy(true);
    setTurns((current) => [
      ...current,
      { role: "user", content: question },
      { role: "assistant", content: "" },
    ]);

    /** Rewrites the answer in place as tokens arrive. */
    function updateAnswer(change: (turn: Turn) => Turn) {
      setTurns((current) => {
        const next = [...current];
        next[next.length - 1] = change(next[next.length - 1]);
        return next;
      });
    }

    const controller = new AbortController();
    abort.current = controller;

    try {
      let id = conversationId;
      if (!id) {
        id = (await api.conversations.start(botId)).id;
        setConversationId(id);
      }

      let answered = false;
      let cited = false;

      await streamMessage(
        id,
        question,
        {
          onToken: (text) => {
            answered = true;
            updateAnswer((turn) => ({ ...turn, content: turn.content + text }));
          },
          onCitations: (citations) => {
            cited = citations.length > 0;
            updateAnswer((turn) => ({ ...turn, citations }));
          },
          onError: (message) => {
            updateAnswer((turn) => ({ ...turn, content: message, failed: true }));
          },
          onDone: () => {
            // No citations means the bot declined rather than answering from a
            // document. Worth surfacing here: it is the signal that the
            // knowledge base is missing something.
            if (answered && !cited) updateAnswer((turn) => ({ ...turn, ungrounded: true }));
          },
        },
        controller.signal,
      );
    } catch (caught) {
      if (caught instanceof DOMException && caught.name === "AbortError") return;
      updateAnswer((turn) => ({
        ...turn,
        content: caught instanceof Error ? caught.message : "The assistant could not answer.",
        failed: true,
      }));
    } finally {
      setBusy(false);
    }
  }

  function reset() {
    abort.current?.abort();
    setTurns([]);
    setConversationId(null);
    setBusy(false);
  }

  return (
    <Card className="flex h-[640px] flex-col overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
        <div>
          <h2 className="font-display text-[20px] font-semibold">Chat Playground</h2>
          <p className="text-[13px] text-muted">
            Test how {botName ?? "your chatbot"} answers using your business data.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-line bg-surface px-2.5 py-1 text-[12px] font-medium">
            <span className="h-2 w-2 rounded-full bg-ok" />
            RAG Active
          </span>
          {turns.length > 0 && (
            <Button size="sm" variant="secondary" onClick={reset}>
              <RotateCcw className="h-3.5 w-3.5" />
              New chat
            </Button>
          )}
        </div>
      </div>

      <div ref={scroller} className="flex-1 space-y-5 overflow-y-auto bg-cream/60 p-5">
        {turns.length === 0 && (
          <div className="mx-auto max-w-md py-10 text-center">
            <span className="mx-auto grid h-12 w-12 place-items-center rounded-full bg-accent text-white">
              <LogoMark className="h-6 w-6 text-white" />
            </span>
            <p className="mt-3 text-sm font-medium">Ask what your customers would</p>
            <p className="mt-1 text-[13px] text-muted">
              Try a question your documents can answer, then one they cannot, and watch it
              decline rather than invent.
            </p>
            <div className="mt-4 flex flex-wrap justify-center gap-2">
              {STARTERS.map((starter) => (
                <button
                  key={starter}
                  onClick={() => ask(starter)}
                  className="rounded-full border border-line bg-surface px-3 py-1.5 text-[12.5px] text-muted transition-colors hover:border-clay/40 hover:text-ink"
                >
                  {starter}
                </button>
              ))}
            </div>
          </div>
        )}

        {turns.map((turn, index) =>
          turn.role === "user" ? (
            <div key={index} className="flex items-end justify-end gap-2">
              <p className="max-w-[78%] rounded-2xl rounded-br-md bg-clay-soft px-4 py-2.5 text-sm text-ink">
                {turn.content}
              </p>
              <Avatar name={user?.user.name ?? "You"} />
            </div>
          ) : (
            <div key={index} className="flex items-start gap-2.5">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-accent text-white">
                <LogoMark className="h-4.5 w-4.5 text-white" />
              </span>
              <div className="min-w-0 max-w-[85%] space-y-2">
                <div
                  className={cx(
                    "rounded-2xl rounded-tl-md px-4 py-2.5 text-sm shadow-soft",
                    turn.failed
                      ? "border border-bad/20 bg-bad-soft text-bad"
                      : "border border-line bg-surface whitespace-pre-wrap",
                  )}
                >
                  {turn.content || <Spinner />}

                  {turn.citations && turn.citations.length > 0 && (
                    <div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-line pt-2.5">
                      <span className="text-[11.5px] font-medium text-muted">Sources:</span>
                      {turn.citations.map((citation) => (
                        <span
                          key={`${citation.documentId}-${citation.marker}`}
                          title={citation.snippet}
                          className="inline-flex max-w-[220px] items-center gap-1 rounded-lg bg-clay-soft px-2 py-0.5 text-[11.5px] text-ink"
                        >
                          <FileText className="h-3 w-3 shrink-0 text-clay" />
                          <span className="truncate">{citation.filename}</span>
                          {citation.pageNumber != null && (
                            <span className="shrink-0 text-muted">p.{citation.pageNumber}</span>
                          )}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                {turn.ungrounded && !turn.failed && (
                  <Badge tone="warn">No supporting document. Check KB Health for gaps</Badge>
                )}
              </div>
            </div>
          ),
        )}
      </div>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          ask(input.trim());
        }}
        className="border-t border-line bg-surface p-3"
      >
        <div className="flex items-center gap-2 rounded-full border border-line bg-cream py-1.5 pl-4 pr-1.5 focus-within:border-clay/50 focus-within:ring-2 focus-within:ring-clay/15">
          <input
            value={input}
            onChange={(event) => setInput(event.target.value)}
            placeholder="Ask anything about your business…"
            aria-label="Your question"
            disabled={busy}
            className="min-w-0 flex-1 bg-transparent text-sm placeholder:text-faint focus:outline-none disabled:opacity-60"
          />
          <button
            type="submit"
            disabled={busy || !input.trim()}
            aria-label="Send"
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-accent text-white transition-colors hover:bg-accent-strong disabled:opacity-40"
          >
            {busy ? <Spinner className="text-white" /> : <Send className="h-4 w-4" />}
          </button>
        </div>
      </form>
    </Card>
  );
}
