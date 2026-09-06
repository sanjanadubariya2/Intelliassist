"use client";

import { useEffect, useState } from "react";
import {
  HelpCircle,
  MessageCircle,
  MessageSquare,
  Send,
  Sparkles,
  X,
} from "lucide-react";
import { api, type Bot, type LauncherIcon, type WidgetPosition } from "@/lib/api";
import { WithBot } from "@/components/bot-picker";
import {
  Button,
  Card,
  Field,
  Input,
  Textarea,
  cx,
} from "@/components/ui";

/** The swatches from the design, plus whatever the customer types. */
const SWATCHES = [
  "#7C5AED",
  "#EF4444",
  "#F97316",
  "#F59E0B",
  "#22C55E",
  "#14B8A6",
  "#3B82F6",
  "#8B5CF6",
];

const POSITIONS: Array<{ value: WidgetPosition; label: string }> = [
  { value: "BOTTOM_RIGHT", label: "Bottom Right" },
  { value: "BOTTOM_LEFT", label: "Bottom Left" },
  { value: "TOP_RIGHT", label: "Top Right" },
  { value: "TOP_LEFT", label: "Top Left" },
];

const ICONS: Array<{ value: LauncherIcon; node: React.ReactNode }> = [
  { value: "CHAT", node: <MessageCircle className="h-4.5 w-4.5" /> },
  { value: "MESSAGE", node: <MessageSquare className="h-4.5 w-4.5" /> },
  { value: "HELP", node: <HelpCircle className="h-4.5 w-4.5" /> },
  { value: "SPARKLE", node: <Sparkles className="h-4.5 w-4.5" /> },
];

const SECTIONS = [
  { id: "appearance", label: "Appearance" },
  { id: "messages", label: "Messages" },
  { id: "behavior", label: "Behavior" },
  { id: "advanced", label: "Advanced" },
] as const;

type SectionId = (typeof SECTIONS)[number]["id"];

export default function CustomizationPage() {
  return (
    <WithBot
      title="Customize your bot"
      description="Change the appearance and behavior of your widget."
    >
      {(bot, reload) => <Editor key={bot.id} bot={bot} onSaved={reload} />}
    </WithBot>
  );
}

