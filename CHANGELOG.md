# Changelog

All notable changes to TapHive are recorded here. v1.1 is in progress; the app version is not bumped until the release is validated.

## [Unreleased] — v1.1 hardening

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

### Tests
- Added JUnit tests for IPv4/IPv6 parsing, fragment rejection, flow-key symmetry, DNS A-record parsing, truncated responses, query rejection, and 5,000-iteration DNS fuzz input.
- Current result: **11 tests, 0 failures** (`testDebugUnitTest`).

### Documentation
- Added the v1.1 audit report (kept local while pre-fix findings are not public).
- README and BUILDPLAN updates are pending the remaining security/transport work.

### Outstanding
- TLS syslog has been verified failing closed against a self-signed listener; a successful TLS session against a system-trusted collector still needs a lab endpoint.
- TLS pinning/mTLS is not implemented; TLS uses the platform trust store only.
- Release signing is not configured; the published v1.0 APK is debuggable/debug-signed.
- SQLite still needs WAL/index/migration work and off-thread writes; TRAFFIC still queries on the main thread.
- TCP retransmission/half-close handling remains simplified; IPv6 forwarding remains deferred.
- Public screenshots still contain real usage data and must be replaced with demo data.
- v1.1 version bump and signed release APK are pending validation.
