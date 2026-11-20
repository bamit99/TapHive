# TapHive — Phased Build Plan

Original implementation, clean-room. This document is a **behaviour-only specification**. Implement from this document alone:

- Do not copy code, strings, layouts, icons, UI text, resource names, identifiers, settings keys or API shapes from any prior implementation.
- Internal analysis/reference material is not an implementation input and must not ship in the product.
- Names below (`th*` entry points, `th_*` preference keys) are TapHive-owned. They are not translations of any other product's API.

**Naming (provisional):** product name **TapHive**, app id `com.taphive.app`, companion addon `com.taphive.companion`. Trademark clearance is a Phase 0 gate (§4). A name search returns an unrelated iOS design studio using "Taphive"; record a cleared fallback name and package ids before producing any public artifact.

## 1. Product summary

On-device packet capture for Android **without root**: local-only VPN that aggregates device traffic into one interface, then classifies/dumps it. Per-app attribution, protocol detection, geo lookup, live HTTP/payload inspection — all free.

**Phase 1 slice:** capture flow metadata over the VPN interface and forward it as structured syslog events for SIEM ingestion. The rest of this document is target state; §4 marks which phase ships each part.

## 2. Platform requirements

- minSdk **32** (Android 13), targetSdk/compile **37**; phone + tablet; multi-user/work-profile aware.
- No billing, no SKUs, no license servers — everything free.
- Dependencies policy: only permissive licenses (Apache-2 / BSD / MIT / zlib) and clean original code — own native core (§8), own packet engine, own protocol detection, own disseminators, own pcapng writer; zstd + zlib libs stay as-is where possible. GPL/LGPL/viral native libraries may not link into the app binary without an explicit Phase 0 decision (§4, §12).
- Data sources: GeoLite2 country/ASN in MaxMind-DB format. Licensing is the **GeoLite End User Agreement incorporating CC BY-SA 4.0** — not plain CC BY — which requires attribution ("This product includes GeoLite Data created by MaxMind, available from maxmind.com"), a MaxMind account + licence key for downloads, keeping data current and destroying old copies, and prohibits using the data to locate individuals. Verify the current EULA before bundling (last checked 2026-11-20); if these obligations are unacceptable, choose a different source before Phase 2.
- Blacklists: open domain/IP/threat-intel feeds; check each feed's licence and record the outcome in §12 before bundling (Phase 4 gate).

### 2.1 Platform permissions & store compliance

| Need | Mechanism | Gate |
| --- | --- | --- |
| Numeric uid attribution | uid mapping via socket statistics; no package-visibility permission needed | Phase 1 |
| Package label/icon | `QUERY_ALL_PACKAGES` (Play-restricted — declare permitted use) or narrow `<queries>` where sufficient; profile-aware uid mapping for work profiles | Phase 2 |
| Foreground capture | `BIND_VPN_SERVICE`, `FOREGROUND_SERVICE`, persistent notification, explicit VPN consent; `POST_NOTIFICATIONS` runtime prompt on API 33+ | Phase 1 |
| Start at boot | `RECEIVE_BOOT_COMPLETED`, user-toggleable | Phase 6 |
| Visualization mask | Overlay permission (`SYSTEM_ALERT_WINDOW`) or a Play-compliant non-overlay alternative — decide in Phase 0 | Phase 4 |
| Companion addon | Separate store listing (Play/F-Droid/other compliant channel). No out-of-band APK download from inside the app. CA install uses Android's credential flow with an explanatory screen; no programmatic cert installation | Phase 3 |
| Root mode | Opt-in `su` invocation, disclosed in store listing | Phase 6 |
| Data disclosure | Data Safety declaration covering VPN capture, local files, optional syslog/remote export, geo lookups; cleartext syslog **off by default** and behind explicit consent | Each release |
| Cleartext traffic | Network security config limited to user-configured collector/syslog endpoints | Phase 1/3 |

## 3. Architecture (target state)

