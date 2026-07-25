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

Declare every hidden Android API only in the `:hidden-api` module. Do not place
framework mirrors or stubs in product modules. Before adding or changing a
hidden API declaration, use the `android-api-diff` MCP to inspect its signatures
and version ranges. When Java declaration code is needed, call
`generate_android_api_code` directly; it performs the version query itself.
Use its returned `code` as the source of truth. Preserve generated declarations,
version-range comments, and annotations; do not recreate or omit them manually.
The `:hidden-api` module must not contain Java constant declarations, field
initializers, or any `final` modifier. Convert generated `public static final`
fields to uninitialized `public static` fields, and remove `final` from
generated type and member declarations.

When a hidden API changes across Android maintenance releases that share the
same API level, implement runtime signature detection and dispatch in
`:priv-shared`; do not rely on `SDK_INT` alone. When the hidden interface is
absent on part of the supported API range, expose a narrow compat wrapper that
accepts an `IBinder` and keeps references to that interface out of lower-level
callers. Otherwise, keep compatibility logic on the corresponding hidden type.
Keep hidden API declarations limited to members required by the queried
framework API and compilation. Do not add empty private constructors or other
boilerplate merely to prevent instantiation.

Node.js, TypeScript, and SVG in the repository are limited to documentation and
repository tooling. Executable source files for this tooling must use the `.ts`
extension. The documentation site uses the default VitePress theme and must not
add custom theme CSS. These technologies must not be added to Gradle product
modules, Android runtime artifacts, or public Android APIs.
