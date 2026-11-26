# TapHive SIEM Integration

TapHive emits RFC 3164 or RFC 5424 syslog with a JSON `th_*` message. The examples below are starting points; validate them in your own pipeline. The event contract is `th_schema_version: 2`.

## Event fields

| Field | Type | Meaning |
| --- | --- | --- |
| `th_schema_version` | int | Currently `2` |
| `th_session_id` | string | UUID for one capture session |
| `th_event` | string | `capture_start`, `flow_open`, `flow_close`, `capture_stop` |
| `th_timestamp` | RFC 3339 string | Event time (UTC) |
| `th_flow_id` | string | Flow correlation id; unique within a session |
| `th_uid` | int | Android UID; resolve to an app on-device |
| `th_ip_version` | int | `4` or `6` |
| `th_protocol` | int | `6` TCP, `17` UDP, `132` SCTP |
| `th_src_ip` / `th_src_port` | string / int | Source endpoint |
| `th_dst_ip` / `th_dst_port` | string / int | Destination endpoint |
| `th_bytes_sent` / `th_bytes_received` | long | Flow totals at close |
| `th_packets_sent` / `th_packets_received` | long | Packet totals at close |
| `th_status` | string | `CLOSED` or `STOPPED` |
| `th_dropped_events` | long | Sender drops at lifecycle emission |
| `th_packets_captured` / `th_events_forwarded` | long | Lifecycle aggregate counters |
| `th_dns_queries` / `th_dns_responses` | long | DNS queries/responses observed on UDP port 53 |
| `th_dns_timeouts` | long | Queries with no response within 5 seconds |
| `th_dns_nxdomain` / `th_dns_servfail` | long | DNS response error classes |
| `th_dns_other_errors` / `th_dns_malformed` | long | Other RCODEs / unparsable packets |

DNS counters cover plaintext UDP DNS on remote port 53 only. TCP DNS, DoH, and DoT are not visible.

`flow_open` is the only event that carries `th_uid` and the destination tuple. Join it to `flow_close` on `th_session_id + th_flow_id` to attribute the close counters. DNS names, country/org labels and app labels are local-only and are not in syslog.

## Splunk

Example `props.conf`:

```ini
[taphive:syslog]
SHOULD_LINEMERGE = false
LINE_BREAKER = ([\r\n]+)
TRANSFORMS-taphive = taphive_extract_json
```

Example `transforms.conf`:

```ini
[taphive_extract_json]
REGEX = (?s)(?:\<\d+\>.*?)(\{.*\})$
FORMAT = $1
```

Then use `spath` or the JSON extraction UI:

```spl
index=network sourcetype=taphive:syslog
| spath
| search th_event=flow_open
| table _time th_session_id th_flow_id th_uid th_dst_ip th_dst_port th_protocol
```

Correlate flow closes:

```spl
index=network sourcetype=taphive:syslog
| spath
| eval key=th_session_id."|".th_flow_id
| stats values(th_uid) as uid values(th_dst_ip) as dst values(th_dst_port) as port
        sum(th_bytes_sent) as sent sum(th_bytes_received) as received by key
```

## Elastic / ELK

Example Logstash pipeline:

```ruby
input {
  syslog { port => 5514 }
}

filter {
  grok {
    match => {
      "message" => [
        "<%{POSINT:priority}>%{SYSLOGTIMESTAMP:syslog_time} %{HOSTNAME:host} %{DATA:app}\[%{POSINT:procid}\]: %{GREEDYDATA:json}",
        "<%{POSINT:priority}>1 %{TIMESTAMP_ISO8601:syslog_time} %{HOSTNAME:host} %{DATA:app} %{POSINT:procid} %{DATA:msgid} - %{GREEDYDATA:json}"
      ]
    }
  }
  json {
    source => "json"
    target => "taphive"
  }
  date {
    match => ["[taphive][th_timestamp]", "ISO8601"]
    target => "@timestamp"
  }
}

output {
  elasticsearch {
    hosts => ["https://elastic.example:9200"]
    index => "taphive-%{+YYYY.MM.dd}"
  }
}
```

Map `th_src_ip`/`th_dst_ip` as `ip`, `th_timestamp` as `date`, integer fields as `long`/`integer`, and `th_flow_id`/`th_session_id` as `keyword`. Build the Kibana correlation on `th_flow_id`.

## Microsoft Sentinel

Recommended path: a small Linux AMA/Logstash relay receives UDP/TCP/TLS syslog from TapHive, parses the JSON, and sends it through the Logs Ingestion API/DCR to a custom table such as `TapHive_CL`.

KQL example:

```kusto
TapHive_CL
| where th_event_s == "flow_open"
| project TimeGenerated, th_session_id_s, th_flow_id_s, th_uid_d,
          th_src_ip_s, th_dst_ip_s, th_dst_port_d, th_protocol_d
| join kind=inner (
    TapHive_CL
    | where th_event_s == "flow_close"
    | project th_session_id_s, th_flow_id_s, th_bytes_sent_d, th_bytes_received_d
) on th_session_id_s, th_flow_id_s
```

Suggested DCR columns: `TimeGenerated` (datetime), `th_session_id_s`, `th_flow_id_s`, `th_event_s`, `th_uid_d`, `th_protocol_d`, `th_src_ip_s`, `th_dst_ip_s`, `th_src_port_d`, `th_dst_port_d`, `th_bytes_sent_d`, `th_bytes_received_d`, `th_packets_sent_d`, `th_packets_received_d`, `th_status_s`.

## Transport notes

- UDP/TCP syslog are cleartext. Use only on a trusted segment.
- TLS syslog uses TCP and the platform certificate store; certificate pinning and mTLS are not implemented.
- The sender's TCP/TLS connection is persistent with exponential backoff; a TLS failure never falls back to cleartext.
- The JSON schema remains v2. Transport and enrichment changes do not alter field names.

## Compatibility status

The v2 field names and RFC 3164/5424 framing have been device-verified with local receivers. The Splunk/Elastic/Sentinel configurations above are documentation examples and have not been executed against live vendor instances in this repository. Validate ingestion and field mappings before relying on them in production.
