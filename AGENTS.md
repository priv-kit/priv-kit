# Priv Kit repository guidance

Read these files before changing product behavior or public APIs:

1. `docs/architecture.md`
2. The README of every affected Gradle module

The `docs` directory contains durable maintainer and AI-readable repository
documentation. It is not published as the project website.

The VitePress project root and published source directory are both `website`.
Every public English Markdown page under `website` must have a path-equivalent
Simplified Chinese page under `website/zh`. Internal or AI-readable Markdown
belongs under `docs`, not `website`. Run `pnpm check` and `pnpm build` from the
`website` directory after changing public documentation.

Node.js, TypeScript, and SVG in the repository are limited to documentation and
repository tooling. Executable source files for this tooling must use the `.ts`
extension. The documentation site uses the default VitePress theme and must not
add custom theme CSS. These technologies must not be added to Gradle product
modules, Android runtime artifacts, or public Android APIs.