```
┌ UI (Material3, nav drawer + nav rail, per-app language, splash, dark theme)
│   MainActivity · Connections · Apps · Stats · Settings · About · Onboarding
├ App core (Application class: singletons for lists/prefs, crash handler)
├ CaptureService = VpnService + foreground service + TapHive packet reader
│   └ Phase 1: PacketReader (OWN, Kotlin) → flow registry → syslog event sink
│   └ Later phases: native core seam (§8) → detection → disseminators → compression
├ Data: schema-validated SharedPreferences, JSON lists, files dir, SAF capture folder
├ Exporters: file / TCP / UDP / HTTP server / remote collector / syslog forwarder
└ Companion addon (own package), root mode (su)
```

Capture flow: VPN fd → Kotlin `PacketReader` (Phase 1; §8 JNI seam reserved for later phases) → flow registry (uid attribution via socket statistics) → UI push + syslog event sink. Payload/HTTP reassembly belongs to later phases and is not built in Phase 1.

## 4. Phased delivery

Each phase ends with a shippable, tested increment. Acceptance = the phase's rows in the §11 test matrix are green.

### Phase 0 — gates & spike (before Phase 1)

- Trademark clearance for TapHive and both package ids; fallback recorded if blocked.
- Licence decisions: protocol detection own-vs-copyleft, GeoLite obligations, feed licences, dependency audit.
- Play policy/permission review (§2.1) — Phase 1 needs only VPN consent and notifications; package visibility is deferred with per-app labels to Phase 2.
- Reader spike: **metadata-only** reader over a VPN fd — IPv4/IPv6 headers, TCP/UDP ports, 5-tuple flow keys, flow expiry, bounded memory. Phase 1 delivers this as pure Kotlin `PacketReader`; the JNI/native seam in §8 is reserved for later phases. No payload, no L7 detection. Measure packets/second + bytes/second on API 32 and a 16 KB page-size device, and define the no-drop budget (§11).
- Exit: named decisions recorded in §12 and spike meets the budget.

### Phase 1 — MVP (SIEM ingestion pipeline)

Goal: grant VPN, run a capture, and see flow metadata events land in a syslog receiver. Deliberately minimal — no files, no payload, no inspection UI.

- Foreground VpnService with minimal builder (§6), persistent notification, VPN consent, start/stop control.
- Own Kotlin capture loop (`PacketReader`) emitting **metadata only**: 5-tuple, ip_version, uid (numeric — package label lookup deferred to Phase 2), byte/packet counters, timestamps, flow status. No payload, DNS names, L7 protocol, geo/ASN or decryption. The §8 `thRunCaptureLoop` JNI name remains a reserved seam, not a Phase 1 build dependency. Event fields are defined in §9.
- Flow lifecycle: `flow_open` on first packet; `flow_close` on idle timeout/teardown with final counters; `capture_start`/`capture_stop`. No periodic updates for long-lived flows in Phase 1.
- Syslog forwarding to a remote SIEM receiver: RFC 3164 header + JSON event body, over **UDP or TCP, default port 514**, **disabled by default**; settings toggle + host/port + transport; facility default local0 (13), app name default = product name. `PRI = facility * 8 + severity` (flow events INFO=6, lifecycle NOTICE=5). Every event carries `th_schema_version` and `th_session_id` for parser versioning and correlation.
- Sender: bounded queue with drop-newest + dropped counter, token-bucket rate limiter for bursts, TCP persistent socket with exponential backoff (1 s initial, 60 s cap, jitter) and drop-on-disconnect; UDP send-and-forget. Best-effort only: no wake lock, flush opportunistically under doze. Consent notice states flow metadata is sent in cleartext; TLS and RFC 5424 remain roadmap (§12).
- **Out of scope for Phase 1:** pcap/pcapng output (moved to Phase 3), per-app labels/icons and package-visibility permission (Phase 2), DNS/L7/HTTP/payload inspection (Phase 2), geo/ASN (Phase 2), firewall/threat detection (Phase 4), companion addon/decryption (Phase 3), root/backup/external API (Phases 6–7), onboarding/splash/theme/TV/Wear/locales (Phase 8), non-syslog exporters (Phase 3).

### Phase 2 — Inspection layer

