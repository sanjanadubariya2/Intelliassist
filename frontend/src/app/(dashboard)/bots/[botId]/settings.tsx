"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api, type Bot, type BotStatus } from "@/lib/api";
import {
  Button,
  Card,
  CardHeader,
  Field,
  Input,
  Modal,
  Select,
  Textarea,
} from "@/components/ui";

export function BotSettings({ bot, onSaved }: { bot: Bot; onSaved: () => void }) {
  const router = useRouter();

  const [form, setForm] = useState({
    name: bot.name,
    description: bot.description ?? "",
    status: bot.status,
    defaultLanguage: bot.defaultLanguage,
    systemPrompt: bot.systemPrompt ?? "",
  });
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(false);

    try {
      await api.bots.update(bot.id, {
        name: form.name,
        description: form.description || undefined,
        status: form.status,
        defaultLanguage: form.defaultLanguage,
        systemPrompt: form.systemPrompt || undefined,
      });
      setSaved(true);
      onSaved();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not save.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Card className="max-w-2xl">
        <CardHeader title="Settings" description="How this bot identifies and behaves." />
        <form onSubmit={save} className="space-y-4 p-5">
          <Field label="Name">
            <Input
              required
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
          </Field>

          <Field label="Description" hint="Optional. Never shown to visitors.">
            <Textarea
              rows={2}
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
            />
          </Field>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Status" hint="Paused bots stop answering the widget.">
              <Select
                value={form.status}
                onChange={(event) =>
                  setForm({ ...form, status: event.target.value as BotStatus })
                }
              >
                <option value="ACTIVE">Online</option>
                <option value="INACTIVE">Paused</option>
              </Select>
            </Field>

            <Field label="Default language">
              <Input
                value={form.defaultLanguage}
                onChange={(event) => setForm({ ...form, defaultLanguage: event.target.value })}
              />
            </Field>
          </div>

          <Field
            label="System prompt"
            hint="Stored with the bot. Not yet sent to the AI service — see the note below."
          >
            <Textarea
              rows={4}
              value={form.systemPrompt}
              onChange={(event) => setForm({ ...form, systemPrompt: event.target.value })}
              placeholder="You are a support assistant for Acme…"
            />
          </Field>

          {/*
           * Said plainly rather than hidden. The field is persisted, but the
           * AI service's query contract has no parameter to carry it, so it
           * has no effect on answers today. Showing an input that silently
           * does nothing is worse than admitting the gap.
           */}
          <p className="rounded-lg bg-warn-soft px-3 py-2 text-[12.5px] text-warn">
            The system prompt is saved but does not yet change answers: the AI
            service&apos;s query contract has no field for it. Wiring it through is a
            change to that service.
          </p>

          {error && <p className="text-[13px] text-bad">{error}</p>}
          {saved && !error && <p className="text-[13px] text-ok">Saved.</p>}

          <div className="pt-1">
            <Button type="submit" loading={busy}>
              Save changes
            </Button>
          </div>
        </form>
      </Card>

      <Card className="mt-4 max-w-2xl border-bad/25">
        <CardHeader title="Delete this bot" description="This cannot be undone." />
        <div className="flex flex-wrap items-center justify-between gap-3 p-5">
          <p className="text-[13px] text-muted">
            Deletes the bot, its conversations, its embed keys, and every document in its
            knowledge base.
          </p>
          <Button variant="danger" onClick={() => setConfirmDelete(true)}>
            Delete bot
          </Button>
        </div>
      </Card>

      <DeleteBotModal
        bot={bot}
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onDeleted={() => router.push("/bots")}
      />
    </>
  );
}

/**
 * Deleting a bot destroys its whole corpus, so it asks for the name to be typed
 * rather than settling for a second click on a button.
 */
function DeleteBotModal({
  bot,
  open,
  onClose,
  onDeleted,
}: {
  bot: Bot;
  open: boolean;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api.bots.remove(bot.id);
      onDeleted();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not delete the bot.");
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={`Delete ${bot.name}`}>
      <p className="text-[13px] text-muted">
        Its conversations, embed keys and indexed documents go with it. Type{" "}
        <strong className="text-ink">{bot.name}</strong> to confirm.
      </p>

      <div className="mt-4">
        <Input
          value={typed}
          onChange={(event) => setTyped(event.target.value)}
          placeholder={bot.name}
          autoFocus
        />
      </div>

      {error && <p className="mt-3 text-[13px] text-bad">{error}</p>}

      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose}>
          Cancel
        </Button>
        <Button variant="danger" disabled={typed !== bot.name} loading={busy} onClick={remove}>
          Delete permanently
        </Button>
      </div>
    </Modal>
  );
}
