# What's Changed

<!--
Keep only the changes for the current release in this file.
Replace the content when preparing the next release; release history is preserved by GitHub Releases.
-->

- Record privileged process crashes and add a crash log browser to the sample app.
- Fix directory walks through symbolic-link roots such as `/sdcard` and allow opening directories whose parents cannot be listed, including `/storage/emulated/0`.
- Move the sample file browser's parent-directory action beside the path input and clear input focus when submitting or interacting outside the field.
