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

  const bots = data?.bots ?? [];
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
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-[12px] text-muted">
                  <th className="px-5 py-3 font-medium">Name</th>
                  <th className="px-5 py-3 font-medium">Description</th>
                  <th className="px-5 py-3 font-medium">Status</th>
                  <th className="px-5 py-3 font-medium">Messages</th>
                  <th className="px-5 py-3 font-medium">Updated</th>
                  <th className="px-5 py-3" />
                </tr>
              </thead>
              <tbody>
                {bots.map((bot) => (
                  <tr
                    key={bot.id}
                    onClick={() => router.push(`/bots/${bot.id}`)}
                    className="cursor-pointer border-b border-line last:border-0 hover:bg-canvas"
                  >
                    <td className="px-5 py-3.5">
                      {/* A real link inside the clickable row, so the keyboard
                          and middle-click both work. */}
                      <Link
                        href={`/bots/${bot.id}`}
                        className="font-medium hover:text-accent"
                        onClick={(event) => event.stopPropagation()}
                      >
                        {bot.name}
                      </Link>
                    </td>
                    <td className="max-w-xs px-5 py-3.5">
                      <span className="block truncate text-muted">
                        {bot.description ?? "—"}
                      </span>
                    </td>
                    <td className="px-5 py-3.5">
                      <span
                        className={cx(
                          "flex items-center gap-1.5 text-[12.5px]",
                          bot.status === "ACTIVE" ? "text-ok" : "text-warn",
                        )}
                      >
                        <span
                          className={cx(
                            "h-1.5 w-1.5 rounded-full",
                            bot.status === "ACTIVE" ? "bg-ok" : "bg-warn",
                          )}
                        />
                        {bot.status === "ACTIVE" ? "Online" : "Maintenance"}
                      </span>
                    </td>
                    <td className="px-5 py-3.5 text-muted">
                      {formatNumber(data?.usageByBot.get(bot.id)?.totalMessages ?? 0)}
                    </td>
                    <td className="px-5 py-3.5 text-muted">{formatDate(bot.updatedAt)}</td>
                    <td className="px-5 py-3.5 text-right">
                      <RowMenu botId={bot.id} onChanged={reload} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex items-center justify-between border-t border-line px-5 py-3">
            <p className="text-[12.5px] text-muted">
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
        </Card>
      )}

      <CreateBotModal
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(botId) => router.push(`/bots/${botId}`)}
      />
    </>
  );
}

/** The row's overflow menu. Closes on any outside click. */
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
        className="rounded-md p-1.5 text-faint hover:bg-canvas hover:text-ink"
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
