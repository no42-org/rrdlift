# rrdlift

Migrates OpenNMS RRDtool and JRobin history into Prometheus-compatible backends via remote write.
The history lands in the same series the OpenNMS Prometheus remote-write plugin writes live, so graphs continue across the cutover.
It reads both file formats natively and needs only a Java 17 runtime; no `rrdtool` binary and no JRobin-to-RRDtool conversion.

## How it works

You switch OpenNMS to the timeseries integration layer with the Prometheus remote-write plugin first.
From then on OpenNMS writes new data to the backend and `share/rrd` no longer changes.
rrdlift then copies the history out of `share/rrd` in the background.

Counters are written as synthetic raw counters anchored on the last reading, so `rate()` over the backfilled range returns the rates RRD stored.
Only AVERAGE archives are used beyond the finest resolution; the 1-day MIN and MAX archives are not migrated.

### Precision note

RRDtool wraps 32-bit counters with 2^32 - 1, not 2^32.
rrdlift reproduces the rates RRD stored, including that quirk.

## Backend requirements

Every backfilled sample is older than the live samples of its series.
The backend must accept such out-of-order samples as far back as your oldest RRD data, usually about one year, and its retention must cover that range.

| Backend | Setting |
|---|---|
| Prometheus | `--web.enable-remote-write-receiver`, `storage.tsdb.out_of_order_time_window` longer than the oldest RRD sample, retention longer than that |
| Mimir, Cortex, Thanos Receive | out-of-order window per tenant; pass `--org-id` for the tenant |
| VictoriaMetrics | `-retentionPeriod` covering the oldest sample; `-search.disableCache` during the backfill. VictoriaMetrics stores about 14 significant digits, so run `verify` with `--tolerance 1e-11` against it. Instant queries hide recent samples via `-search.latencyOffset`, but rrdlift reads back with range selectors so it is not affected. |

`rrdlift preflight` checks this before anything is written.

## Workflow

1. Stop OpenNMS, set `org.opennms.timeseries.strategy=integration`, install and configure the Prometheus remote-write plugin, start OpenNMS.
2. Wait for at least one collection cycle of your longest collection interval.
3. Check the backend:
   ```
   rrdlift preflight --rrd-dir /opt/opennms/share/rrd --write-url http://prometheus:9090/api/v1/write --read-url http://prometheus:9090
   ```
4. Copy the live labels:
   ```
   rrdlift snapshot-labels --rrd-dir /opt/opennms/share/rrd --read-url http://prometheus:9090
   ```
5. Plan, and read the report:
   ```
   rrdlift plan --rrd-dir /opt/opennms/share/rrd --labels labels-prometheus.json
   ```
   `MATCHED` data sources join a live series.
   `ORPHAN` data sources have no live series and get the minimal labels `__name__`, `resourceId`, `mtype`; pass `--skip-orphans` to leave them out.
   `AMBIGUOUS` data sources match more than one live series and are skipped.
6. Backfill; rerun the same command to resume after an interruption:
   ```
   rrdlift backfill --plan plan.json --write-url http://prometheus:9090/api/v1/write --rate 20000
   ```
   Exit code 0 means done, 1 means some files failed (`--retry-failed` retries them), 2 means paused because the backend was unavailable.
   Every command exits 64 on invalid options.
7. Verify:
   ```
   rrdlift verify --plan plan.json --read-url http://prometheus:9090
   ```
   `--tolerance` is relative; for counters the allowed delta error is the tolerance times the counter value.
8. When verify reports zero mismatches, archive or delete `share/rrd`.
   Until then it is your rollback path.

## Build

```
make build        # target/rrdlift-<version>.jar
make test
make integration  # needs Docker
```

## License

AGPL-3.0-or-later.
Parts are derived from OpenNMS (`NewtsConverter`) and the OpenNMS Prometheus remote-write plugin (`CortexTSS`).
