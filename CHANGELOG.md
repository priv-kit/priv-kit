# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Keep manual startup commands free of crash-log environment variables; receive the configured directory through the owner handshake while preserving early crash logging for Core-managed startup.
- Download and verify the pinned playground font before resource processing, reuse the local cache, and keep the full font out of Git.
