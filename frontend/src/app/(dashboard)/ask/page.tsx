"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowRight, Check, CheckCircle2, Circle, Send, ShieldCheck, Sparkles, X } from "lucide-react";
import { api, type Bot } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useAsync } from "@/lib/hooks";
import {
  STARTERS,
  interpret,
  setupSteps,
  type Action,
  type Proposal,
  type Reply,
  type SetupState,
} from "@/lib/intelli";
import {
  Avatar,
  Button,
  Card,
  ErrorNote,
  Loading,
  PageHeader,
  Select,
  cx,
  notify,
} from "@/components/ui";

interface Entry {
  id: number;
  role: "user" | "intelli";
  text?: string;
  reply?: Reply;
  /** pending → awaiting a decision; applied / declined / failed once made. */
  state?: "pending" | "applied" | "declined" | "failed";
}

async function loadSetup(botId: string | null): Promise<SetupState> {
  const bots = (await api.bots.list(0, 100)).items;
  const bot = bots.find((candidate) => candidate.id === botId) ?? bots[0] ?? null;
  if (!bot) return { bot: null, bots, documents: 0, indexedDocuments: 0, activeKeys: 0, domains: 0 };

  const [documents, keys, domains] = await Promise.all([
    api.knowledge.listDocuments(bot.id, 0, 100),
    api.credentials.listKeys(bot.id),
    api.credentials.listDomains(bot.id),
  ]);

  return {
    bot,
    bots,
    documents: documents.totalItems,
    indexedDocuments: documents.items.filter((document) => document.status === "INDEXED").length,
    activeKeys: keys.filter((key) => key.active).length,
    domains: domains.length,
  };
}

/** Writes an approved proposal. The only place Ask Intelli changes anything. */
async function apply(action: Action, bots: Bot[]): Promise<string> {
  switch (action.kind) {
    case "appearance":
      await api.bots.updateAppearance(action.botId, action.patch);
      return "Saved. Live widgets pick it up on their next page load.";
    case "createBot": {
      const bot = await api.bots.create({ name: action.name });
      return `Created ${bot.name}. Next, upload a document so it has something to answer from.`;
    }
    case "addDomain":
      await api.credentials.addDomain(action.botId, { domain: action.domain });
      return `${action.domain} is allowed.`;
    case "status":
    case "rename":
    case "language": {
      // PUT replaces every field, so the current values go along with the change.
      const bot = bots.find((candidate) => candidate.id === action.botId);
      if (!bot) throw new Error("That bot no longer exists.");
      await api.bots.update(bot.id, {
        name: action.kind === "rename" ? action.name : bot.name,
        description: bot.description ?? undefined,
        status: action.kind === "status" ? action.status : bot.status,
        defaultLanguage: action.kind === "language" ? action.language : bot.defaultLanguage,
        systemPrompt: bot.systemPrompt ?? undefined,
      });
      return action.kind === "status"
        ? action.status === "ACTIVE"
          ? "It's online again."
          : "Paused. The widget will stop answering."
        : "Done.";
    }
  }
}

