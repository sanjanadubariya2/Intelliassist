"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Bot as BotIcon, MoreVertical, Plus } from "lucide-react";
import { api, type Bot, type BotUsage } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Button,
  Card,
  Empty,
  ErrorNote,
  Field,
  Input,
  Loading,
  ConfirmModal,
  Modal,
  PageHeader,
  Textarea,
  cx,
  notify,
  formatDate,
  formatNumber,
} from "@/components/ui";

const PAGE_SIZE = 10;

interface Listing {
  bots: Bot[];
  total: number;
  usageByBot: Map<string, BotUsage>;
}

async function loadBots(page: number): Promise<Listing> {
  const result = await api.bots.list(page, PAGE_SIZE);

  // One usage call per bot on the page. Bounded by PAGE_SIZE rather than by the
  // total, so the cost does not grow with the number of bots a customer has.
  const usages = await Promise.all(result.items.map((bot) => api.usage.forBot(bot.id)));

  return {
    bots: result.items,
    total: result.totalItems,
    usageByBot: new Map(result.items.map((bot, index) => [bot.id, usages[index]])),
  };
}

export default function BotsPage() {
  const router = useRouter();
  const [page, setPage] = useState(0);
  const { data, loading, error, reload } = useAsync(() => loadBots(page), [page]);
  const [creating, setCreating] = useState(false);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<"ALL" | "ACTIVE" | "INACTIVE">("ALL");

  const bots = data?.bots ?? [];
  // Filters the page already loaded; the API has no name search.
  const visible = bots.filter(
    (bot) =>
      (status === "ALL" || bot.status === status) &&
      bot.name.toLowerCase().includes(query.trim().toLowerCase()),
  );
  const total = data?.total ?? 0;
  const firstShown = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const lastShown = Math.min(total, (page + 1) * PAGE_SIZE);

  return (
    <>
      <PageHeader
        title="Bots"
        description="Create and manage AI assistants for your business. Each has its own documents, widget and conversations."
        action={
          <Button onClick={() => setCreating(true)}>
            <Plus className="h-4 w-4" />
            Create bot
          </Button>
        }
      />

      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : bots.length === 0 ? (
        <Card>
          <Empty
            icon={<BotIcon className="h-7 w-7" />}
            title="No bots yet"
            description="A bot answers from the documents you give it, and nothing else."
            action={<Button onClick={() => setCreating(true)}>Create a bot</Button>}
          />
        </Card>
      ) : (
        <>
          <div className="mb-5 flex flex-wrap items-center gap-2.5">
            <label className="w-full max-w-[340px] flex-[1_1_240px]">
              <span className="sr-only">Filter bots</span>
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Filter by name"
              />
            </label>
            <div role="group" aria-label="Status" className="flex gap-1 rounded-xl bg-[#f3ece4] p-1">
              {(["ALL", "ACTIVE", "INACTIVE"] as const).map((value) => {
                const count = value === "ALL" ? bots.length : bots.filter((b) => b.status === value).length;
                const label = value === "ALL" ? "All" : value === "ACTIVE" ? "Online" : "Paused";
                return (
                  <button
                    key={value}
                    type="button"
                    aria-pressed={status === value}
                    onClick={() => setStatus(value)}
                    className={cx(
                      "min-h-9 rounded-[9px] px-3.5 text-[13px]",
                      status === value ? "bg-surface font-semibold text-ink shadow-sm" : "font-medium text-[#5f5249]",
                    )}
                  >
                    {label} · {count}
                  </button>
                );
              })}
            </div>
          </div>

          <div className="grid grid-cols-[repeat(auto-fill,minmax(320px,1fr))] gap-4">
            {visible.map((bot, index) => (
              <BotCard
                key={bot.id}
                bot={bot}
                usage={data?.usageByBot.get(bot.id)}
                tint={TINTS[index % TINTS.length]}
                onChanged={reload}
              />
            ))}
            <button
              type="button"
              onClick={() => setCreating(true)}
              className="flex min-h-[300px] flex-col items-center justify-center gap-2.5 rounded-2xl border-2 border-dashed border-[#e2d4c5] p-6 text-center text-[#4a3d35] transition-colors hover:border-clay/50 hover:bg-cream"
            >
              <span className="grid h-12 w-12 place-items-center rounded-full bg-accent-soft text-accent">
                <Plus className="h-5 w-5" />
              </span>
              <span className="text-[16px] font-semibold text-ink">Create a bot</span>
              <span className="max-w-[240px] text-[14px] text-muted">
                Give it a name and a few documents. You can test it before anyone sees it.
              </span>
            </button>
          </div>
          {visible.length === 0 && (
            <p className="mt-4 text-[14px] text-muted">No bots on this page match that filter.</p>
          )}

          <div className="mt-5 flex items-center justify-between">
            <p className="text-[13px] text-muted">
              Showing {firstShown} to {lastShown} of {total} bots
            </p>
            <div className="flex gap-1">
              <Button
                size="sm"
                variant="secondary"
                disabled={page === 0}
                onClick={() => setPage((current) => current - 1)}
              >
                Previous
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={lastShown >= total}
                onClick={() => setPage((current) => current + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}

      <CreateBotModal
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(botId) => router.push(`/bots/${botId}`)}
      />
    </>
  );
}

/** Initial-tile colours, cycled so neighbouring bots are easy to tell apart. */
const TINTS = [
  "bg-accent-soft text-accent",
  "bg-clay-soft text-clay-strong",
  "bg-sage-soft text-[#3e5a3c]",
];

function BotCard({
  bot,
  usage,
  tint,
  onChanged,
}: {
  bot: Bot;
  usage: BotUsage | undefined;
  tint: string;
  onChanged: () => void;
}) {
  const online = bot.status === "ACTIVE";
  const grounded =
    usage && usage.totalAnswers > 0
      ? `${Math.round((usage.groundedAnswers / usage.totalAnswers) * 100)}%`
      : "—";

  return (
    <article className="flex flex-col gap-4 rounded-2xl border border-line bg-surface p-5 shadow-soft">
      <div className="flex items-center gap-3">
        <span className={cx("grid h-12 w-12 shrink-0 place-items-center rounded-[14px] font-display text-[20px] font-semibold", tint)}>
          {bot.name.slice(0, 1).toUpperCase()}
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-[17px] font-semibold">
            <Link href={`/bots/${bot.id}`} className="hover:text-accent">
              {bot.name}
            </Link>
          </h2>
          <p className="text-[13px] text-muted">
            Language: {bot.defaultLanguage} · updated {formatDate(bot.updatedAt)}
          </p>
        </div>
        <span
          className={cx(
            "rounded-full px-2.5 py-0.5 text-[12px] font-semibold",
            online ? "bg-ok-soft text-ok" : "bg-warn-soft text-warn",
          )}
        >
          {online ? "Online" : "Paused"}
        </span>
        <RowMenu botId={bot.id} onChanged={onChanged} />
      </div>
      <p className="line-clamp-2 min-h-[42px] text-[14px] leading-normal text-[#4a3d35]">
        {bot.description ?? "No description yet."}
      </p>
      <dl className="grid grid-cols-3 gap-2 border-y border-[#f3ece4] py-3.5">
        <div>
          <dt className="text-[12px] text-muted">Conversations</dt>
          <dd className="mt-0.5 text-[16px] font-semibold">{formatNumber(usage?.totalConversations ?? 0)}</dd>
        </div>
        <div>
          <dt className="text-[12px] text-muted">Messages</dt>
          <dd className="mt-0.5 text-[16px] font-semibold">{formatNumber(usage?.totalMessages ?? 0)}</dd>
        </div>
        <div>
          <dt className="text-[12px] text-muted">Grounded</dt>
          <dd className="mt-0.5 text-[16px] font-semibold">{grounded}</dd>
        </div>
      </dl>
      <div className="flex gap-2">
        <Link
          href={`/playground?bot=${bot.id}`}
          className="inline-flex min-h-11 flex-1 items-center justify-center rounded-xl border border-[#e2d4c5] bg-surface text-sm font-semibold hover:bg-cream"
        >
          Test
        </Link>
        <Link
          href={`/bots/${bot.id}`}
          className="inline-flex min-h-11 flex-1 items-center justify-center rounded-xl border border-[#e2d4c5] bg-surface text-sm font-semibold hover:bg-cream"
        >
          Manage
        </Link>
      </div>
    </article>
  );
}

/** The card's overflow menu. Closes on any outside click. */
function RowMenu({ botId, onChanged }: { botId: string; onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const router = useRouter();

  return (
    <span className="relative inline-block" onClick={(event) => event.stopPropagation()}>
      <button
        onClick={() => setOpen((current) => !current)}
        aria-label="Bot actions"
        aria-expanded={open}
        className="grid h-10 w-10 place-items-center rounded-xl text-muted hover:bg-canvas hover:text-ink"
      >
        <MoreVertical className="h-4 w-4" />
      </button>

      {open && (
        <>
          <span className="fixed inset-0 z-10" onClick={() => setOpen(false)} aria-hidden />
          <span className="absolute right-0 z-20 mt-1 block w-44 overflow-hidden rounded-lg border border-line bg-surface py-1 text-left shadow-lg">
            {[
              { label: "Open", href: `/bots/${botId}` },
              { label: "Test in playground", href: `/playground?bot=${botId}` },
              { label: "KB Health", href: `/health?bot=${botId}` },
              { label: "Widget Studio", href: `/studio?bot=${botId}` },
              { label: "Embed code", href: `/studio?bot=${botId}&tab=install` },
            ].map((item) => (
              <button
                key={item.label}
                onClick={() => {
                  setOpen(false);
                  router.push(item.href);
                }}
                className="block w-full px-3 py-2 text-left text-[13px] hover:bg-canvas"
              >
                {item.label}
              </button>
            ))}
            <button
              onClick={() => {
                setOpen(false);
                setConfirming(true);
              }}
              className="block w-full border-t border-line px-3 py-2 text-left text-[13px] text-bad hover:bg-bad-soft"
            >
              Delete
            </button>
          </span>
        </>
      )}

      <ConfirmModal
        open={confirming}
        title="Delete this bot?"
        confirmLabel="Delete permanently"
        body="Its conversations, embed keys and every indexed document go with it. This cannot be undone."
        onClose={() => setConfirming(false)}
        onConfirm={async () => {
          try {
            await api.bots.remove(botId);
            notify.success("Bot deleted");
            onChanged();
          } catch (caught) {
            notify.failure(caught, "Could not delete the bot.");
          }
        }}
      />
    </span>
  );
}

function CreateBotModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (botId: string) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [language, setLanguage] = useState("en");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);

    try {
      const bot = await api.bots.create({
        name,
        description: description || undefined,
        defaultLanguage: language,
      });
      onCreated(bot.id);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not create the bot.");
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="New bot">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Name">
          <Input
            required
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Customer Support Bot"
          />
        </Field>

        <Field label="Description" hint="Optional. Never shown to visitors.">
          <Textarea
            rows={2}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="Handles customer support queries"
          />
        </Field>

        <Field label="Default language">
          <Input
            value={language}
            onChange={(event) => setLanguage(event.target.value)}
            placeholder="en"
          />
        </Field>

        {error && <p className="text-[13px] text-bad">{error}</p>}

        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            Create bot
          </Button>
        </div>
      </form>
    </Modal>
  );
}
