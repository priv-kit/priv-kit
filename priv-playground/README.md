# priv-playground

An unpublished UI demonstration host under `priv.kit.playground`. It consumes `:priv-ui` on
Desktop/JVM and Browser/WasmJS. Android integration remains in `:priv-sample`.

`commonMain` provides the Material 3 theme and renders `PrivilegePreviewScaffold()`. The Desktop
entry point creates a resizable window. The Wasm entry point exports `renderPrivilegePlayground`,
which accepts a host element, resource URL resolver, theme, and `useLegacyPackaging`. The website owns the element lifecycle.
Root, Wireless ADB, static TCP, manual startup, and external authorization use an in-memory simulation.
Operations default to success, pairing accepts any six digits, and confirmation/cancellation dialogs
drive the same shared page components. No privileged runtime, networking, or system changes occur.
Copy buttons copy sample text to the host clipboard. Closing the page or window discards the session.

Run the desktop app:

```shell
./gradlew :priv-playground:run
```

Run the website from the repository root:

```shell
pnpm dev
```

Open `/playground/` or `/zh/playground/`. The Vue page provides
language, appearance, and `useLegacyPackaging` controls outside the canvas. `priv-website/scripts/build-playground.ts` builds the Wasm executable and assembles
the entry module, Wasm, Skiko, and Compose resources in the ignored `priv-playground/dist` directory.
The private workspace package exposes `renderPrivilegePlayground(container, dark, useLegacyPackaging)` with its resource resolver
already configured. The Vue page loads it with `import('priv-playground')`; Vite bundles its dependencies and
fingerprints the Wasm, font, and string resources into `priv-website/.vitepress/dist/assets`.
Thin localized Markdown pages mount the component through `ClientOnly` with `layout: false`.
VitePress handles routing and the single website build; no separate Vite build or dev server is needed.
The route selects the UI language, and appearance follows the website theme. Leaving the page
clears the Compose host and restores the browser language settings used by Compose resources.
The Wasm mount function returns an options updater, so switching the theme or packaging preserves simulation data.
The manual command uses a session-local installation path with two URL-safe Base64 tokens encoding 16 random bytes each
and the fixed package name `priv.kit.sample`. Packaging defaults to `true` (extracted ARM64 library); `false`
shows the `linker64` command for the library inside `base.apk`. Switching formats retains the installation path.
Changing the language opens a new localized page session.
Build the package with `pnpm --dir priv-website build:wasm` before importing it outside the website scripts.
No npm or Maven artifact is published for this module.

The playground bundles Noto Sans SC from the Google Fonts repository to render Chinese in Wasm.
Its SIL Open Font License is included under `src/commonMain/composeResources/files/NotoSansSC-OFL.txt`.
The font is demonstration-host data and is not shipped in `priv-ui`.
