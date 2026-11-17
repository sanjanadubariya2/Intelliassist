"use client";

import { useEffect, useState } from "react";
import {
  Code2,
  HelpCircle,
  Laptop,
  MessageCircle,
  MessageSquare,
  Paintbrush,
  Send,
  Smartphone,
  Sparkles,
  Type,
  X,
} from "lucide-react";
import { api, type Bot, type LauncherIcon, type WidgetPosition } from "@/lib/api";
import { BotEmbed } from "@/app/(dashboard)/bots/[botId]/embed";
import { Button, Card, Field, Input, Tabs, Textarea, cx, notify } from "@/components/ui";

/** Swatches that sit well on the cream canvas, plus whatever the customer types. */
const SWATCHES = [
  "#1F4B3B",
  "#C0583A",
  "#7C5AED",
  "#2563EB",
  "#0F766E",
  "#D97706",
  "#DB2777",
  "#111827",
];

const POSITIONS: Array<{ value: WidgetPosition; label: string }> = [
  { value: "BOTTOM_RIGHT", label: "Bottom right" },
  { value: "BOTTOM_LEFT", label: "Bottom left" },
  { value: "TOP_RIGHT", label: "Top right" },
  { value: "TOP_LEFT", label: "Top left" },
];

const ICONS: Array<{ value: LauncherIcon; label: string; node: React.ReactNode }> = [
  { value: "CHAT", label: "Chat bubble", node: <MessageCircle className="h-4.5 w-4.5" /> },
  { value: "MESSAGE", label: "Message", node: <MessageSquare className="h-4.5 w-4.5" /> },
  { value: "HELP", label: "Help", node: <HelpCircle className="h-4.5 w-4.5" /> },
  { value: "SPARKLE", label: "Sparkle", node: <Sparkles className="h-4.5 w-4.5" /> },
];

export type StudioTab = "appearance" | "messages" | "install";

const TABS = [
  { id: "appearance" as const, label: <><Paintbrush className="h-4 w-4" /> Appearance</> },
  { id: "messages" as const, label: <><Type className="h-4 w-4" /> Messages</> },
  { id: "install" as const, label: <><Code2 className="h-4 w-4" /> Install</> },
];

const HEX = /^#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$/;

type Draft = {
  primaryColor: string;
  widgetPosition: WidgetPosition;
  welcomeMessage: string;
  fallbackMessage: string;
  launcherIcon: LauncherIcon;
};

/**
 * Widget Studio: style the chatbot, see it live, and get the embed code.
 *
 * One screen for what used to be two, because the order people do it in is
 * "make it look right, then put it on the site", and the preview should stay
 * in view the whole way through.
 */