export default function AskPage() {
  const { user } = useAuth();
  const router = useRouter();
  const [botId, setBotId] = useState<string | null>(null);
  const setup = useAsync(() => loadSetup(botId), [botId]);

  const [entries, setEntries] = useState<Entry[]>([]);
  const [input, setInput] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const nextId = useRef(1);
  const scroller = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Greet once the workspace is known, with a next step that fits it.
  const greeted = useRef(false);
  useEffect(() => {
    if (greeted.current || !setup.data) return;
    greeted.current = true;
    const first = user?.user.name.split(/\s+/)[0];
    const next = interpret("what should I do next", setup.data);
    setEntries([
      {
        id: nextId.current++,
        role: "intelli",
        reply: {
          text: `Hi${first ? ` ${first}` : ""}! I'm Intelli, your setup guide. Ask me how anything works, or tell me what to change. I'll show you the change before anything is saved.`,
        },
      },
      { id: nextId.current++, role: "intelli", reply: next },
    ]);
  }, [setup.data, user]);

  useEffect(() => {
    const element = scroller.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [entries]);

  const steps = useMemo(() => (setup.data ? setupSteps(setup.data) : []), [setup.data]);
  const progress = steps.filter((step) => step.done).length;

  function ask(text: string) {
    const question = text.trim();
    if (!question || !setup.data) return;
    setInput("");
    const reply = interpret(question, setup.data);
    setEntries((current) => [
      ...current,
      { id: nextId.current++, role: "user", text: question },
      { id: nextId.current++, role: "intelli", reply, state: reply.proposal ? "pending" : undefined },
    ]);
  }

  function settle(id: number, state: Entry["state"], note?: string) {
    setEntries((current) => {
      const next = current.map((entry) => (entry.id === id ? { ...entry, state } : entry));
      return note
        ? [...next, { id: nextId.current++, role: "intelli" as const, reply: { text: note } }]
        : next;
    });
  }

  async function approve(entry: Entry, proposal: Proposal) {
    if (!setup.data) return;
    setBusyId(entry.id);
    try {
      const message = await apply(proposal.action, setup.data.bots);
      settle(entry.id, "applied", message);
      notify.success(proposal.title);
      setup.reload();
      router.refresh();
    } catch (caught) {
      const reason = caught instanceof Error ? caught.message : "It didn't go through.";
      settle(entry.id, "failed", `That didn't work: ${reason}`);
    } finally {
      setBusyId(null);
      inputRef.current?.focus();
    }
  }

  return (
    <>
      <PageHeader
        title="Ask Intelli"
        description="Your AI setup guide. Get help, have features explained, and make changes with your approval."
        eyebrow={
          <>
            <Sparkles className="h-3.5 w-3.5" /> New
          </>
        }
        action={
          setup.data && setup.data.bots.length > 1 ? (
            <Select
              value={setup.data.bot?.id ?? ""}
              onChange={(event) => setBotId(event.target.value)}
              className="w-auto min-w-48"
              aria-label="Bot"
            >
              {setup.data.bots.map((bot) => (
                <option key={bot.id} value={bot.id}>
                  {bot.name}
                </option>
              ))}
            </Select>
          ) : undefined
        }
      />

      {setup.loading ? (
        <Loading label="Looking at your workspace" />
      ) : setup.error ? (
        <ErrorNote message={setup.error} onRetry={setup.reload} />
      ) : (
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
          <Card className="flex h-[calc(100dvh-210px)] min-h-[520px] flex-col overflow-hidden">
            <div ref={scroller} className="flex-1 space-y-4 overflow-y-auto bg-cream/60 p-5">
              {entries.map((entry) =>
                entry.role === "user" ? (
                  <div key={entry.id} className="flex items-end justify-end gap-2">
                    <p className="max-w-[78%] rounded-2xl rounded-br-md bg-clay-soft px-4 py-2.5 text-sm">
                      {entry.text}
                    </p>
                    <Avatar name={user?.user.name ?? "You"} />
                  </div>
                ) : (
                  <IntelliMessage
                    key={entry.id}
                    entry={entry}
                    busy={busyId === entry.id}
                    onAsk={ask}
                    onApprove={(proposal) => approve(entry, proposal)}
                    onDecline={() => settle(entry.id, "declined", "No problem, nothing was changed.")}
                  />
                ),
              )}
            </div>

            <div className="border-t border-line bg-surface p-3">
              {entries.length <= 2 && (
                <div className="mb-2 flex flex-wrap gap-1.5">
                  {STARTERS.map((starter) => (
                    <button
                      key={starter}
                      onClick={() => ask(starter)}
                      className="shrink-0 rounded-full border border-line bg-cream px-3 py-1 text-[12px] text-muted transition-colors hover:border-clay/40 hover:text-ink"
                    >
                      {starter}
                    </button>
                  ))}
                </div>
              )}
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  ask(input);
                }}
                className="flex items-center gap-2 rounded-full border border-line bg-cream py-1.5 pl-4 pr-1.5 focus-within:border-clay/50 focus-within:ring-2 focus-within:ring-clay/15"
              >
                <Sparkles className="h-4 w-4 shrink-0 text-clay" />
                <input
                  ref={inputRef}
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                  placeholder="Ask Intelli, or tell it what to change…"
                  aria-label="Message Intelli"
                  className="min-w-0 flex-1 bg-transparent text-sm placeholder:text-faint focus:outline-none"
                />
                <button
                  type="submit"
                  disabled={!input.trim()}
                  aria-label="Send"
                  className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-accent text-white transition-colors hover:bg-accent-strong disabled:opacity-40"
                >
                  <Send className="h-4 w-4" />
                </button>
              </form>
            </div>
          </Card>

          <Card className="h-fit">
            <div className="border-b border-line px-5 py-4">
              <p className="text-[12px] font-medium uppercase tracking-wider text-faint">Setup guide</p>
              <p className="mt-0.5 font-display text-lg font-semibold">
                {setup.data?.bot?.name ?? "Your first bot"}
              </p>
              <div className="mt-3 flex items-center gap-3">
                <div className="h-2 flex-1 overflow-hidden rounded-full bg-cream ring-1 ring-line">
                  <div
                    className="h-full rounded-full bg-accent transition-all"
                    style={{ width: `${(progress / Math.max(1, steps.length - 1)) * 100}%` }}
                  />
                </div>
                <span className="text-[12px] text-muted">
                  {progress}/{steps.length - 1}
                </span>
              </div>
            </div>
            <ol className="divide-y divide-line">
              {steps.map((step) => (
                <li key={step.id}>
                  <Link href={step.href} className="flex items-start gap-3 px-5 py-3 hover:bg-cream">
                    {step.done ? (
                      <CheckCircle2 className="mt-0.5 h-4.5 w-4.5 shrink-0 text-ok" />
                    ) : (
                      <Circle className="mt-0.5 h-4.5 w-4.5 shrink-0 text-faint" />
                    )}
                    <span className="min-w-0 flex-1">
                      <span className={cx("block text-[13.5px]", step.done ? "text-muted line-through" : "font-medium")}>
                        {step.label}
                      </span>
                      {!step.done && <span className="block text-[12px] text-muted">{step.hint}</span>}
                    </span>
                    {!step.done && <ArrowRight className="mt-1 h-3.5 w-3.5 shrink-0 text-faint" />}
                  </Link>
                </li>
              ))}
            </ol>
            <p className="flex items-start gap-2 border-t border-line px-5 py-3 text-[12px] text-muted">
              <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-accent" />
              Intelli never changes anything on its own. Every change is shown first and waits for
              your approval.
            </p>
          </Card>
        </div>
      )}
    </>
  );
}

