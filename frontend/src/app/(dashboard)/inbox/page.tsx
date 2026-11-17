"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import {
  ArrowUpRight,
  CheckCircle2,
  Clock,
  FileText,
  Inbox as InboxIcon,
  RotateCcw,
  StickyNote,
  UserRound,
  UserRoundCheck,
  Users,
} from "lucide-react";
import {
  api,
  type Conversation,
  type ConversationNote,
  type InboxView,
  type Member,
  type Message,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useAsync, usePolling } from "@/lib/hooks";
import {
  Avatar,
  Badge,
  Button,
  Card,
  Empty,
  ErrorNote,
  Loading,
  LogoMark,
  PageHeader,
  ResolutionBadge,
  Select,
  Textarea,
  cx,
  formatDuration,
  formatRelative,
  formatTime,
  notify,
} from "@/components/ui";

const VIEWS: Array<{ id: InboxView; label: string; icon: React.ComponentType<{ className?: string }> }> = [
  { id: "MINE", label: "Assigned to me", icon: UserRoundCheck },
  { id: "UNASSIGNED", label: "Unassigned", icon: Users },
  { id: "OPEN", label: "All open", icon: InboxIcon },
  { id: "CLOSED", label: "Closed", icon: CheckCircle2 },
];

export default function InboxPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Inbox />
    </Suspense>
  );
}

/**
 * The human handoff inbox.
 *
 * One queue across every bot, because one team answers for all of them. The
 * selected view and conversation are in the URL, so "look at this one" is a
 * link that can be pasted into chat.
 */