export function WidgetStudio({
  bot,
  onSaved,
  initialTab = "appearance",
}: {
  bot: Bot;
  onSaved: () => void;
  initialTab?: StudioTab;
}) {
  const [tab, setTab] = useState<StudioTab>(initialTab);
  const [draft, setDraft] = useState<Draft>(() => fromBot(bot));
  const [busy, setBusy] = useState(false);

  // When the saved bot changes underneath (after a save and reload), the draft
  // follows it. Without this a second save compared against stale values.
  useEffect(() => setDraft(fromBot(bot)), [bot]);

  const saved = fromBot(bot);
  const dirty = (Object.keys(saved) as Array<keyof Draft>).some((key) => draft[key] !== saved[key]);
  const colourValid = HEX.test(draft.primaryColor);

  async function save() {
    if (!colourValid) {
      notify.error("The colour must be a hex value like #1F4B3B.");
      return;
    }
    setBusy(true);
    try {
      await api.bots.updateAppearance(bot.id, draft);
      notify.success("Widget saved. Live sites pick it up on their next page load.");
      onSaved();
    } catch (caught) {
      notify.failure(caught, "Could not save the widget.");
    } finally {
      setBusy(false);
    }
  }

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));

  return (
    <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.25fr)]">
      <div className="min-w-0">
        <Tabs tabs={TABS} value={tab} onChange={setTab} />

        {tab === "install" ? (
          <BotEmbed botId={bot.id} />
        ) : (
          <Card className="p-5">
            {tab === "appearance" && (
              <div className="space-y-6">
                <Field
                  label="Brand colour"
                  hint="Used for the launcher, the header and your visitors' messages."
                  error={colourValid ? undefined : "Use a hex colour like #1F4B3B"}
                >
                  <div className="flex flex-wrap items-center gap-3">
                    <div className="flex items-center gap-2">
                      <input
                        type="color"
                        value={colourValid && draft.primaryColor.length === 7 ? draft.primaryColor : "#1f4b3b"}
                        onChange={(event) => set("primaryColor", event.target.value.toUpperCase())}
                        aria-label="Pick a colour"
                        className="h-9 w-10 cursor-pointer rounded-lg border border-line bg-surface p-1"
                      />
                      <Input
                        value={draft.primaryColor}
                        onChange={(event) => set("primaryColor", event.target.value)}
                        className="w-28 font-mono"
                        aria-label="Colour hex"
                        maxLength={7}
                      />
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      {SWATCHES.map((colour) => (
                        <button
                          key={colour}
                          type="button"
                          onClick={() => set("primaryColor", colour)}
                          aria-label={`Use ${colour}`}
                          title={colour}
                          style={{ background: colour }}
                          className={cx(
                            "h-8 w-8 rounded-full border-2 transition-transform hover:scale-110",
                            draft.primaryColor.toUpperCase() === colour
                              ? "border-surface ring-2 ring-ink/40"
                              : "border-surface ring-1 ring-line",
                          )}
                        />
                      ))}
                    </div>
                  </div>
                </Field>

                <Field label="Position on the page">
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                    {POSITIONS.map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => set("widgetPosition", option.value)}
                        className={cx(
                          "rounded-xl border px-3 py-2 text-[13px] transition-colors",
                          draft.widgetPosition === option.value
                            ? "border-clay bg-clay-soft font-medium text-ink"
                            : "border-line text-muted hover:border-clay/40 hover:text-ink",
                        )}
                      >
                        {option.label}
                      </button>
                    ))}
                  </div>
                </Field>

                <Field
                  label="Launcher icon"
                  hint="A fixed set rather than an image URL: an arbitrary image would be a request from your visitors' browsers to a host nobody vetted."
                >
                  <div className="flex gap-2">
                    {ICONS.map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => set("launcherIcon", option.value)}
                        aria-label={option.label}
                        title={option.label}
                        className={cx(
                          "grid h-11 w-11 place-items-center rounded-xl border transition-colors",
                          draft.launcherIcon === option.value
                            ? "border-clay bg-clay-soft text-clay"
                            : "border-line text-muted hover:border-clay/40 hover:text-ink",
                        )}
                      >
                        {option.node}
                      </button>
                    ))}
                  </div>
                </Field>
              </div>
            )}

            {tab === "messages" && (
              <div className="space-y-5">
                <Field
                  label="Welcome message"
                  hint="Shown before the visitor types anything, so it is the one line every visitor reads."
                >
                  <Textarea
                    rows={2}
                    maxLength={500}
                    value={draft.welcomeMessage}
                    onChange={(event) => set("welcomeMessage", event.target.value)}
                  />
                </Field>
                <Field
                  label="Fallback message"
                  hint="Shown when nothing in your documents matches. Wording only: the AI service decides when to decline."
                >
                  <Textarea
                    rows={3}
                    maxLength={500}
                    value={draft.fallbackMessage}
                    onChange={(event) => set("fallbackMessage", event.target.value)}
                  />
                </Field>
              </div>
            )}

            <div className="mt-6 flex flex-wrap items-center gap-3 border-t border-line pt-4">
              <Button onClick={save} loading={busy} disabled={!dirty}>
                Save widget
              </Button>
              {dirty && (
                <>
                  <Button variant="ghost" onClick={() => setDraft(saved)}>
                    Discard
                  </Button>
                  <span className="text-[13px] text-muted">Unsaved changes</span>
                </>
              )}
              {!dirty && (
                <button
                  type="button"
                  onClick={() => setTab("install")}
                  className="text-[13px] font-medium text-clay hover:underline"
                >
                  Get the embed code →
                </button>
              )}
            </div>
          </Card>
        )}
      </div>

      <div className="xl:sticky xl:top-20 xl:self-start">
        <Preview draft={draft} name={bot.name} />
      </div>
    </div>
  );
}

function fromBot(bot: Bot): Draft {
  return {
    primaryColor: bot.primaryColor,
    widgetPosition: bot.widgetPosition,
    welcomeMessage: bot.welcomeMessage,
    fallbackMessage: bot.fallbackMessage,
    launcherIcon: bot.launcherIcon,
  };
}

/**
 * A live preview of the widget.
 *
 * Rendered here rather than embedding the real widget: the real one needs an
 * API key and an allowed domain, and neither belongs in a settings screen. It
 * mirrors the widget's own layout closely enough to judge a colour by, and the
 * launcher opens and closes it the way it does on a site.
 */
