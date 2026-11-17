"use client";

import { useState } from "react";
import { Check, Copy, Globe, KeyRound, Trash2 } from "lucide-react";
import { api, type CreatedApiKey } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Field,
  Input,
  Loading,
  Modal,
  formatRelative,
  notify,
} from "@/components/ui";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function BotEmbed({ botId }: { botId: string }) {
  const keys = useAsync(() => api.credentials.listKeys(botId), [botId]);
  const domains = useAsync(() => api.credentials.listDomains(botId), [botId]);

  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const [naming, setNaming] = useState(false);

  const liveKey = keys.data?.find((key) => key.active);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader
          title="Install"
          description="Paste this before the closing </body> tag of your site."
        />
        <div className="p-5">
          <Snippet
            botKey={created?.rawKey ?? liveKey?.keyPrefix ?? "ia_your_key_here"}
            masked={!created}
          />
          {!created && (
            <p className="mt-3 text-[12.5px] text-muted">
              Only the key&apos;s prefix is shown — the rest is stored as a hash and
              cannot be retrieved. Create a new key to get a full one.
            </p>
          )}
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Allowed domains"
          description="The key only works on these sites. Without one, every request is rejected."
          action={<AddDomain botId={botId} onAdded={domains.reload} />}
        />
        {domains.loading ? (
          <Loading />
        ) : domains.error ? (
          <div className="p-5">
            <ErrorNote message={domains.error} onRetry={domains.reload} />
          </div>
        ) : !domains.data?.length ? (
          <Empty
            icon={<Globe className="h-7 w-7" />}
            title="No domains yet"
            description="Add the domain your widget will run on. Until then the key answers nobody."
          />
        ) : (
          <ul className="divide-y divide-line">
            {domains.data.map((domain) => (
              <li key={domain.id} className="flex items-center justify-between px-5 py-3">
                <span className="flex items-center gap-2.5 text-sm">
                  <Globe className="h-4 w-4 text-faint" />
                  {domain.domain}
                </span>
                <button
                  onClick={async () => {
                    try {
                      await api.credentials.removeDomain(domain.id);
                      notify.success(`${domain.domain} removed`);
                      domains.reload();
                    } catch (caught) {
                      notify.failure(caught, "Could not remove the domain.");
                    }
                  }}
                  aria-label={`Remove ${domain.domain}`}
                  className="rounded-md p-1.5 text-faint hover:bg-bad-soft hover:text-bad"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card>
        <CardHeader
          title="API keys"
          description="Public by design — they ship in your page source. Domains are what keep them safe."
          action={<Button onClick={() => setNaming(true)}>New key</Button>}
        />
        {keys.loading ? (
          <Loading />
        ) : keys.error ? (
          <div className="p-5">
            <ErrorNote message={keys.error} onRetry={keys.reload} />
          </div>
        ) : !keys.data?.length ? (
          <Empty
            icon={<KeyRound className="h-7 w-7" />}
            title="No keys yet"
            description="Create one to embed this bot on your website."
            action={<Button onClick={() => setNaming(true)}>Create a key</Button>}
          />
        ) : (
          <ul className="divide-y divide-line">
            {keys.data.map((key) => (
              <li key={key.id} className="flex items-center justify-between gap-3 px-5 py-3">
                <div className="min-w-0">
                  <p className="flex items-center gap-2 text-sm font-medium">
                    {key.name}
                    {!key.active && <Badge tone="bad">Revoked</Badge>}
                  </p>
                  <p className="truncate font-mono text-[12px] text-muted">
                    {key.keyPrefix}··········
                  </p>
                  <p className="text-[12px] text-faint">
                    {key.lastUsedAt
                      ? `Last used ${formatRelative(key.lastUsedAt)}`
                      : "Never used"}
                  </p>
                </div>
                {key.active && (
                  <Button
                    size="sm"
                    variant="danger"
                    onClick={async () => {
                      try {
                        await api.credentials.revokeKey(key.id);
                        notify.success(`Revoked ${key.name}`);
                        keys.reload();
                      } catch (caught) {
                        notify.failure(caught, "Could not revoke the key.");
                      }
                    }}
                  >
                    Revoke
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>

      <NewKeyModal
        botId={botId}
        open={naming}
        onClose={() => setNaming(false)}
        onCreated={(key) => {
          setCreated(key);
          setNaming(false);
          keys.reload();
        }}
      />

      <RevealKeyModal created={created} onClose={() => setCreated(null)} />
    </div>
  );
}

function Snippet({ botKey, masked }: { botKey: string; masked: boolean }) {
  const snippet = `<script
  src="${API_URL}/widget/intelliassist.js"
  data-bot-key="${botKey}${masked ? "··········" : ""}"
  data-api-url="${API_URL}"
></script>`;

  return (
    <div className="relative">
      <pre className="overflow-x-auto rounded-xl bg-[#1d2a24] px-4 py-3.5 pr-24 text-[12.5px] leading-relaxed text-[#e9f0ea]">
        <code>{snippet}</code>
      </pre>
      <div className="absolute right-2 top-2">
        <CopyButton value={snippet} />
      </div>
    </div>
  );
}

function CopyButton({ value, label }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <Button
      size="sm"
      variant="secondary"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          setTimeout(() => setCopied(false), 1600);
        } catch {
          // Clipboard needs a secure context; over plain http it is absent.
          // The text is on screen and selectable, so this is not a dead end.
        }
      }}
    >
      {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
      {label ?? (copied ? "Copied" : "Copy")}
    </Button>
  );
}

function AddDomain({ botId, onAdded }: { botId: string; onAdded: () => void }) {
  const [open, setOpen] = useState(false);
  const [domain, setDomain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.credentials.addDomain(botId, { domain });
      setDomain("");
      setOpen(false);
      onAdded();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not add the domain.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button size="sm" variant="secondary" onClick={() => setOpen(true)}>
        Add domain
      </Button>
      <Modal open={open} onClose={() => setOpen(false)} title="Allow a domain">
        <form onSubmit={submit} className="space-y-4">
          <Field
            label="Domain"
            hint="The scheme, port and path are ignored — acme.com covers every page on it."
          >
            <Input
              required
              autoFocus
              value={domain}
              onChange={(event) => setDomain(event.target.value)}
              placeholder="acme.com"
            />
          </Field>
          {error && <p className="text-[13px] text-bad">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={busy}>
              Add domain
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
}

function NewKeyModal({
  botId,
  open,
  onClose,
  onCreated,
}: {
  botId: string;
  open: boolean;
  onClose: () => void;
  onCreated: (key: CreatedApiKey) => void;
}) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onCreated(await api.credentials.createKey(botId, { name }));
      setName("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not create the key.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="New API key">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Name" hint="Where it will be used, so you can tell keys apart later.">
          <Input
            required
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Marketing site"
          />
        </Field>
        {error && <p className="text-[13px] text-bad">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            Create key
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * Shows a new key exactly once.
 *
 * The server stores only a SHA-256 hash, so this really is the only chance to
 * copy it — the modal says so plainly rather than letting the user discover it
 * after closing.
 */
function RevealKeyModal({
  created,
  onClose,
}: {
  created: CreatedApiKey | null;
  onClose: () => void;
}) {
  return (
    <Modal open={created !== null} onClose={onClose} title="Copy your key now">
      {created && (
        <>
          <p className="text-[13px] text-warn">{created.warning}</p>
          <div className="mt-3 flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate rounded-lg bg-canvas px-3 py-2 font-mono text-[12.5px]">
              {created.rawKey}
            </code>
            <CopyButton value={created.rawKey} />
          </div>
          <div className="mt-4 flex justify-end">
            <Button onClick={onClose}>Done</Button>
          </div>
        </>
      )}
    </Modal>
  );
}
