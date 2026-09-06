"use client";

import { useEffect, useRef, useState } from "react";
import { FileText, Send } from "lucide-react";
import { api, streamMessage, type Citation } from "@/lib/api";
import { Badge, Button, Card, CardHeader, Spinner } from "@/components/ui";

interface Turn {
  role: "user" | "assistant";
  content: string;
  citations?: Citation[];
  /** Set once the answer finishes with no citations at all. */
  ungrounded?: boolean;
  failed?: boolean;
}

/**
 * A place to try the bot exactly as a visitor would, without embedding it.
 *
 * Streams over the dashboard endpoint rather than the public one, because the
 * dashboard is authenticated by JWT and has no bot key — and minting a key just
 * to test would leave a real credential lying around.
 */
export function BotPlayground({ botId }: { botId: string }) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);

  const endRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [turns]);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    const question = input.trim();
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

    try {
      let id = conversationId;
      if (!id) {
        id = (await api.conversations.start(botId)).id;
        setConversationId(id);
      }

      let answered = false;
      let cited = false;

      await streamMessage(id, question, {
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
      });
    } catch (caught) {
      updateAnswer((turn) => ({
        ...turn,
        content:
          caught instanceof Error ? caught.message : "The assistant could not answer.",
        failed: true,
      }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mx-auto flex h-[600px] max-w-3xl flex-col">
      <CardHeader
        title="Playground"
        description="Ask what your visitors would. Answers stream exactly as they do in the widget."
        action={
          turns.length > 0 ? (
            <Button
              size="sm"
              variant="secondary"
              onClick={() => {
                setTurns([]);
                setConversationId(null);
              }}
            >
              New conversation
            </Button>
          ) : undefined
        }
      />

      <div className="flex-1 space-y-4 overflow-y-auto bg-canvas p-5">
        {turns.length === 0 && (
          <p className="py-12 text-center text-[13px] text-muted">
            Ask a question your documents can answer — then one they cannot, and watch it
            decline rather than invent.
          </p>
        )}

        {turns.map((turn, index) =>
          turn.role === "user" ? (
            <div key={index} className="flex justify-end">
              <p className="max-w-[78%] rounded-2xl rounded-br-md bg-accent px-3.5 py-2.5 text-sm text-white">
                {turn.content}
              </p>
            </div>
          ) : (
            <div key={index} className="max-w-[85%] space-y-2">
              <div
                className={
                  turn.failed
                    ? "rounded-2xl rounded-bl-md border border-bad/20 bg-bad-soft px-3.5 py-2.5 text-sm text-bad"
                    : "rounded-2xl rounded-bl-md border border-line bg-surface px-3.5 py-2.5 text-sm whitespace-pre-wrap"
                }
              >
                {turn.content || <Spinner />}
              </div>

              {turn.ungrounded && !turn.failed && (
                <Badge tone="warn">No supporting document</Badge>
              )}

              {turn.citations && turn.citations.length > 0 && (
                <div className="space-y-1.5">
                  <p className="text-[11.5px] text-muted">Sources</p>
                  {turn.citations.map((citation) => (
                    <div
                      key={`${citation.documentId}-${citation.marker}`}
                      className="rounded-lg border border-line bg-surface px-3 py-2"
                    >
                      <p className="flex items-center gap-2 text-[12.5px] font-medium">
                        <FileText className="h-3.5 w-3.5 shrink-0 text-faint" />
                        <span className="truncate">{citation.filename}</span>
                        {citation.pageNumber != null && (
                          <span className="shrink-0 text-[11.5px] font-normal text-muted">
                            p.{citation.pageNumber}
                          </span>
                        )}
                      </p>
                      <p className="mt-1 line-clamp-2 text-[12px] text-muted">
                        {citation.snippet}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ),
        )}
        <div ref={endRef} />
      </div>

      <form onSubmit={send} className="flex gap-2 border-t border-line p-3">
        <input
          value={input}
          onChange={(event) => setInput(event.target.value)}
          placeholder="Ask a question…"
          disabled={busy}
          className="flex-1 rounded-lg border border-line px-3 py-2 text-sm placeholder:text-faint focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 disabled:opacity-60"
        />
        <Button type="submit" loading={busy} disabled={!input.trim()}>
          <Send className="h-4 w-4" />
        </Button>
      </form>
    </Card>
  );
}