function IntelliMessage({
  entry,
  busy,
  onAsk,
  onApprove,
  onDecline,
}: {
  entry: Entry;
  busy: boolean;
  onAsk: (text: string) => void;
  onApprove: (proposal: Proposal) => void;
  onDecline: () => void;
}) {
  const reply = entry.reply!;

  return (
    <div className="animate-rise flex items-start gap-2.5">
      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-gradient-to-br from-clay to-[#e0896d] text-white shadow-soft">
        <Sparkles className="h-4.5 w-4.5" />
      </span>
      <div className="min-w-0 max-w-[88%] space-y-2">
        <div className="rounded-2xl rounded-tl-md border border-line bg-surface px-4 py-3 text-sm shadow-soft">
          <p>{renderBold(reply.text)}</p>
          {reply.bullets && reply.bullets.length > 0 && (
            <ul className="mt-2 space-y-1">
              {reply.bullets.map((bullet) => (
                <li key={bullet} className="flex gap-2 text-[13.5px] text-ink/90">
                  <span className="mt-2 h-1 w-1 shrink-0 rounded-full bg-clay" />
                  <span>{bullet}</span>
                </li>
              ))}
            </ul>
          )}
          {reply.links && reply.links.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2">
              {reply.links.map((link) => (
                <Link
                  key={link.href + link.label}
                  href={link.href}
                  className="inline-flex items-center gap-1 rounded-lg bg-accent-soft px-2.5 py-1 text-[12.5px] font-medium text-accent hover:bg-accent hover:text-white"
                >
                  {link.label}
                  <ArrowRight className="h-3 w-3" />
                </Link>
              ))}
            </div>
          )}
        </div>

        {reply.proposal && (
          <ProposalCard
            proposal={reply.proposal}
            state={entry.state ?? "pending"}
            busy={busy}
            onApprove={() => onApprove(reply.proposal!)}
            onDecline={onDecline}
          />
        )}

        {reply.suggestions && reply.suggestions.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {reply.suggestions.map((suggestion) => (
              <button
                key={suggestion}
                onClick={() => onAsk(suggestion)}
                className="rounded-full border border-line bg-surface px-3 py-1 text-[12px] text-muted transition-colors hover:border-clay/40 hover:text-ink"
              >
                {suggestion}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function ProposalCard({
  proposal,
  state,
  busy,
  onApprove,
  onDecline,
}: {
  proposal: Proposal;
  state: NonNullable<Entry["state"]>;
  busy: boolean;
  onApprove: () => void;
  onDecline: () => void;
}) {
  return (
    <div
      className={cx(
        "overflow-hidden rounded-2xl border bg-surface shadow-soft",
        state === "applied" ? "border-ok/30" : state === "pending" ? "border-clay/40" : "border-line",
      )}
    >
      <div className="flex items-center justify-between gap-2 border-b border-line bg-cream px-4 py-2.5">
        <p className="text-[13px] font-semibold">{proposal.title}</p>
        <span
          className={cx(
            "rounded-full px-2 py-0.5 text-[11px] font-medium",
            state === "pending" && "bg-clay-soft text-clay",
            state === "applied" && "bg-ok-soft text-ok",
            state === "declined" && "bg-cream text-muted ring-1 ring-line",
            state === "failed" && "bg-bad-soft text-bad",
          )}
        >
          {state === "pending" ? "Needs your approval" : state === "applied" ? "Applied" : state === "declined" ? "Cancelled" : "Failed"}
        </span>
      </div>
      <div className="px-4 py-3">
        <p className="text-[12.5px] text-muted">{proposal.summary}</p>
        <dl className="mt-2 space-y-2">
          {proposal.changes.map((change) => (
            <div key={change.label} className="grid grid-cols-[110px_minmax(0,1fr)] gap-2 text-[13px]">
              <dt className="text-muted">{change.label}</dt>
              <dd className="min-w-0">
                <span className="flex flex-wrap items-center gap-1.5">
                  <Value value={change.from} colour={change.colour} struck />
                  <ArrowRight className="h-3 w-3 shrink-0 text-faint" />
                  <Value value={change.to} colour={change.colour} />
                </span>
              </dd>
            </div>
          ))}
        </dl>
        {state === "pending" && (
          <div className="mt-3 flex gap-2">
            <Button size="sm" onClick={onApprove} loading={busy}>
              {!busy && <Check className="h-3.5 w-3.5" />}
              Approve &amp; apply
            </Button>
            <Button size="sm" variant="ghost" onClick={onDecline} disabled={busy}>
              <X className="h-3.5 w-3.5" />
              Cancel
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}

function Value({ value, colour, struck }: { value: string; colour?: boolean; struck?: boolean }) {
  return (
    <span className={cx("inline-flex min-w-0 items-center gap-1.5 break-words", struck ? "text-faint line-through" : "font-medium")}>
      {colour && <span className="h-3.5 w-3.5 shrink-0 rounded-full ring-1 ring-line" style={{ background: value }} />}
      {value}
    </span>
  );
}

/** `**bold**` only: replies are fixed strings, never HTML, so nothing else is needed. */
function renderBold(text: string) {
  return text.split(/(\*\*[^*]+\*\*)/).map((part, index) =>
    part.startsWith("**") && part.endsWith("**") ? (
      <strong key={index} className="font-semibold">
        {part.slice(2, -2)}
      </strong>
    ) : (
      <span key={index}>{part}</span>
    ),
  );
}
