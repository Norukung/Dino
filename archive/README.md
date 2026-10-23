# Archived pre-Phase-2 implementation

`previous-full-version/` preserves the earlier source files, build configurations, tests and documentation that used Room, ACK, TTL, retries and DTN. They were moved out of the active Android source tree at the user's request to make the active app Phase 2 only.

This directory is outside all Gradle source sets and is not compiled or packaged. It is a reference archive, not an independently buildable project: shared unchanged files remain in the root project. No user message database was deleted.

`phase2-demo/` preserves the README and verification report from the fixed Hello Dinoblog demo before free-text chat was restored in version 2.1.

`v2.1-chat/` preserves files replaced when version 2.2 restored Room, ACK, TTL and pending-message retry. The active project now includes the restored features; this archive remains reference-only.
