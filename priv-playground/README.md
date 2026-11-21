# priv-playground

JVM and WasmJS preview of the Material 3 authorization page from `priv-ui-m3`. The preview simulates startup and pairing.

| Task | Command from the repository root |
| --- | --- |
| Run desktop preview | `./gradlew :priv-playground:run` |
| Run documentation site and browser preview | `pnpm dev` |
| Build browser assets separately | `pnpm --filter @priv-kit/playground build:wasm` |
| Check the playground | `pnpm --filter @priv-kit/playground check` |

Set `PRIV_KIT_SKIP_ANDROID` for preview-only builds without an Android SDK/NDK. Java is still required. The first browser build needs network access for the font. See [the font license](src/commonMain/composeResources/files/NotoSansSC-OFL.txt).
