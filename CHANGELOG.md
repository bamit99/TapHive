# Changelog

All notable changes to TapHive are recorded here. v1.1.1 is release-signed; the v1.1 core was device-verified on Android 16. The v1.1.1 change is UI-only and was build/unit-test verified while the test device was offline.

## [1.1.1] — Country-state clarity

- TRAFFIC now shows `EXTERNAL ENRICHMENT OFF · ENABLE IT IN SYSLOG TARGET` instead of `UNRESOLVED · 100%` when country lookup is opted out.
- Version metadata is `1.1.1` / code `3`.
- No change to the v2 syslog contract.

## [1.1.0] — v1.1 hardening

### Security
- Blocked TapHive's own traffic from entering its VPN with `addDisallowedApplication`.
- Added Android 12+ data-extraction rules and disabled legacy full backup for app databases/preferences.
- Stopped exporting the internal `TrafficActivity`.
- Removed the unconditional capture start from `Application.onCreate`, so the `startAtBoot` opt-in is no longer bypassed by process creation.
- Added DNS parser bounds checks and fuzz coverage.
- Added an explicit, default-off external enrichment consent checkbox.
- Replaced cleartext ip-api lookups with HTTPS `ipwho.is`, bounded the response to 64 KiB, capped the lookup queue/cache, and removed the ip-api cleartext exemption.
- Added TLS syslog (`tls` transport) using the platform trust store and hostname verification; TLS failures are surfaced as certificate errors and do not fall back to plaintext.
- Made history-off delete the `ip_meta` cache and stop DNS/geo persistence.
- Fixed the Android 16 status pulse restart every second.
- Stopped queuing syslog events while forwarding is disabled and made drop counters atomic.
- Guarded spinner indexing against `-1` during settings save.

### Reliability
- Contained per-packet forwarding exceptions so a malformed packet cannot silently kill the packet loop while the VPN stays up.
- Added a visible capture-failure notification and teardown path.
- Dropped IPv4/IPv6 fragments explicitly instead of forwarding partial or bogus-port packets.
- Added UDP/TCP header-length validation before reading payloads.
- Made UDP receive/send cleanup reliable, increased the UDP receive buffer to 65535, and bounded DNS callback failures.
- Added a SYN gate for new TCP sessions, corrected RST ACK values, bounded the client write queue, and added established-TCP idle expiry.
- Added flow-counter atomics and throttled flow expiry to once per second.
- Added connectivity callbacks and resolved the real non-VPN default network; helper sockets no longer bind to TapHive's own VPN.
- Recovered Android 16 back handling with `OnBackInvokedCallback` plus the legacy API-32 fallback. Device-verified on API 36.

### Persistence and performance
- Added a non-destructive SQLite v2 migration, `flow_id` tracking, WAL, and indexes for UID/destination queries.
- Moved flow-history inserts onto a bounded single-thread writer; the packet thread no longer waits for disk I/O.
- Moved TRAFFIC aggregate queries off the main thread and coalesced overlapping refreshes.
- “Flows” counts now use distinct flow ids instead of counting 10-second sample rows.
- Device-verified migration from the existing v1 database (`user_version=2`, history preserved) and TRAFFIC rendering.

### Release
- Bumped to `versionName 1.1`, `versionCode 2`.
- Added a gitignored release signing configuration; the release build is minified, release-signed, and non-debuggable.
- Device-verified a clean release install: VPN consent, capture start, and main-screen rendering.
- Removed the two public traffic screenshots containing real usage/destination data.

### Tests
- Added JUnit tests for IPv4/IPv6 parsing, fragment rejection, flow-key symmetry, DNS A-record parsing, truncated responses, query rejection, and 5,000-iteration DNS fuzz input.
- Added RFC 3164/5424 formatter tests and two on-device database tests for distinct-flow counting and retention-off cleanup.
- Current result: **14 JVM tests + 2 instrumentation tests, 0 failures**.

### Documentation
- Updated README for v1.1 behavior, release signing, TLS/enrichment consent, privacy claims, and known limitations.
- Added `docs/SIEM.md` with Splunk, Elastic/Logstash, and Sentinel examples and field mappings.
- Added a v1.1 status note to BUILDPLAN.

### Documentation
- Updated README for v1.1 behavior, release signing, TLS/enrichment consent, privacy claims, and known limitations.
- Added `docs/SIEM.md` with Splunk, Elastic/Logstash, and Sentinel examples and field mappings.
- Added a v1.1 status note to BUILDPLAN.
- Added the v1.1 audit report locally; it is not published while pre-fix findings and residual risks are being reviewed.

### Outstanding
- TLS syslog has been verified failing closed against a self-signed listener; a successful TLS session against a system-trusted collector still needs a lab endpoint.
- TLS pinning/mTLS is not implemented; TLS uses the platform trust store only.
- TCP retransmission/half-close handling remains simplified.
- IPv6 forwarding remains deferred; IPv6 traffic is not captured.
- History is still plaintext at rest inside the app sandbox (backup/device transfer excluded).
- Android 13–15 device coverage is still pending; v1.1 was verified on Android 16.
- Traffic screenshots need to be regenerated from controlled demo data before restoring them to the gallery.
- Release keystore/password must be backed up outside the repo; losing them prevents upgrades.