function Editor({ bot, onSaved }: { bot: Bot; onSaved: () => void }) {
  const [section, setSection] = useState<SectionId>("appearance");

  const [draft, setDraft] = useState({
    primaryColor: bot.primaryColor,
    widgetPosition: bot.widgetPosition,
    welcomeMessage: bot.welcomeMessage,
    fallbackMessage: bot.fallbackMessage,
    launcherIcon: bot.launcherIcon,
  });

  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A saved confirmation should fade rather than sit there through the next
  // three edits implying they were saved too.
  useEffect(() => {
    if (!saved) return;
    const timer = setTimeout(() => setSaved(false), 2500);
    return () => clearTimeout(timer);
  }, [saved]);

  const dirty =
    draft.primaryColor !== bot.primaryColor ||
    draft.widgetPosition !== bot.widgetPosition ||
    draft.welcomeMessage !== bot.welcomeMessage ||
    draft.fallbackMessage !== bot.fallbackMessage ||
    draft.launcherIcon !== bot.launcherIcon;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await api.bots.updateAppearance(bot.id, draft);
      setSaved(true);
      onSaved();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not save.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[160px_minmax(0,1fr)_320px]">
      {/* Section rail. Horizontal on narrow screens rather than a tall stack
          that pushes the actual form below the fold. */}
      <nav className="flex gap-1 overflow-x-auto lg:flex-col">
        {SECTIONS.map((entry) => (
          <button
            key={entry.id}
            onClick={() => setSection(entry.id)}
            className={cx(
              "whitespace-nowrap rounded-lg px-3 py-2 text-left text-sm transition-colors",
              section === entry.id
                ? "bg-accent-soft font-medium text-accent"
                : "text-muted hover:bg-canvas hover:text-ink",
            )}
          >
            {entry.label}
          </button>
        ))}
      </nav>

      <Card className="p-5">
        {section === "appearance" && (
          <div className="space-y-5">
            <Field label="Primary Color" hint="Used for the launcher, header and your messages.">
              <div className="flex items-center gap-2">
                <Input
                  value={draft.primaryColor}
                  onChange={(event) =>
                    setDraft({ ...draft, primaryColor: event.target.value })
                  }
                  className="w-32 font-mono"
                  aria-label="Primary colour hex"
                />
                <div className="flex flex-wrap gap-1.5">
                  {SWATCHES.map((colour) => (
                    <button
                      key={colour}
                      type="button"
                      onClick={() => setDraft({ ...draft, primaryColor: colour })}
                      aria-label={colour}
                      title={colour}
                      style={{ background: colour }}
                      className={cx(
                        "h-7 w-7 rounded-md border transition-transform hover:scale-110",
                        draft.primaryColor.toUpperCase() === colour
                          ? "border-ink ring-2 ring-ink/20"
                          : "border-line",
                      )}
                    />
                  ))}
                </div>
              </div>
            </Field>

            <Field label="Position">
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {POSITIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => setDraft({ ...draft, widgetPosition: option.value })}
                    className={cx(
                      "rounded-lg border px-3 py-2 text-[13px] transition-colors",
                      draft.widgetPosition === option.value
                        ? "border-accent bg-accent-soft font-medium text-accent"
                        : "border-line text-muted hover:border-accent/40 hover:text-ink",
                    )}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </Field>

            <Field
              label="Launcher Icon"
              hint="A fixed set, not a URL — an arbitrary image would be a request from your visitors' browsers to a host we do not control."
            >
              <div className="flex gap-2">
                {ICONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => setDraft({ ...draft, launcherIcon: option.value })}
                    aria-label={option.value}
                    className={cx(
                      "grid h-10 w-10 place-items-center rounded-lg border transition-colors",
                      draft.launcherIcon === option.value
                        ? "border-accent bg-accent-soft text-accent"
                        : "border-line text-muted hover:border-accent/40 hover:text-ink",
                    )}
                  >
                    {option.node}
                  </button>
                ))}
              </div>
            </Field>
          </div>
        )}

        {section === "messages" && (
          <div className="space-y-5">
            <Field
              label="Welcome Message"
              hint="Shown before the visitor has typed anything, so it is the one line every visitor reads."
            >
              <Textarea
                rows={2}
                maxLength={500}
                value={draft.welcomeMessage}
                onChange={(event) =>
                  setDraft({ ...draft, welcomeMessage: event.target.value })
                }
              />
            </Field>

            <Field
              label="Fallback Message"
              hint="Shown when nothing in your documents matches. Wording only — the AI service decides when to decline."
            >
              <Textarea
                rows={3}
                maxLength={500}
                value={draft.fallbackMessage}
                onChange={(event) =>
                  setDraft({ ...draft, fallbackMessage: event.target.value })
                }
              />
            </Field>
          </div>
        )}

        {section === "behavior" && (
          <div className="space-y-4">
            <p className="text-[13px] text-muted">
              Language and status live with the bot itself rather than its appearance.
            </p>
            <div className="rounded-lg border border-line p-4 text-[13px]">
              <p>
                <span className="text-muted">Default language:</span>{" "}
                <span className="font-medium">{bot.defaultLanguage}</span>
              </p>
              <p className="mt-1">
                <span className="text-muted">Status:</span>{" "}
                <span className="font-medium">
                  {bot.status === "ACTIVE" ? "Online" : "Maintenance"}
                </span>
              </p>
              <p className="mt-3 text-muted">
                Change these on the bot&apos;s own Settings tab.
              </p>
            </div>
          </div>
        )}

        {section === "advanced" && (
          <div className="space-y-4">
            <Field
              label="System prompt"
              hint="Saved with the bot, but it does not change answers yet."
            >
              <Textarea rows={4} value={bot.systemPrompt ?? ""} readOnly />
            </Field>
            <p className="rounded-lg bg-warn-soft px-3 py-2 text-[12.5px] text-warn">
              The AI service&apos;s query contract has no field for a system prompt, so this
              value is stored and never sent. Wiring it through is a change to that service.
              Edit it on the bot&apos;s Settings tab.
            </p>
          </div>
        )}

        {error && <p className="mt-4 text-[13px] text-bad">{error}</p>}

        <div className="mt-6 flex items-center gap-3 border-t border-line pt-4">
          <Button onClick={save} loading={busy} disabled={!dirty}>
            Save changes
          </Button>
          {saved && !error && <span className="text-[13px] text-ok">Saved.</span>}
          {dirty && !saved && (
            <span className="text-[13px] text-muted">Unsaved changes</span>
          )}
        </div>
      </Card>

      <Preview draft={draft} name={bot.name} />
    </div>
  );
}

