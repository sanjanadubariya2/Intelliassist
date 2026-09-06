"use client";

import { useState } from "react";
import { Globe, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { WithBot } from "@/components/bot-picker";
import {
  Button,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Field,
  Input,
  Loading,
  Modal,
  formatDate,
} from "@/components/ui";

/**
 * The domains a bot's widget key is allowed to run on.
 *
 * This is the control that makes a public API key safe: the key ships in every
 * customer's page source, and what stops a copied key working elsewhere is the
 * server checking the request's Origin against this list.
 */
export default function DomainsPage() {
  return (
    <WithBot
      title="Domains"
      description="Where each bot's widget is allowed to run."
    >
      {(bot) => <DomainList key={bot.id} botId={bot.id} />}
    </WithBot>
  );
}

function DomainList({ botId }: { botId: string }) {
  const { data, loading, error, reload } = useAsync(
    () => api.credentials.listDomains(botId),
    [botId],
  );
  const [adding, setAdding] = useState(false);

  return (
    <>
      <Card>
        <CardHeader
          title="Allowed domains"
          description="Without at least one, every widget request is rejected with 401."
          action={<Button onClick={() => setAdding(true)}>Add domain</Button>}
        />

        {loading ? (
          <Loading />
        ) : error ? (
          <div className="p-5">
            <ErrorNote message={error} onRetry={reload} />
          </div>
        ) : !data?.length ? (
          <Empty
            icon={<Globe className="h-7 w-7" />}
            title="No domains yet"
            description="Add the domain your widget will run on. Until then the key answers nobody."
            action={<Button onClick={() => setAdding(true)}>Add a domain</Button>}
          />
        ) : (
          <ul className="divide-y divide-line">
            {data.map((domain) => (
              <li key={domain.id} className="flex items-center justify-between px-5 py-3.5">
                <div className="flex items-center gap-2.5">
                  <Globe className="h-4 w-4 text-faint" />
                  <div>
                    <p className="text-sm font-medium">{domain.domain}</p>
                    <p className="text-[12px] text-muted">
                      Added {formatDate(domain.createdAt)}
                    </p>
                  </div>
                </div>
                <button
                  onClick={async () => {
                    await api.credentials.removeDomain(domain.id);
                    reload();
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

      <AddDomain
        botId={botId}
        open={adding}
        onClose={() => setAdding(false)}
        onAdded={reload}
      />
    </>
  );
}

function AddDomain({
  botId,
  open,
  onClose,
  onAdded,
}: {
  botId: string;
  open: boolean;
  onClose: () => void;
  onAdded: () => void;
}) {
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
      onClose();
      onAdded();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not add the domain.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="Allow a domain">
      <form onSubmit={submit} className="space-y-4">
        <Field
          label="Domain"
          hint="Scheme, port and path are ignored — acme.com covers every page on it."
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
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={busy}>
            Add domain
          </Button>
        </div>
      </form>
    </Modal>
  );
}
