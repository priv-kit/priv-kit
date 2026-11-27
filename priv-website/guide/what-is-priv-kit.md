---
description: Priv Kit features and module choices.
---

# What is Priv Kit? {#what-is-priv-kit}

Priv Kit lets Android apps start their own privileged server through Root, ADB, a shell command, or Shizuku.

## Features {#what-priv-kit-provides}

### Privilege UI {#privilege-ui}

Use [Privilege UI](./priv-ui) to add a Compose startup page with authorization and pairing. Try it in the [playground](/playground/).

### priv-core for custom interfaces {#priv-core}

Use `priv-core` to control [startup and authorization](./activation) from your own interface.

### Binder access {#binder}

Call Android system services through [Binder](./binder). Your app supplies their interfaces.

### Built-in file proxy {#file-proxy}

Read, write, and traverse files through the [file proxy](./file-proxy).

### Application-defined UserService {#user-service}

Run your own AIDL implementation in a privileged [UserService](./user-service).

## One server per app {#app-owned-runtime}

Each app manages its own server, connection, and recovery.

## Design philosophy {#design-philosophy}

Keep business logic and state in the main application process. Keep the privileged side focused on stateless forwarding wherever possible: receive inputs, perform an operation, and return the result. This reduces state synchronization and recovery work after a server restart.

Prefer the built-in APIs. Use a [UserService](./user-service#default-process) for custom operations; it shares the privileged server process by default.

## Where to start {#where-to-start}

Follow [Getting started](./getting-started), then choose the [built-in page](./priv-ui) or a [custom interface](./activation).