Goal: protocols, geo and HTTP become visible.
- Per-app attribution in the UI: resolve the Phase 1 numeric uid to package label/icon via `QUERY_ALL_PACKAGES` or scoped `<queries>` (§2.1).
- Own protocol detection module behind `thDetectProtocols`; DNS queries view; country+ASN via GeoLite2 database (auto-download after EULA notice, with attribution).
- Payload tab: hexdump + full payload view with size threshold/truncation notice.
- Basic HTTP log for unencrypted HTTP (request/response reassembly — no TLS decryption yet), flow ids.
- Per-app stats screen; CSV export/log-size setting.
- Per-app capture filter ("capture list"): app allowlist, exclusive mode; VPN exclusions; block new apps.

### Phase 3 — File formats & TLS interoperability

Goal: pcapng and TLS-keylog interoperability with standard pcap analysis tools.
- pcapng writer incl. a TapHive-owned custom "keylog capture block" (`thReadKeylogFromCapture`, `thHasKeylogBlocks`); document our block wire format ourselves. Standard tools parse the base pcapng and ignore our custom block — that is acceptable. TLS decryption rules list; QUIC policy never/always/for-decryption; private-DNS auto-block.
- Companion addon `com.taphive.companion` + setup wizard: intro → install addon (store link) → install CA cert via system credential flow → done (doze-mode notice, addon options passthrough).
- pcap/pcapng file output (filename prefix, app storage) — moved here from the original Phase 1 scope.
- Built-in HTTP server (max 8 concurrent clients) serving pcap/pcapng MIME streams; TCP/UDP exporters. Phase 3 owns all non-syslog exporters.

### Phase 4 — Security features

Goal: firewall + malware detection live by default.
- Firewall: whitelist mode, rules (app/ip/host/protocol/country), country-rule warnings, block/unblock from connection view; allowlist allow/remove context actions; defaults pre-seeded (§10 storage).
- Malware detection: blacklist managers for open domain/IP/threat-intel feeds (licences cleared in Phase 0); status OK/detected/update-failed + notifications; whitelist (free).
- Visualization mask; block-rule JSON list; per-app stats polish; firewall-block and threat-match counters on dashboard (`thFirewallBlockCount`, `thThreatMatchCount`).

### Phase 5 — Connectivity plumbing

Goal: proxies and port mapping.
- SOCKS5 redirection (+auth); port forwarding (+enabled, exemptions); IPv4/IPv6 modes [ipv4|ipv6_only|both]; custom DNS (system/custom v4+v6); DNS server set; doze notices; always-on-VPN notice; local-network notice.

### Phase 6 — Device integration

Goal: survive reboot & disconnection; root power.
- Start at boot (BOOT_COMPLETED), restart-on-disconnection service pattern, `su` shell via `thRunRootCommand` for netns-based capture, SAF persistent URI perms.
- Multi-user/work-profile attribution across users (signature permission as needed).

### Phase 7 — Data & API

Goal: user data portability and third-party apps.
- Settings backup/restore: JSON export/import incl. sensitive-key confirm, import-anyway for mismatched app version, merge-on-restore for capture list; capture folder via SAF.
- External API (behavior contract, our own strings): control activity `get_status`/`start`/`stop`/`get_session_info` + `action` extra + status broadcast; per-caller consent grants stored locally (`control_grants`), no licence/activation flow and no server.

### Phase 8 — Platform polish

- Android TV (LEANBACK launcher, banner), Wear, desktop mode; RTL; per-app language set (launch at ≥15 locales, expandable); fonts/night layouts; about screen + open-source licenses page (Apache/BSD/zlib deps + data attributions).
- App-log viewer; crash dialog polish.

## 5. UI spec

- Main: drawer + nav rail; start/stop/settings menu; tabs (status|connections) + per-app drill-ins.
- Screens: Status dashboard (start control, capture interface, dump target), Connections (+details), Apps (+overview+stats), HTTP log (+filter+payload), Firewall status, Malware status, Blacklists, Capture list, Settings (+DNS/Syslog/GeoIP/SOCKS5/VPN-Exemptions/PortMap sub-screens — Syslog: enable toggle, host/port, UDP/TCP picker, test button, cleartext-consent notice), About, App log, Companion setup wizard (Intro→Install Addon→Install Cert→Done), Onboarding, ErrorActivity (crash), control-permissions editor.
- Context menus: app/connection/capture-list/http-log/list-edit; copy/share; CSV export; block/unblock; CSV fields settings; search; "open pcap file" (SAF → analysis mode).
- Themes: light/dark/auto; splash screen; RTL; watch/tv/landscape layouts.