function Preview({ draft, name }: { draft: Draft; name: string }) {
  const [open, setOpen] = useState(true);
  const [device, setDevice] = useState<"desktop" | "phone">("desktop");
  const [showFallback, setShowFallback] = useState(false);

  const icon = ICONS.find((entry) => entry.value === draft.launcherIcon)?.node;
  const bottom = draft.widgetPosition.startsWith("BOTTOM");
  const right = draft.widgetPosition.endsWith("RIGHT");
  // Guards against a half-typed hex, which would otherwise flash black.
  const colour = HEX.test(draft.primaryColor) ? draft.primaryColor : "#1F4B3B";

  const launcher = (
    <button
      type="button"
      onClick={() => setOpen((value) => !value)}
      aria-label={open ? "Close preview chat" : "Open preview chat"}
      className="grid h-14 w-14 shrink-0 place-items-center rounded-full text-white shadow-lift transition-transform hover:scale-105"
      style={{ background: colour }}
    >
      {open ? <X className="h-5 w-5" /> : icon}
    </button>
  );

  return (
    <section aria-label="Live preview" className="overflow-hidden rounded-[20px] border border-line bg-[#f4ece2]">
      <div className="flex items-center gap-2 border-b border-line px-5 py-3.5">
        <span className="flex gap-1.5" aria-hidden>
          <span className="h-2.5 w-2.5 rounded-full bg-[#e2d4c5]" />
          <span className="h-2.5 w-2.5 rounded-full bg-[#e2d4c5]" />
          <span className="h-2.5 w-2.5 rounded-full bg-[#e2d4c5]" />
        </span>
        <span className="ml-2 rounded-full bg-[#f3ece4] px-2.5 py-0.5 text-[12px] font-semibold text-[#5f5249] ring-1 ring-line">
          your-website.com
        </span>
        <span className="ml-auto flex items-center gap-2 text-[13px] font-medium">
          <span className="h-2 w-2 rounded-full bg-ok" /> Live preview
        </span>
        <div className="flex rounded-lg bg-surface p-0.5 ring-1 ring-line">
          {(["desktop", "phone"] as const).map((option) => (
            <button
              key={option}
              type="button"
              onClick={() => setDevice(option)}
              aria-label={`${option} preview`}
              aria-pressed={device === option}
              className={cx(
                "grid h-8 w-8 place-items-center rounded-md transition-colors",
                device === option ? "bg-cream text-ink shadow-sm" : "text-muted hover:text-ink",
              )}
            >
              {option === "desktop" ? <Laptop className="h-3.5 w-3.5" /> : <Smartphone className="h-3.5 w-3.5" />}
            </button>
          ))}
        </div>
      </div>

      <div className="p-5">
        <div
          className={cx(
            "relative mx-auto flex flex-col gap-3.5 overflow-hidden p-5 transition-all",
            device === "phone"
              ? "h-[600px] w-[300px] rounded-[32px] bg-surface/60 ring-1 ring-line"
              : "h-[600px] w-full rounded-xl",
            bottom ? "justify-end" : "justify-start",
            right ? "items-end" : "items-start",
          )}
        >
          {/* A sketch of the host page, so the widget is seen in context. */}
          <div className="pointer-events-none absolute inset-x-5 top-5 max-w-[420px] space-y-3 opacity-60" aria-hidden>
            <div className="h-5 w-3/5 rounded-md bg-[#e2d4c5]" />
            <div className="h-3 w-[90%] rounded-md bg-[#ebe0d3]" />
            <div className="h-3 w-4/5 rounded-md bg-[#ebe0d3]" />
            <div className="mt-2 h-40 rounded-xl bg-[#ebe0d3]" />
          </div>

          {!bottom && launcher}

          {open && (
            <div className="animate-rise relative w-full max-w-[340px] overflow-hidden rounded-[18px] bg-surface shadow-lift">
              <div className="flex items-center gap-2.5 px-4 py-3.5 text-white" style={{ background: colour }}>
                <span className="grid h-[34px] w-[34px] place-items-center rounded-[10px] bg-white/20 text-[14px] font-semibold">
                  {name.slice(0, 1).toUpperCase()}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[15px] font-semibold">{name}</span>
                  <span className="block text-[12px] opacity-90">Usually replies instantly</span>
                </span>
              </div>

              <div className="space-y-2.5 bg-[#fffdfa] p-4">
                <p className="max-w-[88%] rounded-[14px] rounded-bl-[4px] bg-[#f6f0e9] px-3 py-2.5 text-[13px] leading-normal">
                  {draft.welcomeMessage || "…"}
                </p>
                <p
                  className="ml-auto max-w-[85%] rounded-[14px] rounded-br-[4px] px-3 py-2.5 text-[13px] leading-normal text-white"
                  style={{ background: colour }}
                >
                  {showFallback ? "Do you sell gift cards?" : "Do you deliver across India?"}
                </p>
                <p className="max-w-[88%] rounded-[14px] rounded-bl-[4px] bg-[#f6f0e9] px-3 py-2.5 text-[13px] leading-normal">
                  {showFallback
                    ? draft.fallbackMessage || "…"
                    : "Yes! We deliver across India within 3–7 business days."}
                </p>
              </div>

              <div className="flex items-center gap-2 border-t border-line px-4 py-3">
                <span className="flex-1 text-[13px] text-muted">Type your question…</span>
                <Send className="h-3.5 w-3.5" style={{ color: colour }} />
              </div>
            </div>
          )}

          {bottom && launcher}
        </div>

        <label className="mt-3 flex cursor-pointer items-center justify-center gap-2 text-[12px] text-muted">
          <input
            type="checkbox"
            checked={showFallback}
            onChange={(event) => setShowFallback(event.target.checked)}
            className="accent-[#c0583a]"
          />
          Preview the fallback reply
        </label>
      </div>
    </section>
  );
}
