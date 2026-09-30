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
| VictoriaMetrics | `-retentionPeriod` covering the oldest sample. `-search.disableCache` during the backfill. VictoriaMetrics stores about 14 significant digits, so run `verify` with `--tolerance 1e-11` against it. Instant queries hide recent samples via `-search.latencyOffset`, but rrdlift reads back with range selectors so it is not affected. |

`rrdlift preflight` checks this before anything is written.

A Prometheus out-of-order window of a year raises memory use and compaction load while the backfill runs.
Use a moderate `--rate`, or prefer VictoriaMetrics or Mimir for very large repositories.

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
   `ORPHAN` data sources have no live series and get the minimal labels `__name__`, `resourceId`, `mtype`.
   Pass `--skip-orphans` to leave them out.
   `AMBIGUOUS` data sources match more than one live series and are skipped.
   `MTYPE_MISMATCH` data sources match a live series whose `mtype` label contradicts the RRD data source type, and are skipped.
   `not-migrated.txt` lists every data source or file that will not be written, with its class and the reason.
6. Backfill; rerun the same command to resume after an interruption:
   ```
   rrdlift backfill --plan plan.json --write-url http://prometheus:9090/api/v1/write --rate 20000
   ```
   After each run backfill reports how many planned files are done in both passes, counted from the checkpoint.
   Exit code 0 means every planned file is done in both passes.
   Exit code 1 means some files are not done yet, or the run aborted on a local error such as an unwritable state directory.
   A plain rerun does not retry failed files and still exits 1.
   Use `--retry-failed` to retry the failed files.
   Exit code 2 means paused because the backend was unavailable.
   Every command exits 64 on invalid options.
7. Verify:
   ```
   rrdlift verify --plan plan.json --read-url http://prometheus:9090
   ```
   Verify first reports how many planned files are done in both passes and lists up to 20 that are not.
   It then prints the not-migrated counts per class under `NOT MIGRATED (by plan):`.
   Then it reads a sample of files back and compares them.
   It exits 1 on any mismatch or any file not done, and 0 otherwise.
   Not-migrated entries do not change the exit code.
   Verify compares the backend with rrdlift's own sample generator, not with an independent reconstruction of the RRD files.
   `--tolerance` is relative.
   For counters the allowed delta error is the tolerance times the counter value.
   VictoriaMetrics needs `--tolerance 1e-11`.
8. Run `rrdlift verify --plan plan.json --read-url http://prometheus:9090 --all`.
   Cutover is complete when it exits 0.
   By default verify samples only 1% of files plus every file that needed a retry.
   Review `not-migrated.txt` before archiving or deleting `share/rrd`, because nothing listed there is in the backend.
   Until then `share/rrd` is your rollback path.

## Build

```
make build        # target/rrdlift-<version>.jar
make test
make integration  # needs Docker
```

## License

AGPL-3.0-or-later.
Parts are derived from OpenNMS (`NewtsConverter`) and the OpenNMS Prometheus remote-write plugin (`CortexTSS`).