## 6. VPN builder defaults (service core contract)

MTU, /30 split-route IPv4 (0/1+128/1), IPv6 `2000::/3` + `fc00::/7`, custom DNS or system DNS, app allowlist/exclusions, session name; metered=false on ≥API 29.

## 7. Settings schema (validated by schema + validators)

**Phase 1 validates only the `syslog_*` group plus runtime capture on/off state.** All other groups below are target state for later phases and need not be implemented or persisted in Phase 1.

- capture: `capture_dump_mode` [off|pcap|tcp|udp|http_server], `pcapng_enabled`, `keylog_blocks_enabled`, `dump_prefix`, `max_dump_bytes`, `max_flow_packets`, `snaplen_bytes`, `root_capture`, `capture_interface`.
- network: `ip_mode` [ipv4|ipv6_only|both], `use_system_dns`, `dns_server_v4`, `dns_server_v6*`, `auto_block_private_dns`, `socks5_*` (+auth user/pass), `port_forwarding` (json, sensitive, enabled, exemptions), `vpn_exclusions`.
- capture filter: `app_allowlist` set + `app_filter_enabled`.
- security: `firewall_enabled`, `block_new_apps`, `firewall_allowlist` (+mode +init_version), `block_rules` json, `threat_detection_enabled`, `threat_allowlist` json, `mask_rules` json, `decryption_rules` json.
- misc: `start_at_boot`, `restart_on_disconnect`, `connection_log_size`, `http_server_port`, `collector_host`/`collector_port`*, `acks`, `payload_notice_ack`, `quic_policy` [never|always|for_decryption], `full_payload`, `tls_keylog`, `cert_setup_done`, `ca_setup_skipped`, `ignored_companion_version`, `lockdown_vpn_notice_shown`, `local_network_notice_shown`.
- syslog (Phase 1): `syslog_enabled` (bool, default false), `syslog_host` (host/IP, sensitive), `syslog_port` (int, default 514), `syslog_transport` [udp|tcp], `syslog_facility` (int, default 13), `syslog_app_name` (string, optional, default = product name), `syslog_format` [rfc3164] — rfc5424 is reserved for §12 and must be rejected by the validator until implemented.
- runtime-only: `app_language`, `app_version`, `control_grants` json (per-caller consent, no server), `persistable_uri`.
- `sensitive()`/`noBackup()`/`mergeOnRestore()` flags as marked above.

## 8. Native boundary (TapHive JNI contract — reserved seam)

Phase 1 implements capture and flow accounting in pure Kotlin (`PacketReader`). The entry points below are TapHive-owned names describing a future native seam; they are not linked into the Phase 1 APK.

| Entry point | Behaviour | Phase |
| --- | --- | --- |
| `thInitLogger` / `thWriteLog` | initialise log sink / emit a log event | 1 |
| `thRunCaptureLoop` / `thStopCaptureLoop` | consume the VPN fd and publish flow metadata events; stop cleanly | 1 |
| `thDumpStats` | snapshot capture statistics | 2 |
| `thDetectProtocols` | return detected L7 protocols for a flow | 2 |
| `thSetPayloadMode` | runtime payload capture mode | 2 |
| `thWriteMasterSecret` | append a TLS master secret to our keylog store | 3 |
| `thReadKeylogFromCapture` / `thHasKeylogBlocks` | read/detect our custom keylog block in a pcapng file | 3 |
| `thCaptureHeader` | file header metadata for the active capture | 3 |
| `thFirewallBlockCount` / `thThreatMatchCount` | dashboard counters | 4 |
| `thSetFirewallEnabled` | toggle firewall enforcement | 4 |
| `thSetDnsServers` | runtime DNS override | 5 |
| `thRunRootCommand` | opt-in root shell command | 6 |

Zstd decoding uses a permissively-licensed library (e.g. zstd-jni) behind our own wrapper with a `decompress` method. All pushes (stats/HTTP/payload/notifications/permission prompts) go through a single-thread executor callback; UI polls/pushes via listeners (keep the observable interface stable). Phase 1 builds only the flow-event push path.

