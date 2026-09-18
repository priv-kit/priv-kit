# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Add `flushBatchSize` to `PrivilegeFile.walk()` so callers can tune pipe flush frequency
  during large directory traversals. The default batches 32 entries while delivering the
  first entry immediately.
- Remove unused core fields and duplicate synchronization in user service startup and
  runtime handshake bookkeeping.
