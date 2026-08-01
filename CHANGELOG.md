# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Replace UserService start, bind, unbind, and stop operations with cancellable suspend APIs.
- Move UserService results to an asynchronous `ResultReceiver` protocol with cancellation
  propagation and bounded background execution.
- Improve UserService connection lifecycle cleanup and update the sample and documentation
  for the new API.