## 9. Data formats

### Phase 1 syslog event (SIEM contract)

RFC 3164 header `<PRI>MMM dd hh:mm:ss HOST APP[PID]: ` followed by one JSON object with stable, typed keys:

| Key | Type | Notes |
| --- | --- | --- |
| `th_schema_version` | int | 2 for Phase 1; increment on any field change |
| `th_session_id` | string | UUID per capture run |
| `th_event` | enum | `capture_start` / `capture_stop` / `flow_open` / `flow_close` |
| `th_timestamp` | string | UTC ISO-8601 with milliseconds |
| `th_flow_id` | string | stable within a session; absent on capture events |
| `th_uid` | int | numeric app uid; no package label in Phase 1 |
| `th_ip_version` | int | 4 or 6 |
| `th_protocol` | int | IP protocol number (6 TCP, 17 UDP, …) |
| `th_src_ip` / `th_dst_ip` | string | textual IPv4/IPv6 |
| `th_src_port` / `th_dst_port` | int | 0 when not applicable |
| `th_bytes_sent` / `th_bytes_received` | int | cumulative at event time |
| `th_packets_sent` / `th_packets_received` | int | cumulative at event time |
| `th_status` | string | flow state or close reason |
| `th_dropped_events` | int | queue-overflow count, included on lifecycle events |
| `th_packets_captured` | int | raw packets read from the VPN fd; lifecycle events only |
| `th_events_forwarded` | int | syslog events successfully sent; lifecycle events only |

Severity: `capture_*` = NOTICE (5), `flow_*` = INFO (6). Facility default local0 (13), so a Phase 1 flow event uses PRI 110. No payload, DNS names, URLs or packet bytes are ever emitted in Phase 1. Example:

`<110>Nov 20 21:41:00 taphive TapHive[1234]: {"th_schema_version":2,"th_session_id":"...","th_event":"flow_close","th_timestamp":"2026-11-20T21:41:00.123Z","th_flow_id":"...","th_uid":10123,"th_ip_version":4,"th_protocol":6,"th_src_ip":"192.168.1.5","th_src_port":52344,"th_dst_ip":"93.184.216.34","th_dst_port":443,"th_bytes_sent":512,"th_bytes_received":2410,"th_packets_sent":4,"th_packets_received":6,"th_status":"CLOSED","th_dropped_events":0}`

Lifecycle example with aggregate counters:

`<109>Nov 20 21:41:05 taphive TapHive[1234]: {"th_schema_version":2,"th_session_id":"...","th_event":"capture_stop","th_timestamp":"2026-11-20T21:41:05.000Z","th_bytes_sent":0,"th_bytes_received":0,"th_packets_sent":0,"th_packets_received":0,"th_dropped_events":0,"th_packets_captured":1842,"th_events_forwarded":61}`

The full target-state formats below are for later phases; their fields are not emitted in Phase 1.

- **Flow record:** uid, flow_id, ip_version, ip_protocol, src/dst ip+port, local_port, interface_index, first/last_seen, status, sent/rcvd packets+bytes, blocked_packets, block_accounted, country, asn, decrypted flag, decryption_error, alerted, is_blocked, info, payload refs.
- **Flow states:** NEW/CONNECTING/CONNECTED/CLOSED/ERROR/SOCKET-ERROR/CLIENT-ERROR/RESET/UNREACHABLE.
- **Match lists:** types VISUALIZATION_MASK, THREAT_ALLOWLIST, BLOCK_RULES, FIREWALL_ALLOWLIST, DECRYPTION_RULES, APP_ALLOWLIST; rule fields: app/package, ip/cidr, host/domain, protocol, country (+port); JSON per list; uid-mapping invalidation on user changes.
- **Stats:** active/total flows, bytes/packets sent+received, ipv6 variants, dump_bytes, packets_dropped, num_open_sockets, max_fd, num_dns_queries, num_dropped_flows, alloc_summary.
- **HTTP log:** request/response pairs (method, host, path, query, status+code, content-type, body length, stream_id, timestamp, reset_flag); payload chunks (file offset/len, websocket_opcode).
- **Blacklists status JSON:** per-list {num_rules, last_update}; global {num_domain_rules, num_ip_rules, last_update}.
- **Files:** pcap + pcapng (own writer + custom keylog capture block), TLS keylog format, geo dbs (mmdb); firewall defaults: shell/system apps, GMS/GSF/IMS, samsung push.