function Inbox() {
  const router = useRouter();
  const params = useSearchParams();
  const view = (VIEWS.find((entry) => entry.id === params.get("view"))?.id ?? "OPEN") as InboxView;
  const selectedId = params.get("c");

  const summary = useAsync(() => api.handoff.summary(), []);
  const list = useAsync(() => api.handoff.list(view, 0, 50), [view]);
  const members = useAsync(() => api.members.list(), []);

  // A queue changes while someone watches it. Thirty seconds is fresh enough to
  // notice a new escalation without hammering the API.
  usePolling(() => {
    list.reload();
    summary.reload();
  }, 30_000, true);

  function go(next: { view?: InboxView; c?: string | null }) {
    const query = new URLSearchParams(params.toString());
    if (next.view) query.set("view", next.view);
    if (next.c === null) query.delete("c");
    else if (next.c) query.set("c", next.c);
    router.replace(`/inbox?${query}`, { scroll: false });
  }

  const items = list.data?.items ?? [];

  // Open the first conversation when nothing is selected, on wide screens
  // where the reading pane is visible anyway.
  useEffect(() => {
    if (!selectedId && items.length > 0 && window.matchMedia("(min-width: 1024px)").matches) {
      go({ c: items[0].id });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, items.length]);

  function refreshAll() {
    list.reload();
    summary.reload();
  }

  const counts: Record<InboxView, number | undefined> = {
    MINE: summary.data?.mine,
    UNASSIGNED: summary.data?.unassigned,
    OPEN: summary.data?.open,
    CLOSED: undefined,
  };

  return (
    <>
      <PageHeader
        title="Human Handoff Inbox"
        description="Conversations your assistant handed to a person. Assign them, reply in your own channels, and track them to resolution."
        eyebrow={
          <>
            <InboxIcon className="h-3.5 w-3.5" /> Handoff
          </>
        }
      />

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Waiting" value={summary.data?.open} icon={<InboxIcon className="h-4 w-4" />} />
        <Stat label="Unassigned" value={summary.data?.unassigned} icon={<Users className="h-4 w-4" />} tone="clay" />
        <Stat label="Resolved · 30 days" value={summary.data?.resolvedLast30Days} icon={<CheckCircle2 className="h-4 w-4" />} />
        <Stat
          label="Median time to resolve"
          value={
            summary.data
              ? summary.data.medianResolutionMinutes == null
                ? "—"
                : formatDuration(summary.data.medianResolutionMinutes)
              : undefined
          }
          icon={<Clock className="h-4 w-4" />}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[360px_minmax(0,1fr)]">
        <Card className={cx("flex flex-col overflow-hidden lg:h-[calc(100dvh-300px)] lg:min-h-[520px]", selectedId && "hidden lg:flex")}>
          <div className="grid grid-cols-2 gap-1 border-b border-line p-2">
            {VIEWS.map(({ id, label, icon: Icon }) => (
              <button
                key={id}
                onClick={() => go({ view: id, c: null })}
                className={cx(
                  "flex items-center gap-1.5 whitespace-nowrap rounded-lg px-2.5 py-1.5 text-[12.5px] transition-colors",
                  view === id ? "bg-clay-soft font-medium text-ink" : "text-muted hover:bg-cream hover:text-ink",
                )}
              >
                <Icon className="h-3.5 w-3.5" />
                {label}
                {counts[id] !== undefined && counts[id]! > 0 && (
                  <span className="rounded-full bg-surface px-1.5 text-[11px] ring-1 ring-line">{counts[id]}</span>
                )}
              </button>
            ))}
          </div>

          <div className="flex-1 overflow-y-auto">
            {list.loading ? (
              <Loading />
            ) : list.error ? (
              <div className="p-4">
                <ErrorNote message={list.error} onRetry={list.reload} />
              </div>
            ) : items.length === 0 ? (
              <Empty
                icon={<InboxIcon className="h-6 w-6" />}
                title={view === "CLOSED" ? "Nothing closed yet" : "Inbox zero"}
                description={
                  view === "CLOSED"
                    ? "Resolved handoffs appear here."
                    : "Nothing is waiting. Escalate a conversation, or assign one, and it lands here."
                }
              />
            ) : (
              <ul className="divide-y divide-line">
                {items.map((conversation) => (
                  <li key={conversation.id}>
                    <button
                      onClick={() => go({ c: conversation.id })}
                      className={cx(
                        "w-full px-4 py-3 text-left transition-colors",
                        selectedId === conversation.id ? "bg-clay-soft/60" : "hover:bg-cream",
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <p className="truncate text-[13.5px] font-medium">
                          {conversation.title ?? "Untitled conversation"}
                        </p>
                        <span className="shrink-0 text-[11.5px] text-faint">
                          {formatRelative(conversation.escalatedAt ?? conversation.lastMessageAt)}
                        </span>
                      </div>
                      <div className="mt-1 flex items-center gap-2 text-[12px] text-muted">
                        <span className="truncate">{conversation.botName}</span>
                        <span>·</span>
                        {conversation.assigneeName ? (
                          <span className="flex items-center gap-1 truncate">
                            <UserRound className="h-3 w-3" />
                            {conversation.assigneeName}
                          </span>
                        ) : (
                          <span className="text-clay">Unassigned</span>
                        )}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </Card>

        <div className={cx(!selectedId && "hidden lg:block")}>
          {selectedId ? (
            <Detail
              key={selectedId}
              conversationId={selectedId}
              members={members.data ?? []}
              onBack={() => go({ c: null })}
              onChanged={refreshAll}
            />
          ) : (
            <Card className="grid h-full min-h-[320px] place-items-center">
              <Empty
                icon={<InboxIcon className="h-6 w-6" />}
                title="Pick a conversation"
                description="Its transcript, owner and notes open here."
              />
            </Card>
          )}
        </div>
      </div>
    </>
  );
}

function Stat({
  label,
  value,
  icon,
  tone,
}: {
  label: string;
  value: number | string | undefined;
  icon: React.ReactNode;
  tone?: "clay";
}) {
  return (
    <Card className="flex items-center gap-3 px-4 py-3">
      <span
        className={cx(
          "grid h-9 w-9 place-items-center rounded-xl",
          tone === "clay" ? "bg-clay-soft text-clay" : "bg-sage-soft text-accent",
        )}
      >
        {icon}
      </span>
      <span>
        <span className="block text-[12px] text-muted">{label}</span>
        <span className="block font-display text-xl font-semibold leading-tight">
          {value === undefined ? "…" : value}
        </span>
      </span>
    </Card>
  );
}

interface Thread {
  conversation: Conversation;
  messages: Message[];
  notes: ConversationNote[];
}

function Detail({
  conversationId,
  members,
  onBack,
  onChanged,
}: {
  conversationId: string;
  members: Member[];
  onBack: () => void;
  onChanged: () => void;
}) {
  const { user } = useAuth();
  const { data, loading, error, reload } = useAsync<Thread>(async () => {
    const [conversation, messages, notes] = await Promise.all([
      api.conversations.get(conversationId),
      api.conversations.messages(conversationId),
      api.conversations.notes(conversationId),
    ]);
    return { conversation, messages, notes };
  }, [conversationId]);

  const [busy, setBusy] = useState<string | null>(null);

  async function act(label: string, run: () => Promise<unknown>, done: string) {
    setBusy(label);
    try {
      await run();
      notify.success(done);
      reload();
      onChanged();
    } catch (caught) {
      notify.failure(caught, "That did not work. Try again.");
    } finally {
      setBusy(null);
    }
  }

  if (loading) return <Card><Loading /></Card>;
  if (error) return <ErrorNote message={error} onRetry={reload} />;
  if (!data) return null;

  const { conversation, messages, notes } = data;
  const mine = conversation.assigneeId === user?.user.id;
  const waiting = conversation.resolution === "ESCALATED";

  return (
    <div className="grid grid-cols-1 gap-4 2xl:grid-cols-[minmax(0,1fr)_320px]">
      <Card className="flex flex-col overflow-hidden lg:h-[calc(100dvh-300px)] lg:min-h-[520px]">
        <div className="border-b border-line px-5 py-4">
          <button onClick={onBack} className="mb-2 text-[12.5px] text-muted hover:text-ink lg:hidden">
            ← Back to inbox
          </button>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 className="truncate font-display text-lg font-semibold">
                {conversation.title ?? "Untitled conversation"}
              </h2>
              <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-[12.5px] text-muted">
                <span>{conversation.botName}</span>
                <span>·</span>
                <span>
                  {conversation.visitorId ? `Visitor ${conversation.visitorId.slice(0, 10)}` : "Dashboard test"}
                </span>
                {conversation.escalatedAt && (
                  <>
                    <span>·</span>
                    <span>escalated {formatRelative(conversation.escalatedAt)}</span>
                  </>
                )}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <ResolutionBadge resolution={conversation.resolution} />
              <Link
                href={`/conversations/${conversation.id}`}
                className="rounded-lg p-1.5 text-faint hover:bg-cream hover:text-ink"
                title="Open full transcript page"
                aria-label="Open full transcript page"
              >
                <ArrowUpRight className="h-4 w-4" />
              </Link>
            </div>
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Select
              value={conversation.assigneeId ?? ""}
              disabled={busy !== null}
              aria-label="Assignee"
              className="w-auto min-w-44 py-1.5 text-[13px]"
              onChange={(event) => {
                const next = event.target.value || null;
                const name = members.find((member) => member.userId === next)?.name;
                act(
                  "assign",
                  () => api.handoff.assign(conversation.id, next),
                  next ? `Assigned to ${name ?? "teammate"}` : "Returned to the queue",
                );
              }}
            >
              <option value="">Unassigned</option>
              {members.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {member.name}
                  {member.userId === user?.user.id ? " (you)" : ""}
                </option>
              ))}
            </Select>

            {!mine && user && (
              <Button
                size="sm"
                variant="secondary"
                loading={busy === "take"}
                disabled={busy !== null}
                onClick={() => act("take", () => api.handoff.assign(conversation.id, user.user.id), "It's yours")}
              >
                <UserRoundCheck className="h-3.5 w-3.5" />
                Take it
              </Button>
            )}

            {waiting ? (
              <Button
                size="sm"
                loading={busy === "resolve"}
                disabled={busy !== null}
                onClick={() =>
                  act("resolve", () => api.conversations.resolve(conversation.id, "RESOLVED"), "Marked resolved")
                }
              >
                <CheckCircle2 className="h-3.5 w-3.5" />
                Resolve
              </Button>
            ) : (
              <Button
                size="sm"
                variant="secondary"
                loading={busy === "reopen"}
                disabled={busy !== null}
                onClick={() =>
                  act("reopen", () => api.conversations.resolve(conversation.id, "ESCALATED"), "Back in the inbox")
                }
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Reopen handoff
              </Button>
            )}
          </div>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto bg-cream/60 p-5">
          {messages.length === 0 && (
            <p className="py-8 text-center text-[13px] text-muted">No messages yet.</p>
          )}
          {messages.map((message) =>
            message.role === "USER" ? (
              <div key={message.id} className="flex justify-end">
                <div className="max-w-[80%]">
                  <p className="rounded-2xl rounded-br-md bg-clay-soft px-3.5 py-2.5 text-sm">{message.content}</p>
                  <p className="mt-1 text-right text-[11px] text-faint">Visitor · {formatTime(message.createdAt)}</p>
                </div>
              </div>
            ) : (
              <div key={message.id} className="flex items-start gap-2">
                <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-accent text-white">
                  <LogoMark className="h-3.5 w-3.5 text-white" />
                </span>
                <div className="max-w-[85%]">
                  <div className="rounded-2xl rounded-tl-md border border-line bg-surface px-3.5 py-2.5 text-sm whitespace-pre-wrap">
                    {message.content}
                    {message.citations.length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-1.5 border-t border-line pt-2">
                        {message.citations.map((citation) => (
                          <span
                            key={`${citation.documentId}-${citation.marker}`}
                            title={citation.snippet}
                            className="inline-flex max-w-[200px] items-center gap-1 rounded-md bg-clay-soft px-1.5 py-0.5 text-[11px]"
                          >
                            <FileText className="h-3 w-3 shrink-0 text-clay" />
                            <span className="truncate">{citation.filename}</span>
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                  <p className="mt-1 flex items-center gap-2 text-[11px] text-faint">
                    Assistant · {formatTime(message.createdAt)}
                    {message.grounded === false && <Badge tone="warn">Not grounded</Badge>}
                  </p>
                </div>
              </div>
            ),
          )}
        </div>
      </Card>

      <Notes conversationId={conversation.id} notes={notes} onAdded={reload} />
    </div>
  );
}

/** Internal notes: the team's working record of the handoff. Never shown to the visitor. */
function Notes({
  conversationId,
  notes,
  onAdded,
}: {
  conversationId: string;
  notes: ConversationNote[];
  onAdded: () => void;
}) {
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const text = body.trim();
    if (!text) return;
    setBusy(true);
    try {
      await api.conversations.addNote(conversationId, text);
      setBody("");
      onAdded();
    } catch (caught) {
      notify.failure(caught, "Could not add the note.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="flex h-fit flex-col">
      <div className="border-b border-line px-4 py-3">
        <p className="flex items-center gap-2 text-sm font-semibold">
          <StickyNote className="h-4 w-4 text-clay" /> Internal notes
        </p>
        <p className="text-[12px] text-muted">Only your team sees these.</p>
      </div>
      <div className="max-h-72 overflow-y-auto">
        {notes.length === 0 ? (
          <p className="px-4 py-6 text-center text-[12.5px] text-muted">
            No notes yet. Record what you promised the visitor, so whoever picks this up next knows.
          </p>
        ) : (
          <ul className="divide-y divide-line">
            {notes.map((note) => (
              <li key={note.id} className="flex gap-2.5 px-4 py-3">
                <Avatar name={note.authorName} className="h-7 w-7 text-[11px]" />
                <div className="min-w-0">
                  <p className="text-[12px] font-medium">
                    {note.authorName}{" "}
                    <span className="font-normal text-faint">· {formatRelative(note.createdAt)}</span>
                  </p>
                  <p className="mt-0.5 text-[13px] whitespace-pre-wrap">{note.body}</p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
      <form onSubmit={submit} className="border-t border-line p-3">
        <Textarea
          rows={2}
          value={body}
          maxLength={2000}
          onChange={(event) => setBody(event.target.value)}
          placeholder="Add a note for your team…"
          aria-label="Internal note"
        />
        <div className="mt-2 flex justify-end">
          <Button size="sm" type="submit" loading={busy} disabled={!body.trim()}>
            Add note
          </Button>
        </div>
      </form>
    </Card>
  );
}
