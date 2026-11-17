"use client";

import { use, useState } from "react";
import Link from "next/link";
import { ArrowLeft, FileText, Inbox, StickyNote } from "lucide-react";
import {
  api,
  type Conversation,
  type ConversationNote,
  type ConversationResolution,
  type Member,
  type Message,
} from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  ResolutionBadge,
  Select,
  Textarea,
  cx,
  formatDate,
  formatRelative,
  formatTime,
  notify,
} from "@/components/ui";

interface Transcript {
  conversation: Conversation;
  messages: Message[];
  notes: ConversationNote[];
  members: Member[];
}

export default function ConversationPage({
  params,
}: {
  params: Promise<{ conversationId: string }>;
}) {
  const { conversationId } = use(params);

  const { data, loading, error, reload } = useAsync<Transcript>(async () => {
    const [conversation, messages, notes, members] = await Promise.all([
      api.conversations.get(conversationId),
      api.conversations.messages(conversationId),
      api.conversations.notes(conversationId),
      api.members.list(),
    ]);
    return { conversation, messages, notes, members };
  }, [conversationId]);

  if (loading) return <Loading />;
  if (error) return <ErrorNote message={error} onRetry={reload} />;
  if (!data) return null;

  const { conversation, messages, notes, members } = data;

  return (
    <>
      <nav aria-label="Breadcrumb" className="mb-4 flex items-center gap-1.5 text-[14px]">
        <ArrowLeft className="h-3.5 w-3.5 text-muted" aria-hidden />
        <Link href="/conversations" className="font-medium text-accent hover:underline">
          Conversations
        </Link>
        <span className="truncate text-muted">/ {conversation.title ?? "Untitled conversation"}</span>
      </nav>

      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="min-w-0 flex-[1_1_360px] font-display text-[30px] font-semibold leading-tight tracking-tight sm:text-[32px]">
          {conversation.title ?? "Untitled conversation"}
        </h1>

        <div className="flex flex-wrap items-center gap-2">
          <ResolutionBadge resolution={conversation.resolution} />
          <AssigneeControl
            conversation={conversation}
            members={members}
            onChanged={reload}
          />
          <ResolutionControls
            conversationId={conversationId}
            current={conversation.resolution}
            onChanged={reload}
          />
          {conversation.status === "ACTIVE" && (
            <Button
              size="sm"
              variant="secondary"
              onClick={async () => {
                try {
                  await api.conversations.close(conversationId);
                  notify.success("Conversation closed");
                  reload();
                } catch (caught) {
                  notify.failure(caught, "Could not close the conversation.");
                }
              }}
            >
              Close
            </Button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
        <Card>
          <div className="space-y-5 p-6">
            <p className="text-center text-[12px] text-muted">
              {formatDate(conversation.startedAt)} · started{" "}
              {conversation.visitorId ? "from the website widget" : "from the dashboard"}
            </p>
            {messages.map((message) =>
              message.role === "USER" ? (
                <div key={message.id} className="flex justify-end">
                  <div className="max-w-[75%]">
                    <p className="mb-1 text-right text-[12px] font-semibold text-muted">
                      Customer · {formatTime(message.createdAt)}
                    </p>
                    <p className="rounded-2xl rounded-br-[4px] bg-accent px-4 py-3 text-[15px] leading-normal text-white">
                      {message.content}
                    </p>
                  </div>
                </div>
              ) : (
                <div key={message.id} className="max-w-[85%] space-y-2">
                  <p className="text-[12px] font-semibold text-muted">{conversation.botName}</p>
                  <p
                    className={cx(
                      "whitespace-pre-wrap rounded-2xl rounded-bl-[4px] border px-4 py-3 text-[15px] leading-relaxed",
                      message.grounded === false ? "border-[#f0dcae] bg-[#fffbf2]" : "border-line bg-cream",
                    )}
                  >
                    {message.content}
                  </p>

                  {message.citations.length > 0 && (
                    <div className="space-y-1.5">
                      {message.citations.map((citation) => (
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

                  {/* Operator detail: how the answer was produced. Present on
                      the dashboard DTO and deliberately absent from the widget's. */}
                  <p className="flex flex-wrap items-center gap-2 text-[12px] text-muted">
                    <span>{formatTime(message.createdAt)}</span>
                    {message.grounded === false && <Badge tone="warn">Not grounded</Badge>}
                    {message.grounded === true && <Badge tone="ok">Grounded</Badge>}
                    {message.model && <span>· {message.model}</span>}
                    {message.latencyMs != null && <span>· {message.latencyMs} ms</span>}
                  </p>
                </div>
              ),
            )}
          </div>
        </Card>

        <aside className="flex flex-col gap-5">
          <Card className="p-5">
            <h2 className="mb-3.5 text-[12px] font-semibold uppercase tracking-[0.06em] text-muted">Details</h2>
            <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2.5 text-[14px]">
              <dt className="text-muted">Bot</dt>
              <dd className="truncate font-medium">
                <Link href={`/bots/${conversation.botId}`} className="text-accent hover:underline">
                  {conversation.botName}
                </Link>
              </dd>
              <dt className="text-muted">Visitor</dt>
              <dd className="truncate font-medium">
                {conversation.visitorId ? conversation.visitorId.slice(0, 10) : "Dashboard test"}
              </dd>
              <dt className="text-muted">Started</dt>
              <dd className="font-medium">{formatDate(conversation.startedAt)}</dd>
              <dt className="text-muted">Messages</dt>
              <dd className="font-medium">{conversation.messageCount}</dd>
              <dt className="text-muted">Grounded</dt>
              <dd className="font-medium">
                {messages.filter((m) => m.grounded === true).length} of{" "}
                {messages.filter((m) => m.role !== "USER").length} replies
              </dd>
              <dt className="text-muted">Status</dt>
              <dd>
                <ResolutionBadge resolution={conversation.resolution} />
              </dd>
            </dl>
          </Card>
          <Notes conversationId={conversationId} notes={notes} onAdded={reload} />
        </aside>
      </div>
    </>
  );
}

/** Sets how the conversation turned out. Does not close it. */
function ResolutionControls({
  conversationId,
  current,
  onChanged,
}: {
  conversationId: string;
  current: ConversationResolution;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);

  async function set(resolution: ConversationResolution) {
    setBusy(true);
    try {
      await api.conversations.resolve(conversationId, resolution);
      notify.success(
        resolution === "ESCALATED"
          ? "Escalated: it's in the Handoff Inbox"
          : resolution === "RESOLVED"
            ? "Marked resolved"
            : "Reopened",
      );
      onChanged();
    } catch (caught) {
      notify.failure(caught, "Could not update the conversation.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex gap-1">
      {current !== "RESOLVED" && (
        <Button size="sm" variant="secondary" loading={busy} onClick={() => set("RESOLVED")}>
          Mark resolved
        </Button>
      )}
      {current !== "ESCALATED" && (
        <Button size="sm" variant="secondary" loading={busy} onClick={() => set("ESCALATED")}>
          Escalate
        </Button>
      )}
      {current !== "UNRESOLVED" && (
        <Button size="sm" variant="ghost" loading={busy} onClick={() => set("UNRESOLVED")}>
          Reopen
        </Button>
      )}
    </div>
  );
}

/**
 * Who on the team owns this conversation.
 *
 * Assigning a conversation that was never escalated escalates it, so it shows
 * up in the Handoff Inbox; the backend does that, this only reflects it.
 */
function AssigneeControl({
  conversation,
  members,
  onChanged,
}: {
  conversation: Conversation;
  members: Member[];
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);

  return (
    <div className="flex items-center gap-1.5">
      <Select
        value={conversation.assigneeId ?? ""}
        disabled={busy}
        aria-label="Assignee"
        className="w-auto py-1.5 text-[13px]"
        onChange={async (event) => {
          const next = event.target.value || null;
          setBusy(true);
          try {
            await api.handoff.assign(conversation.id, next);
            notify.success(next ? "Assigned" : "Returned to the queue");
            onChanged();
          } catch (caught) {
            notify.failure(caught, "Could not assign the conversation.");
          } finally {
            setBusy(false);
          }
        }}
      >
        <option value="">Unassigned</option>
        {members.map((member) => (
          <option key={member.userId} value={member.userId}>
            {member.name}
          </option>
        ))}
      </Select>
      {conversation.resolution === "ESCALATED" && (
        <Link
          href={`/inbox?c=${conversation.id}`}
          title="Open in the Handoff Inbox"
          aria-label="Open in the Handoff Inbox"
          className="rounded-lg p-1.5 text-muted hover:bg-cream hover:text-ink"
        >
          <Inbox className="h-4 w-4" />
        </Link>
      )}
    </div>
  );
}

/**
 * Internal notes.
 *
 * Staff commentary, often about the visitor. Never returned by the public API
 * and never sent to the AI service, which is worth saying on the screen so
 * nobody writes one assuming otherwise.
 */
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
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const text = body.trim();
    if (!text) return;

    setBusy(true);
    setError(null);
    try {
      await api.conversations.addNote(conversationId, text);
      setBody("");
      onAdded();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not add the note.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="flex h-fit flex-col">
      <CardHeader
        title="Internal notes"
        description="Only your team sees these. Never sent to the assistant."
      />

      <div className="max-h-80 flex-1 overflow-y-auto">
        {notes.length === 0 ? (
          <Empty icon={<StickyNote className="h-6 w-6" />} title="No notes yet" />
        ) : (
          <ul className="divide-y divide-line">
            {notes.map((note) => (
              <li key={note.id} className="px-5 py-3">
                <p className="text-[13px] whitespace-pre-wrap">{note.body}</p>
                <p className="mt-1 text-[11.5px] text-faint">
                  {note.authorName} · {formatRelative(note.createdAt)}
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>

      <form onSubmit={submit} className="border-t border-line p-4">
        <Textarea
          rows={3}
          value={body}
          maxLength={2000}
          onChange={(event) => setBody(event.target.value)}
          placeholder="Add internal note…"
          aria-label="Internal note"
        />
        {error && <p className="mt-2 text-[12.5px] text-bad">{error}</p>}
        <div className={cx("mt-2 flex justify-end")}>
          <Button size="sm" type="submit" loading={busy} disabled={!body.trim()}>
            Add Note
          </Button>
        </div>
      </form>
    </Card>
  );
}