## 10. Storage layout

files dir: blacklists .txt, geobackends mmdb, pcap files (prefix), keylog, lists JSON; cache: logs, zip temp; SAF capture folder (persistable URI perm prefs); SharedPreferences (backed by schema); native lib dir extractable=true.

## 11. Test / verification matrix (per phase gate)

Devices (Phase 1): API 32–37 phone/tablet and **16 KB page-size devices** (native-library requirement). Phase 1 flows: install→VPN consent→start capture→`capture_start`/`flow_open`/`flow_close`/`capture_stop` received at lab receiver; syslog e2e vs lab SIEM receiver: UDP+TCP 514, RFC3164 PRI/facility/severity, JSON schema/field completeness, timestamp/session/flow-id correlation, no payload/DNS/URL fields, TCP server-down→backoff-reconnect, rate-limiter burst (many flows), queue-overflow counter, no-wake-under-doze (best-effort flush), cleartext consent. Later phases: TV; DNS view; HTTP log via companion addon; TLS keylog→decrypt via companion addon; exporters tcp/udp/http/remote-collector → companion desktop app / pcap analysis tools; pcapng + custom keylog block parse with standard pcap tools (base file must parse; custom block ignored); firewall block/allow e2e; malware notification; backup/restore incl. sensitive confirm; root mode; multi-user; start-at-boot; API status/start/stop from third-party app + consent; crash dialog; notifications; doze notices; packet loop no-drop under the Phase 0 budget; low-memory kill (payload release path); runtime-permission denial paths (notifications, package visibility, overlay); companion distribution flow without out-of-band APK download.

| Phase | Exit tests |
| --- | --- |
| 0 | Trademark/licence/permission decisions recorded; native spike meets no-drop budget on API 32 + 16 KB page-size device |
| 1 | VPN consent→capture→four event types at receiver; JSON schema valid, PRI 110 (flow) / 109 (lifecycle), session+flow correlation, no payload/DNS/URL; UDP+TCP e2e, burst, backoff, overflow, doze, consent; notification/permission denial paths; no-drop under Phase 0 budget |
| 2 | Protocol detection and DNS view; GeoLite attribution shown; payload truncation; HTTP log for plaintext; app allowlist/exclusive mode; CSV |
| 3 | pcapng parses in standard tools; keylog block read/decrypt; companion wizard incl. cert and store link; HTTP server + exporters |
| 4 | Firewall allow/deny, threat notification, mask, block-rule JSON, dashboard counters |
| 5 | SOCKS5, port forwarding, IP modes, custom DNS, notices |
| 6 | Boot/restart, root mode, multi-user, SAF persistence |
| 7 | Backup/restore incl. sensitive confirm and version mismatch; external API + per-caller consent |
| 8 | TV/Wear/desktop/RTL/locales; licenses page; app log viewer |

## 12. Open decisions & roadmap

- Trademark fallback name/package ids if clearance fails (Phase 0 gate).
- Protocol-detection implementation: own vs copyleft-library swap — must be decided in Phase 0 (§2).
- GeoLite EULA obligations re-verified at each database update; alternative source if unacceptable (Phase 0 gate, Phase 2 delivery).
- Per-feed blacklist licences and attribution text, recorded before bundling (Phase 0 gate, Phase 4 delivery).
- Desktop companion protocol compatibility (wire format + keylog block) if our own implementation (Phase 3+).
- Visualization mask: overlay permission vs Play-compliant alternative (Phase 0 gate).
- Syslog roadmap: keep the `th_*` JSON keys and `th_schema_version` stable for SIEM parsers; publish example Splunk/Elastic extractions; CEF/LEEF mapping if a receiver needs it; TLS transport (RFC 5424/6514-style, mTLS + CA pinning), rfc5424 validator support, per-event filtering/ack rules.
- Icon set originality; min-market decisions.
