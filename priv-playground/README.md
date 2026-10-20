# priv-playground

JVM and WasmJS previews using `PrivilegePreviewScaffold()`. Android examples live in `priv-sample`.

Startup, pairing, and confirmations are simulated. Any six-digit pairing code works; Copy uses the host clipboard. Closing the host ends the session.

## Run

Desktop:

```shell
./gradlew :priv-playground:run
```

Website, from the repository root:

```shell
pnpm dev
```

Set `PRIV_KIT_SKIP_ANDROID` to build without Android SDK/NDK. This mode still requires Java and loads only UI and playground. Leave it unset for Android builds and publishing.

## Browser

`/playground/` and `/zh/playground/` mount the Wasm page through Vue, with language, theme, packaging, and permission controls. Theme/config updates retain the session; language changes start a new one.

`renderPrivilegePlayground(container, dark, useLegacyPackaging, adbRestricted)` mounts the page and returns an updater. Manual commands use a random installation path. Packaging defaults to extracted libraries; `false` shows the APK command. ADB restrictions default to on and affect simulated Shell connections only.

`batteryOptimizationExempt` and `localNetworkPermissionGranted` default to `true`. Simulated grants notify the host through `onPermissionsChanged`.

`build-playground.ts` writes Wasm, Skiko, fonts, and Compose resources to ignored `dist`. The website imports `@priv-kit/playground`; Vite handles asset paths. `wasm-assets` exports sizes for download progress. Build before importing outside website scripts:

```shell
pnpm --filter @priv-kit/playground build:wasm
```

## Fonts and checks

`prepareFont` downloads a pinned Noto Sans SC version and checks SHA-256. It needs Node.js 24.15+; the first build needs network access and supports `HTTPS_PROXY`/`HTTP_PROXY`. The font is playground-only; its license is in `src/commonMain/composeResources/files/NotoSansSC-OFL.txt`.

Browser builds subset the font to ASCII and characters in repository `.kt`/`.xml` files. Scanning reads source literals as written, follows Git ignore rules, and includes untracked files. The original font stays intact; arbitrary user input may need fallback.

```shell
pnpm --filter @priv-kit/playground check
```

Desktop tests use public `PrivilegeScreen` APIs to verify host state changes and prompts.
