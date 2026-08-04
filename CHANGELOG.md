# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Remove the `priv-ui` initialization provider so consuming apps keep only the Core handshake provider in their merged manifest.
- Enable automatic UI recovery only after a matching UI-owned foreground start succeeds, without inferring recovery intent from manual, non-UI, or owner-reconnect connections.
