# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Animate the permission restriction warning's height and spacing, initializing its status
  synchronously in the ViewModel to avoid flicker during route transitions.
- Check server permissions and startup grant eligibility directly from the client using the
  server PID/UID, while keeping denied-permission enumeration entirely in the server.
- Reject stale permission results after server death or replacement without treating
  ActivityManager failures as privileged server disconnections.
- Remove unused ADB identity refresh code and duplicate permission state subscriptions,
  and reuse initial permission refresh work for the same connection.
- Upgrade the internal protocol to 28 and remove the obsolete permission-check transaction.
  Restart existing privileged servers to use the new protocol.
