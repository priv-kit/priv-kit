---
description: Learn what Priv Kit provides, why its runtime is app-owned, and where its responsibility ends.
---

# What is Priv Kit? {#what-is-priv-kit}

Priv Kit is an app-owned privileged runtime for Android. It lets a single
application start, connect to, and manage its own Privileged Server through
Root, ADB, Manual, or an external authorization bridge. Once
connected, the application can use explicit Binder primitives, the built-in
file proxy, or its own UserService while keeping domain logic under its control.

Most applications should start with `priv-ui`, which provides the Compose
authorization page and uses the runtime on the application's behalf. Use
`priv-core` directly only when the application needs a custom authorization
interface.

Want to try it? Jump to [Getting started](./getting-started).

## What Priv Kit provides {#what-priv-kit-provides}

### Privilege UI for standard integration {#privilege-ui}

`priv-ui` provides the ready-to-embed authorization page. It presents startup
status, Wireless Debugging pairing, permissions, TCP/IP confirmation, Manual
commands, and exact silent replay. Applications can use the supplied flow
without rebuilding those interactions.

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
static TCP, Manual, and app-provided external bridges. It does not provide
permission prompts, pairing input, confirmation surfaces, or error
presentation. Applications building a custom authorization interface can read
[startup methods](./activation) and implement those interactions themselves.

### Binder building blocks {#binder}

Priv Kit can resolve an explicit system service and perform raw Binder
transactions without hiding the original contract or failure semantics. The
application remains responsible for the service interface, transaction format,
and domain behavior. Read the [Binder guide](./binder) for the available
primitives.

### Built-in file proxy {#file-proxy}

Basic absolute-path file operations can run directly in the Privileged Server
without an application-defined UserService. Content uses real file descriptors,
and directory entries stream over a pipe. Read the [file proxy guide](./file-proxy)
for its `java.io.File`-compatible return semantics and deliberate safety limits.

### Application-defined UserService {#user-service}

When raw transactions are not the right boundary, an application can define its
own AIDL interface and privileged implementation. Priv Kit manages the
UserService lifecycle, process mode, and Binder handoff while the application
owns the API and behavior. Read the [UserService guide](./user-service) to choose
an embedded or dedicated process.

## Why is the runtime app-owned? {#app-owned-runtime}

Priv Kit is designed for one application to manage its own server. It is not a
device-wide daemon, a multi-tenant registry, or a shared permission broker.
Keeping the runtime app-owned gives the host application explicit control over
startup, server identity, connection state, recovery, and privileged domain
logic.

Every startup method still converges on the same validated connection model.
The runtime observes server death and connection failures instead of presenting
an uncertain result as success.

## Where to start {#where-to-start}

- Follow [Getting started](./getting-started) to add `priv-ui` and embed the
  authorization page.
- Configure the supplied foreground and silent flows in
  [Privilege UI](./priv-ui).
- Read [startup methods](./activation) only when building a custom interface
  with `priv-core`.
- Choose the [file proxy](./file-proxy) for basic filesystem access,
  [Binder](./binder) for raw transactions, or
  [UserService](./user-service) for an application-defined AIDL service.
- Browse the source and report issues on
  [GitHub](https://github.com/priv-kit/priv-kit).
