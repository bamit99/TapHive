# Changelog

All notable changes to TapHive are recorded here. v1.5.0 is release-signed; the v1.1 core was device-verified on Android 16. v1.2.x adds DNS diagnostics, per-IP detail, external-service attribution, and the UDP/53 diagnostics filter; v1.2.x device/instrumentation validation is pending while the test phone is offline.

## [1.5.0] — Optional enriched syslog fields

- Added `Include app, DNS and geo fields` under SYSLOG TARGET. It is off by default.
- When enabled, flow events carry optional `th_app_name`, `th_country`, `th_region`, `th_city`, `th_asn`, `th_org`, `th_isp`, `th_dns_name`, and `th_enrichment_provider` alongside the existing raw fields.
- Added a formatter test for the enriched JSON keys. Current JVM coverage: 19 tests.
- No syslog schema version change; fields are additive.
- Version metadata is `1.5.0` / code `10`.

## [1.4.0] — Search applications and destinations

- Added a fixed search field above the TRAFFIC tabs.
- Application search matches display name or UID.
- Destination search matches IP, port, protocol, UID, country, organisation, or DNS name against the retained history.
- Search results update live without restarting capture.
- Version metadata is `1.4.0` / code `9`.
- No syslog schema change.

## [1.3.0] — Application drill-down

- Tapping an application in TRAFFIC opens a detail screen.
- Shows total, sent, received, and distinct flow counts.
- Shows protocol split with bytes and flows.
- Shows top destinations and top ports for that application.
- History schema v4 stores per-sample sent/received bytes; pre-1.3 rows show direction as unavailable rather than guessing.
- Version metadata is `1.3.0` / code `8`.
- 18 JVM tests pass; v1.3 instrumentation/device validation was not run because the test phone is offline.
- No syslog schema change.

## [1.2.2] — Truthful capture notification

- Notification now says `Capturing flow metadata locally` when syslog is disabled or no host is configured.
- Notification says `Forwarding flow metadata to syslog` only when forwarding is enabled with a host.
- Saving settings refreshes the foreground notification without restarting capture.
- Version metadata is `1.2.2` / code `7`.
- No syslog schema change.

## [1.2.1] — DNS UDP-port filter

- DNS diagnostics now ignore UDP traffic whose remote port is not 53, including unrelated UDP/5353 traffic.
- Added a regression test proving non-DNS UDP does not change query/response/timeout/malformed counters. Current JVM coverage: 18 tests.
- Documentation now states that TCP DNS, DoH, and DoT are not visible to diagnostics.
- Version metadata is `1.2.1` / code `6`.
- No syslog schema change.

## [1.2.0] — DNS diagnostics and IP drill-down

- Added aggregate DNS health counters: queries, responses, timeouts, NXDOMAIN, SERVFAIL, other errors, malformed. Query names are not stored or emitted.
- Added `th_dns_*` counters to `capture_start`/`capture_stop` syslog events and an **ABOUT → DNS DIAGNOSTICS** view.
- Added a tappable per-destination IP detail screen: DNS name, country/region/city, ASN/org/ISP, provider, timestamp, refresh, and optional lookup.
- Extended `ip_meta` to schema v3 with region, city, ASN, ISP, and provider, preserving existing data.
- Added visible ipwho.is attribution and a data-source button in About, plus README data-source disclosure.
- No syslog schema/version change; the new `th_dns_*` fields are additive.

## [1.1.2] — Country backfill and retry

- Existing unresolved destination IPs are looked up when external enrichment is enabled and TRAFFIC opens.
- Failed/rate-limited lookups now retry after five minutes instead of being suppressed for six hours.
- Rejected enrichment queue entries are retried instead of being silently skipped.
- Added an on-device test for the unresolved-IP query.
- No syslog schema changes.

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
