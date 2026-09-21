# Toy Store catalog App

This read-only MCP App displays `show_toy_catalog` results and calls the existing
`list_toys` tool to refresh or filter by name prefix. The host uses its authorized
MCP connection; the page does not receive or store a bearer token. Catalog strings
are rendered as text, and errors or connection/context changes clear previous
results. Clients without Apps support can use the tool's readable text result.

## Language selection

Successful `show_toy_catalog` and `list_toys` results carry a server-selected
`locale` alongside `summary` and `toys`. The server matches the authenticated
account's locale to the application's supported translations: `en-US`, `de-DE`,
or `pt-BR`. The App translates its headings, controls, status/error messages,
“Added” label, document title, accessibility labels, and footer from that value.
It leaves authored toy names and server-formatted prices and dates unchanged;
the browser does not reformat them.

The initial waiting view uses English. Older results without `locale` and valid
but unsupported locale tags fall back to English; malformed locale fields fail
result validation and leave catalog data cleared. Each valid fresh result can
select a new language, but host/browser preferences and tool arguments never
select the authenticated account's locale. HTTP `Accept-Language` controls MCP
catalog metadata separately. A host locale or time-zone change still clears the
view and requires reopening it, rather than reusing previously returned data.

## Source and packaged HTML

`catalog.html` is the source template, `catalog.mjs` is the display/controller,
and `catalog-entry.mjs` connects the official Apps SDK. The generated
[`../src/main/resources/mcp/apps/catalog.html`](../src/main/resources/mcp/apps/catalog.html)
is checked in with the SDK and exact third-party license notices inline. It loads
no CDN or external assets. Normal Maven builds, Docker builds, and application
runtime require **no Node.js**; the checked-in HTML is portable.

## Optional regeneration

Run these commands from the Toy Store repository root. The current reviewed
generator requires **Node.js 26.5.0 on macOS ARM64**, Apps SDK **2.0.0**, and
Rolldown **1.2.9**. Supply the already-installed, reviewed dependency checkout
containing `package.json`, `package-lock.json`, and `node_modules`. The builder
verifies pinned manifest and complete package-file hashes, including the loaded
native bundler. Other generation platforms need separately reviewed native
binding pins; this restriction does not apply to serving the generated HTML.

The pinned manifests are Soklet core's
[`verification/interoperability/inspector/package.json`](https://github.com/soklet/soklet/blob/main/verification/interoperability/inspector/package.json)
and neighboring
[`package-lock.json`](https://github.com/soklet/soklet/blob/main/verification/interoperability/inspector/package-lock.json).
Copy those two files into a separate dependency directory and install with
`npm ci --ignore-scripts --no-audit --no-fund`, using an isolated npm cache and
configuration as described in the
[dependency review](https://github.com/soklet/soklet/blob/main/verification/interoperability/inspector/dependency-review.md).
The catalog generator reuses that locked toolchain; it does not launch Inspector.

```sh
node mcp-app/build.mjs --dependencies /absolute/path/to/reviewed-dependencies
node mcp-app/build.mjs --dependencies /absolute/path/to/reviewed-dependencies --check
node --test mcp-app/catalog.test.mjs
```

The first command replaces only the generated HTML. `--check` verifies that the
checked-in artifact matches a fresh build without writing it. The commands do
not install dependencies or fetch packages. Controller/build tests use Node's
built-in test runner and do not require the dependency checkout.

## Optional browser check

The browser check uses installed Chrome, the official Apps `AppBridge`, and
synthetic catalog data. Supply the Soklet **core repository** for its existing
browser/process helpers and choose an output directory that does not yet exist:

```sh
node mcp-app/browser-test.mjs \
  --dependencies /absolute/path/to/reviewed-dependencies \
  --core /absolute/path/to/soklet \
  --chrome /absolute/path/to/chrome-executable \
  --output /absolute/path/to/new-browser-check-output
```

It checks desktop/mobile layout, refresh/filter requests, hostile-text rendering,
language selection, and clearing stale results. It retains screenshots and a
result receipt, uses a fresh temporary browser profile, and requires local
loopback sockets. It does
not use saved accounts or a running Toy Store instance. This same-origin,
synthetic official-SDK test is **not released-host compatibility, production
sandbox/CSP enforcement, authentication, or end-to-end deployment qualification**.
