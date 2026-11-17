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

  // The panel explains the most recent finished answer.
  const lastAnswer = [...turns].reverse().find((turn) => turn.role === "assistant" && turn.content && !turn.failed);

  return (
    <div className="flex flex-wrap items-stretch gap-5">
    <Card className="flex h-[640px] min-w-0 flex-[999_1_480px] flex-col overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-6 py-4">
        <div>
          <h2 className="font-display text-[20px] font-semibold">{botName ?? "Your bot"}</h2>
          <p className="text-[13px] text-muted">Nothing here reaches your customers.</p>
        </div>
        {turns.length > 0 && (
          <Button size="sm" variant="secondary" onClick={reset}>
            <RotateCcw className="h-3.5 w-3.5" />
            Reset chat
          </Button>
        )}
      </div>

      <div ref={scroller} className="flex-1 space-y-4 overflow-y-auto p-6">
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
                  className="min-h-9 rounded-full border border-[#e2d4c5] bg-surface px-3.5 text-[13px] font-medium text-ink transition-colors hover:border-clay/40 hover:bg-cream"
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
              <p className="max-w-[75%] rounded-2xl rounded-br-[4px] bg-accent px-4 py-3 text-[15px] leading-normal text-white">
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
                    "rounded-2xl rounded-bl-[4px] px-4 py-3 text-[15px] leading-relaxed",
                    turn.failed
                      ? "border border-bad/20 bg-bad-soft text-bad"
                      : turn.ungrounded
                        ? "whitespace-pre-wrap border border-[#f0dcae] bg-[#fffbf2]"
                        : "whitespace-pre-wrap border border-line bg-cream",
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
                  <Badge tone="warn">Not grounded: no supporting document. Check KB Health for gaps</Badge>
                )}
                {turn.citations && turn.citations.length > 0 && !turn.failed && <Badge tone="ok">Grounded</Badge>}
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
        className="flex gap-2.5 border-t border-line bg-surface px-6 pb-5 pt-4"
      >
        <input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder="Ask anything about your business…"
          aria-label="Your question"
          disabled={busy}
          className="min-h-12 min-w-0 flex-1 rounded-[14px] border border-[#e2d4c5] bg-cream px-4 text-[15px] placeholder:text-muted focus:border-clay/50 focus:outline-none focus:ring-2 focus:ring-clay/15 disabled:opacity-60"
        />
        <button
          type="submit"
          disabled={busy || !input.trim()}
          aria-label="Send"
          className="grid h-12 w-12 shrink-0 place-items-center rounded-xl bg-accent text-white transition-colors hover:bg-accent-strong disabled:opacity-40"
        >
          {busy ? <Spinner className="text-white" /> : <Send className="h-4 w-4" />}
        </button>
      </form>
    </Card>

    <WhyThisAnswer turn={lastAnswer} />
    </div>
  );
}

/**
 * The passages behind the latest answer, with their retrieval scores. This is
 * the panel that tells a builder whether a wrong answer is a retrieval problem
 * (wrong passage, low score) or a gap (no passage at all).
 */
function WhyThisAnswer({ turn }: { turn: Turn | undefined }) {
  const citations = turn?.citations ?? [];
  return (
    <aside className="flex min-w-0 flex-[1_1_320px] flex-col gap-4 rounded-2xl border border-line bg-surface p-6 shadow-soft">
      <div>
        <h2 className="font-display text-[18px] font-semibold">Why this answer</h2>
        <p className="mt-1 text-[13px] text-muted">
          {turn ? "The passages behind the latest reply." : "Ask something to see which passages the bot used."}
        </p>
      </div>
      {turn && (
        <div className="flex flex-wrap gap-2">
          {citations.length > 0 ? <Badge tone="ok">Grounded</Badge> : <Badge tone="warn">Not grounded</Badge>}
          <Badge>
            {citations.length} passage{citations.length === 1 ? "" : "s"}
          </Badge>
        </div>
      )}
      {turn && citations.length === 0 && (
        <p className="rounded-xl bg-[#fffbf2] px-3.5 py-3 text-[13px] leading-normal text-[#7d520c]">
          Nothing in the knowledge base matched, so the bot declined instead of guessing. Add a document that
          covers this question.
        </p>
      )}
      <ul className="flex flex-col gap-2.5">
        {citations.map((citation) => {
          const percent = Math.max(0, Math.min(100, Math.round(citation.score * 100)));
          return (
            <li
              key={`${citation.documentId}-${citation.marker}`}
              className="flex flex-col gap-2 rounded-xl border border-line px-3.5 py-3"
            >
              <div className="flex items-center gap-2">
                <FileText className="h-3.5 w-3.5 shrink-0 text-clay" />
                <span className="min-w-0 flex-1 truncate text-[13px] font-semibold">
                  {citation.filename}
                  {citation.pageNumber != null && ` · p.${citation.pageNumber}`}
                </span>
                <span className="text-[12px] text-muted">match {citation.score.toFixed(2)}</span>
              </div>
              <div className="h-1 rounded-full bg-[#f3ece4]" aria-hidden>
                <div className="h-full rounded-full bg-accent" style={{ width: `${percent}%` }} />
              </div>
              <p className="line-clamp-3 text-[13px] leading-normal text-[#4a3d35]">{citation.snippet}</p>
            </li>
          );
        })}
      </ul>
    </aside>
  );
}
