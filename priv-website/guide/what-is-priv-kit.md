---
description: Priv Kit features and module choices.
---

# What is Priv Kit? {#what-is-priv-kit}

Priv Kit lets an Android app start and manage its own privileged process through Root, ADB, a shell command, or an external authorizer. Use it for Binder calls, file operations, commands, and app-defined UserServices.

Use `priv-ui` for the Compose authorization page, or `priv-core` to build your own interface.

See [Getting started](./getting-started).

## Features {#what-priv-kit-provides}

### Privilege UI {#privilege-ui}

`priv-ui` handles startup, Wireless Debugging pairing, permission requests, TCP/IP confirmation, and silent startup.

<table>
  <tr>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/d04f3d204dc5988ddfa12855265b6b7d1aea3f96719327f6b450482c7f7aa530/687474703a2f2f652e676b642e6c692f65343762366337642d366137612d346264392d383631342d666565336661303266656339" /></td>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/2b33fd1e27fef01878c80725ab16e5cc5ea0f618c62ad0e2346248bea9405eeb/687474703a2f2f652e676b642e6c692f61333965373435312d663932382d346664642d393132322d333236656339373164633336" /></td>
  </tr>
  <tr>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/d819ccb9224b509111d7de52f150681664121cd16c1058380b6e814a639e0f4e/687474703a2f2f652e676b642e6c692f66623639303536652d316338332d343562342d616562362d393266396639343333623536" /></td>
    <td><img width="320" height="714" src="https://camo.githubusercontent.com/c0c53fc5f08f4e2093a1222647c02b5fb81875e6017e41752cbdb1cf1a3322ea/687474703a2f2f652e676b642e6c692f38373330646431332d643430352d343365312d613361332d656234653661353333303635" /></td>
  </tr>
</table>

### priv-core for custom interfaces {#priv-core}

`priv-core` provides the runtime and transport APIs behind Root, wireless ADB,
static TCP, Manual, and app-provided external bridges. A custom interface adds
its own permission prompts, pairing input, confirmations, and error presentation.
See [startup methods](./activation) for the underlying flows.

### Binder access {#binder}

Resolve system services and forward Binder transactions. Your app supplies the service interfaces and handles errors. See [Binder](./binder).

### Built-in file proxy {#file-proxy}

The [file proxy](./file-proxy) runs file operations in the server. File contents and directory entries travel through pipes.

### Application-defined UserService {#user-service}

Define an AIDL interface and its privileged implementation. Priv Kit starts the [UserService](./user-service), manages its lifecycle, and returns its Binder.

## One server per app {#app-owned-runtime}

Each app owns its server and controls startup, connection, and recovery.

All startup methods use the same connection checks and report server death or connection failure.

## Where to start {#where-to-start}

- Follow [Getting started](./getting-started) to add `priv-ui` and embed the
  authorization page.
- Configure the supplied foreground and silent flows in
  [Privilege UI](./priv-ui).
- Read [startup methods](./activation) when building a custom `priv-core`
  interface.
- Choose the [file proxy](./file-proxy) for basic filesystem access,
  [Binder](./binder) for raw transactions, or
  [UserService](./user-service) for an application-defined AIDL service.
- Browse the source and report issues on
  [GitHub](https://github.com/priv-kit/priv-kit).
