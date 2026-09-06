# IntelliAssist widget

The embeddable chat bubble that customers drop onto their own website. One
script tag, no dependencies, no build step.

```html
<script
  src="https://api.example.com/widget/intelliassist.js"
  data-api-key="ia_live_xxxxxxxxxxxx"
  data-api-url="https://api.example.com"
  data-title="Acme Assistant"
  data-color="#7C5AED"
  data-position="bottom-right"
  data-greeting="Hi there! 👋 How can I help you today?"
></script>
```

| Attribute | Default | Notes |
| --- | --- | --- |
| `data-api-key` | — | **Required.** The bot's public key. `data-bot-key` is accepted as an alias. |
| `data-api-url` | origin of the script | The Spring Boot API. Never the Python service. |
| `data-title` | `Assistant` | Shown in the header. |
| `data-color` | `#7C5AED` | Accent colour. |
| `data-position` | `bottom-right` | Or `bottom-left`. |
| `data-greeting` | `Hi there! 👋 …` | First message, shown before any request. |
| `data-placeholder` | `Type your message...` | Composer placeholder. |
| `data-streaming` | `true` | `false` falls back to one request per answer. |

`window.IntelliAssist.open() / .close() / .toggle()` let a host page drive the
widget from its own button.

## Building

TypeScript, bundled by esbuild into one dependency-free file.

```bash
npm install
npm run build        # -> dist/intelliassist.js  (~13 kB)
npm run typecheck
```

The backend's Docker build runs this in its own Node stage and bakes the result
into the jar, so `GET /widget/intelliassist.js` serves exactly what `widget/src`
compiles to.

## Running the demo

```bash
npm run build
npx serve .          # then open http://localhost:3000/demo/
```

The demo page carries deliberately hostile CSS — red buttons, green dashed
borders, triple line-height — because the widget renders in a shadow root and
none of it should land. It is the fastest way to see that isolation holds.

The page's host must be registered as an allowed domain for the bot, or every
request comes back `401`. For local work that means adding `localhost`.

## Design notes

**Iframe isolation.** This runs inside a customer's page whose CSS we have
never seen. A shadow root stops their selectors matching ours, but the widget
still inherits the document's font metrics, any `!important` rule on `*`, and
global print styles. A separate document inherits none of it. The frame is
sandboxed without `allow-same-origin`, so it cannot reach the host page's DOM,
cookies or storage either — the isolation runs both ways. This is why the demo
page's CSS is as hostile as it is.

**The parent does the networking.** This is forced by security, not preference.
The backend authorises a widget by matching `Origin` against the bot's allowed
domains, and a `srcdoc` iframe has an opaque origin — its requests would arrive
as `Origin: null` and be rejected. So the frame renders and the host page
fetches, with `postMessage` between them. Neither side can check `event.origin`
(it is `"null"`), so both verify the sender *window identity* instead.

**No framework.** A chat bubble is a few hundred lines of DOM. Shipping a
framework to every visitor of every customer site to avoid writing them would
cost more than the feature.

**`fetch`, not `EventSource`.** `EventSource` cannot issue a POST and cannot set
`X-Bot-Key`, so SSE frames are parsed by hand out of the response body.

**Conversation continuity.** SSE events are a pass-through from the Python AI
service, which has no concept of a conversation, so a streamed reply cannot
carry its conversation id in the body. The API returns it as the
`X-Conversation-Id` response header, which arrives before the first token and is
present even if the stream then fails. The header must stay listed in the public
CORS `exposedHeaders` or the browser hides it and every conversation becomes one
message long — see `theConversationHeaderIsExposedToBrowsers` in
`PublicApiIntegrationTest`.

**Visitor id.** A random value in `localStorage`, so a visitor's messages group
into one conversation across page loads. It identifies nobody and falls back to
a per-page value when storage is blocked.

## Security

The bot key is public by design — it ships in the page source of every customer
site. It is not what makes the widget safe. The boundary is server-side:

- the key is checked against the bot's registered **allowed domains** using the
  `Origin` header, so a copied key does not work from another site;
- the key names its own bot, so a caller cannot address a different one;
- it carries no dashboard authority — it cannot list bots or mint keys;
- requests are rate limited per key;
- revoking a key takes effect on the next request.

The widget only ever talks to the Spring Boot API. It has no address for the
Python service, which is not reachable from the internet.
