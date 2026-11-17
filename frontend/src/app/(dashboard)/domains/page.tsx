"use client";

import { useState } from "react";
import { Globe, Info } from "lucide-react";
import { api } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { WithBot } from "@/components/bot-picker";
import {
  Badge,
  Button,
  Card,
  Empty,
  ErrorNote,
  Input,
  Loading,
  formatDate,
  notify,
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
      description="Your widget only loads on websites listed here, so nobody can copy your embed code onto another site and spend your usage."
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

  return (
    <div className="flex flex-col gap-5">
      <AddDomain botId={botId} onAdded={reload} />

      <Card className="overflow-hidden">
        <div className="px-6 pb-2 pt-5">
          <h2 className="font-display text-[20px] font-semibold">Allowed domains</h2>
          <p className="mt-1 text-[13px] text-muted">
            Without at least one, every widget request is rejected with 401.
          </p>
        </div>

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
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[14px]">
              <thead>
                <tr className="border-b border-line text-left text-[12px] font-semibold text-muted">
                  <th className="px-6 py-3">Domain</th>
                  <th className="px-4 py-3">Added</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-6 py-3">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.map((domain) => (
                  <tr key={domain.id} className="border-b border-[#f3ece4] last:border-0">
                    <td className="px-6 py-3.5 font-semibold">{domain.domain}</td>
                    <td className="whitespace-nowrap px-4 py-3.5 text-muted">{formatDate(domain.createdAt)}</td>
                    <td className="px-4 py-3.5">
                      <Badge tone="ok">Allowed</Badge>
                    </td>
                    <td className="px-6 py-3.5 text-right">
                      <Button
                        size="sm"
                        variant="secondary"
                        aria-label={`Remove ${domain.domain}`}
                        onClick={async () => {
                          try {
                            await api.credentials.removeDomain(domain.id);
                            notify.success(`${domain.domain} removed`);
                            reload();
                          } catch (caught) {
                            notify.failure(caught, "Could not remove the domain.");
                          }
                        }}
                      >
                        Remove
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <section className="flex items-start gap-3.5 rounded-2xl bg-sage-soft px-5 py-4">
        <Info className="mt-0.5 h-5 w-5 shrink-0 text-[#3e5a3c]" aria-hidden />
        <p className="text-[14px] leading-normal text-[#2f4a2d]">
          Scheme, port and path are ignored: <strong>acme.com</strong> covers every page on it. Add{" "}
          <strong>localhost</strong> while you test on your own machine.
        </p>
      </section>
    </div>
  );
}

/** Inline, as in the design: adding a domain is the main thing done here. */
function AddDomain({ botId, onAdded }: { botId: string; onAdded: () => void }) {
  const [domain, setDomain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.credentials.addDomain(botId, { domain });
      notify.success(`${domain} allowed`);
      setDomain("");
      onAdded();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not add the domain.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="p-6">
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
        <label className="flex min-w-0 flex-[1_1_320px] flex-col gap-1.5">
          <span className="text-[14px] font-semibold">Allow a domain</span>
          <Input
            required
            value={domain}
            onChange={(event) => setDomain(event.target.value)}
            placeholder="shop.example.com"
          />
        </label>
        <Button type="submit" loading={busy}>
          Add domain
        </Button>
      </form>
      {error && <p className="mt-2 text-[13px] text-bad">{error}</p>}
    </Card>
  );
}