/**
 * A live preview of the widget.
 *
 * Rendered here rather than embedding the real widget: the real one needs an
 * API key and an allowed domain, and neither belongs in a settings screen. This
 * mirrors the widget's own markup closely enough to judge a colour by.
 */
function Preview({
  draft,
  name,
}: {
  draft: {
    primaryColor: string;
    widgetPosition: WidgetPosition;
    welcomeMessage: string;
    launcherIcon: LauncherIcon;
  };
  name: string;
}) {
  const icon = ICONS.find((entry) => entry.value === draft.launcherIcon)?.node;
  const bottom = draft.widgetPosition.startsWith("BOTTOM");
  const right = draft.widgetPosition.endsWith("RIGHT");

  // Guards the preview against a half-typed hex, which would otherwise render
  // as an invalid style and flash black.
  const colour = /^#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$/.test(draft.primaryColor)
    ? draft.primaryColor
    : "#7C5AED";

  return (
    <Card className="overflow-hidden">
      <div className="border-b border-line px-4 py-3">
        <p className="text-[13px] font-medium">Preview</p>
      </div>

      <div
        className={cx(
          "relative flex h-[420px] flex-col gap-3 bg-canvas p-4",
          bottom ? "justify-end" : "justify-start",
          right ? "items-end" : "items-start",
        )}
      >
        <div className="w-full max-w-[260px] overflow-hidden rounded-xl bg-surface shadow-lg">
          <div
            className="flex items-center gap-2 px-3 py-2.5 text-white"
            style={{ background: colour }}
          >
            <span className="grid h-5 w-5 place-items-center rounded bg-white/20 text-[10px]">
              {name.slice(0, 1).toUpperCase()}
            </span>
            <span className="flex-1 truncate text-[13px] font-semibold">{name}</span>
            <X className="h-3.5 w-3.5 opacity-80" />
          </div>

          <div className="space-y-2 bg-canvas/60 p-3">
            <p className="max-w-[85%] rounded-xl rounded-bl-sm border border-line bg-surface px-2.5 py-2 text-[12px]">
              {draft.welcomeMessage || "…"}
            </p>
            <p
              className="ml-auto max-w-[85%] rounded-xl rounded-br-sm px-2.5 py-2 text-[12px] text-white"
              style={{ background: colour }}
            >
              Do you offer international shipping?
            </p>
          </div>

          <div className="flex items-center gap-2 border-t border-line px-3 py-2">
            <span className="flex-1 text-[12px] text-faint">Type your message…</span>
            <Send className="h-3.5 w-3.5 text-faint" />
          </div>
        </div>

        <span
          className="grid h-11 w-11 place-items-center rounded-full text-white shadow-lg"
          style={{ background: colour }}
        >
          {icon}
        </span>
      </div>
    </Card>
  );
}
