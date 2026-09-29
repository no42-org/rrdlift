# rrdlift: migrate OpenNMS RRD history into a Prometheus-compatible backend

Status: draft design, 2026-09-29.
Repository: `no42-org/rrdlift`.

## Goal

An OpenNMS operator moves from RRDtool persistence to a Prometheus-compatible backend through the Prometheus remote-write TSS plugin.
The history held in `share/rrd` must end up in the new backend as the same series the live collection writes.

**Success criterion:** after the backfill, the OpenNMS Measurements API on `integration` returns the same values for any time range before the cutover as the RRD fetch strategy returned on `rrd`.
Gauges match exactly.
Counter rates match within floating-point tolerance.

## Non-goals

- Recovering data RRD no longer holds.
  RRD has already consolidated older data; the backfill is lossless relative to what the files contain, not relative to what was once collected.
- Dual-write inside OpenNMS.
  A tee persistence strategy was designed and rejected (see Decisions).
- Backends reached through a TSS plugin other than the Prometheus remote-write plugin.
- Block-based backfill (`promtool tsdb create-blocks-from openmetrics`, `mimirtool backfill`).
- Newts sources.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Cutover, not dual-write. | OpenNMS supports exactly one strategy (`core/lib/.../TimeSeries.java:73`). A tee needs a core change and a release. After cutover `share/rrd` is frozen, so it serves as the snapshot with no copy. Cost: historical graphs are incomplete while the backfill runs, and rollback to `rrd` has a gap since cutover. |
| D2 | External Java CLI in its own repository. | Runs against any Horizon version. Uses JRobin for `.jrb`, a native reader for `.rrd`, and ports the proven stitching algorithm. Versioned independently of OpenNMS. |
| D3 | Write directly via Prometheus remote write. | The tool runs outside OpenNMS and cannot use the TSS plugin. Label identity is ensured by the label index (D4), not by sharing the write path. |
| D4 | Labels come from a label index: Prometheus series first, OpenNMS export second, minimal labels last. | The live labels include meta tags resolved through Mate interpolation (`MetaTagDataLoader`). Re-implementing that outside OpenNMS is fragile. The live series already carry the exact, current labels. |
| D5 | Counters become synthetic raw counters in the same series (`mtype=count`). | One continuous series per data source. `rate()` and the OpenNMS Newts-style rate both return the RRD rate. |
| D6 | Counter anchor is the exact `last_ds`; no 2^64 wrap emulation; negative values allowed. | See "Counter reconstruction". |
| D7 | Keep only AVERAGE beyond the finest resolution. | Prometheus stores one value per series and timestamp. Matches the existing Newts converter. With default RRAs this drops the 1-day MIN and MAX archives only. |
| D8 | Backfill newest data first, in two passes. | The last 7 days are the most viewed and fill in first. |
| D9 | Read RRDtool and JRobin files natively, with no `rrdtool` binary and no JRobin-to-RRDtool conversion. | JRobin installs migrate in one step. Today `jrobin-to-rrdtool-converter` needs 13 to 16 times the JRobin data size in extra disk space. The native RRDtool reader is exact and about 33 times faster than `rrdtool dump` (see "Spike: native RRDtool reader"). |

## Operator workflow

1. Stop OpenNMS.
2. Set `org.opennms.timeseries.strategy=integration`, install and configure the Prometheus remote-write plugin, configure meta tags, start OpenNMS.
   From here on nothing writes to `share/rrd`.
3. Wait at least one collection cycle of the longest configured collection interval, so every collected resource has a live series.
4. `rrdlift preflight`
5. `rrdlift snapshot-labels`
6. `rrdlift plan`
7. Optional, on OpenNMS versions that have it: `opennms:tss-export-labels --in orphans.txt --out labels-opennms.json` in the Karaf shell, then `rrdlift plan --opennms-labels labels-opennms.json`.
8. `rrdlift backfill`
9. `rrdlift verify`
10. When verify passes, archive or delete `share/rrd`.

`share/rrd` stays untouched until step 10 and is the rollback path.

## Backend requirements

Every live series has a sample at "now", so every backfilled sample is out of order for its series.

| Backend | Requirement |
|---|---|
| Prometheus | `--web.enable-remote-write-receiver`. `storage.tsdb.out_of_order_time_window` longer than the oldest RRD sample. Without it Prometheus rejects old samples as out of order or out of bounds, including the first sample of a new series. |
| Mimir, Cortex, Thanos Receive | Out-of-order window per tenant, same TSDB rules. Check the version for support. |
| VictoriaMetrics | No ordering rules. `-retentionPeriod` must cover the oldest sample, because older samples are dropped silently. Reset the rollup cache after the backfill (`/internal/resetRollupResultCache`) or run with `-search.disableCache` during it. Instant queries do not return a sample at exactly its timestamp, and samples newer than `-search.latencyOffset` (default 30s) are hidden, so rrdlift reads back with range selectors. VictoriaMetrics keeps about 12 significant digits, so verify it with `--tolerance 1e-11`. |
| Managed services | Check the provider's out-of-order and age limits. |

For all backends, retention must be longer than the oldest RRD sample.
With default RRAs that is one year plus the backfill duration; 400 days is a safe setting.

Prometheus-family backends hold out-of-order samples in memory and in a write-behind log until compaction.
`--rate` limits that pressure.

## Components

### 1. Reader

- Walks `<rrd-dir>/snmp` and `<rrd-dir>/response`.
- Store-by-group: a directory with `ds.properties`; each distinct value is a group and `<group>.rrd` (or `.jrb`) is the file.
- Store-by-metric: each `<metric>.meta` gives `GROUP`; the file is `<metric>.rrd` (or `.jrb`).
- Files without group information are reported and skipped.
- The format is detected from the file's first bytes, not its extension, so directories that mix `.jrb` and `.rrd` after a partial migration work.
  Files starting with the RRDtool cookie `RRD\0` go to the RRDtool reader; all others are opened with JRobin, which validates its own signature.
- Walker logic follows `features/newts-repository-converter` (`NewtsConverter.processStoreByGroupResources`, `processStoreByMetricResource`).

The walk yields one work item per file: `(file, resourceId, group, data sources)`, where `resourceId = <path relative to rrd-dir>/<group>`.
This equals the TSS `resourceId`, because TSS builds it from the same `ResourcePath` plus group (`TimeseriesPersistOperationBuilder.java:141`, `TimeseriesUtils.toResourceId`).

**RRDtool reader (native):**

- Own implementation of the `rrd_format.h` layout: header, data source definitions, RRA definitions, live head, PDP and CDP preparation, RRA pointers, data.
- Byte order and word size are detected from the position and bytes of the float cookie (`8.642135E130`).
- Rows are unrolled from the ring buffer starting at `cur_row + 1`, oldest first.
- A file parses only if the reader consumes exactly the file's size; anything else is a failed file.
- Verified layout: version `0003`, little-endian, 8-byte word (x86_64 and aarch64).
  Other layouts (`0001`, 32-bit, big-endian, `0004` or later) fall back to `rrdtool dump` when `--rrdtool` points to a binary, and are marked failed otherwise.
- JRobin's bundled reader (`org.jrobin.core.jrrd`) is **not** used: it misreads RRA definitions on 64-bit platforms (see spike).
- `--reader rrdtool` forces `rrdtool dump` for every file, as a cross-check.

**JRobin reader:** JRobin 1.6.0 `RrdDb`, opened read-only.

#### Spike: native RRDtool reader

Run 2026-09-29 with throwaway code that is not part of this repository.
Files were created with `rrdtool create/update` using the OpenNMS default RRAs: an 8-DS store-by-group file (COUNTER, DERIVE, GAUGE, a 32-bit wrap, 64-bit counters near 9.2e18, periodic 2-hour gaps), a single-DS gauge, a 19-character DS name with step 60, and a never-updated file.

| Platform | RRDtool | jrrd (JRobin 1.6.0) | Native reader |
|---|---|---|---|
| aarch64, `opennms/horizon:36.0.4` | 1.8.0 | Header and DS fields match; every RRA definition wrong (`pdp_per_row=0`, `rows=0`), then crash | All fields and all 48,180 values match |
| x86_64, `debian:bookworm` | 1.7.2 | Same failure | All fields and all 48,180 values match |

- jrrd cause: after the 20-byte `cf_nam` it reads `row_cnt` without skipping the 4 padding bytes that 8-byte alignment inserts.
- Speed on aarch64, 8-DS file: native 194 µs per file in a warm JVM; `rrdtool dump` 6.5 ms per file for the process alone, before XML parsing.
- Precision: `rrdtool dump` prints values with `%0.10e` (11 significant digits), so the comparison used a 1e-9 relative tolerance.
  The native reader returns the stored doubles exactly.
- Not covered: 32-bit and big-endian files, format `0001`, and the x86_64 Horizon image, which needs x86-64-v3 and did not run under emulation.

### 2. Sample generator

Ported from `NewtsConverter.generateSamples` (`NewtsConverter.java:703`), with the changes below.

**RRA stitching (unchanged):**
the `pdpPerRow = 1` RRA with the most rows, regardless of consolidation function, then AVERAGE RRAs ordered by step.
Each coarser RRA only contributes the range the finer one does not cover.
The coarse row that straddles a finer RRA's start contributes only the part the finer RRA does not cover.
For counters that row is clipped to the uncovered part and applied at its rate, so the seam shows no flat segment.
Gauges leave that part empty.
With default RRAs one data source yields 2016 + 1320 + 304 = 3,640 samples.

**Timestamps:** the end of each row's consolidation interval, in milliseconds.

**Gaps:** NaN rows produce no sample.

**Passes:** pass 1 emits samples newer than `last_update - 7d`, pass 2 emits the rest.

**Counter reconstruction** (data source types COUNTER and DERIVE):

- RRD stores per-second rates.
  The live plugin stores raw counter values tagged `mtype=count`.
- Anchor at the last update: `C(lastUpdate) = last_ds`, written as a sample of its own.
  The newest row ends at the step boundary `end <= lastUpdate`; the increase between `end` and `lastUpdate` is the PDP preparation value, so `C(end) = last_ds - pdp_prep value`.
  The same holds for JRobin, whose `accumValue` is that quantity (verified 2026-09-29).
- Walk rows backwards from `end`: `C(t - S) = C(t) - rate(t) * S`, with `S` the row's interval.
- `last_ds` is the exact raw value of the last poll before the stop.
  The first live sample is the exact raw value of the first poll after the start.
  The two join without an offset; any increase during the downtime appears as increase over the gap.
- **Change 1:** anchor on the exact `last_ds`.
  The converter rounds it down to a step multiple (`v - v % step`, `NewtsConverter.java:754`).
  That shifts the whole series by up to `step` units, can make the seam look like a decrease, and a decrease reads as a counter reset that draws a spike.
- **Change 2:** no `+2^64` wrap emulation; values may go negative.
  PromQL `rate()` sees a monotonic series.
  OpenNMS converts with `new Counter(value.longValue())` (`NewtsConverterUtils.java:96`); in two's complement a negative-to-positive crossing looks like a 64-bit wrap, and the Newts rate computes the correct delta.
  Values near 2^64 would lose precision as doubles (granularity of 2048 or more).
- NaN rows count as zero increase, as in the converter.
- An unknown `last_ds` (`U`) anchors `C(end) = 0` and writes no `lastUpdate` sample.
- ABSOLUTE data sources are reported and skipped; OpenNMS does not create them.

### 3. Label index

`snapshot-labels`:

- Reads the node-level directory list from the reader.
- For each, queries `GET <readUrl>/api/v1/series?match[]={resourceId=~"<dir>/.*"}&start=<oldest>` so no single response holds millions of series.
- Writes `labels-prometheus.json`: `(resourceId, __name__) -> full label set`.
- Sends `X-Scope-OrgID` when `--org-id` is set.

`plan` merges the index in this order and writes `plan.json` plus a report:

1. **Matched:** exactly one live label set for the key.
2. **Ambiguous:** more than one live label set for the key (for example a meta tag value changed mid-cycle). Skipped and listed.
3. **OpenNMS export:** key found in `labels-opennms.json`.
4. **Orphan:** no source. Written with minimal labels `__name__`, `resourceId`, `mtype`, or skipped with `--skip-orphans`.
   Also written to `orphans.txt` as input for `opennms:tss-export-labels`.

`plan` also writes `not-migrated.txt` (`--not-migrated`).
It has one tab-separated line per entry that will not be written: class, resourceId or file, data source name, note.
The lines are sorted, after a header line `# class\tresource\tds\tnote` (tab-separated).

A matched or exported label set whose `mtype` label differs from the data source type (`count` for COUNTER and DERIVE, `gauge` for GAUGE) is not written.
The entry is classed `MTYPE_MISMATCH` and listed.

The report states counts per class, per top-level directory, and the estimated sample count and duration at the configured rate.
Nothing is written to the backend by `plan`.

**Sanitizers:** `__name__` uses the plugin's `sanitizeMetricName`, other label names `sanitizeLabelName`, values `sanitizeLabelValue` (truncate to 2048).
These are copied from `CortexTSS.java:671-704`.
A test pins them against the plugin's published jar.
Matched and export entries are used as-is; the sanitizers apply only to minimal labels and to computing the lookup key.

**External tags** (string attributes such as `ifAlias`) are not labels.
The plugin stores them in the OpenNMS KV store.
For matched resources they are already there.
Orphans get none.

### 4. OpenNMS label export (optional, core change)

Karaf command `opennms:tss-export-labels --in <orphans.txt> --out <labels.json>`.
For each `resourceId` it builds the collection resource and runs `MetaTagDataLoader` with the configured `MetaTagConfiguration`, then writes the intrinsic and meta tags after sanitizing them like the plugin.
Resources whose node no longer exists produce no entry.
This is a separate Jira story for the next Horizon release; the tool works without it.

### 5. Preflight

`preflight` refuses to start the backfill unless all checks pass:

1. Remote write to `<writeUrl>` accepted for a sample at 60 s in the past on series `rrdlift_probe{run="<uuid>"}`.
2. Remote write accepted for a sample on the same series at the oldest timestamp the plan will write.
3. Both samples read back with a range selector (`rrdlift_probe{run="<uuid>"}[3600s]`, evaluated 60 s after the sample).
   The read-back requires a raw sample at exactly the written timestamp with the written value.
   The current probe sample is written 60 s in the past, so that VictoriaMetrics `-search.latencyOffset` does not hide it.
   This catches VictoriaMetrics silently dropping samples beyond retention.
4. The query API answers `/api/v1/series`.

Each failure names the setting to change: out-of-order window, retention, remote-write receiver, cache, or tenant header.

### 6. Writer

- Remote write 1.0: protobuf `WriteRequest`, snappy, `Content-Type: application/x-protobuf`, `X-Prometheus-Remote-Write-Version: 0.1.0`.
- Batches of `--batch-samples` (default 2000), grouped per series.
- Throttled by a token bucket at `--rate` samples per second, `--threads` workers.
- Samples are deterministic.
  A retry resends identical values, which Prometheus-family backends accept as duplicates.
- HTTP 5xx, 429 or connection error: exponential backoff up to `--max-retries`, then the whole job pauses and exits non-zero.
  Skipping would lose data.
- HTTP 4xx other than 429: the file is marked failed with the response body, and the job continues.

### 7. Checkpoint

`state/<pass>.jsonl` in the working directory, one line per finished file: path, pass, sample count, status, error.
A file is done only after all its batches are acknowledged.
`backfill` resumes from the state files; `--retry-failed` retries only failed files.
Pass 2 starts after pass 1 has finished for every file.

### 8. Verify

`verify [--sample <percent>|--all]`:

- Regenerates samples from `share/rrd` for the selected files.
- Reads them back with `query_range` at the raw step for each RRA segment.
- Gauges must match exactly.
  Counters must match on consecutive deltas.
- `--tolerance` (default 0, exact) is relative to the stored values.
  Gauges compare with it directly.
  For counters, the allowed delta error is the tolerance times the largest of the four values involved (two expected, two stored).
  The error that storage precision puts on a delta scales with the counter value, not with the delta.
- VictoriaMetrics keeps about 12 significant digits, so verify it with `--tolerance 1e-11`.
- Default selection: 1% random files plus every file that needed a retry.
- Before the sample check, verify compares the checkpoint with the plan.
  It reports how many writable files are done in both passes and lists up to 20 that are not.
  A file is not done when its last checkpoint line in either pass is missing or FAILED.
- It also prints the plan's not-migrated entries per class under `NOT MIGRATED (by plan):`.
  These do not change the exit code.
- Exit 1 on any mismatch or any file not done, otherwise 0.

`backfill` reports the same totals from the checkpoint after every run that is not paused.
It exits 1 while any writable file is not done in both passes, also on a rerun that had nothing to do.

Cutover is complete when verify exits 0.
Review `not-migrated.txt` before deleting `share/rrd`, because nothing listed there is in the backend.

## CLI summary

```
rrdlift preflight        --rrd-dir DIR --write-url URL --read-url URL [--org-id ID]
rrdlift snapshot-labels  --rrd-dir DIR --read-url URL [--org-id ID] --out labels-prometheus.json
rrdlift plan             --rrd-dir DIR --labels labels-prometheus.json [--opennms-labels FILE] [--skip-orphans]
rrdlift backfill         --plan plan.json --write-url URL [--rate N] [--threads N] [--retry-failed]
rrdlift verify           --plan plan.json --read-url URL [--sample P|--all]
```

`--rrd-dir` defaults to `/opt/opennms/share/rrd`.
Every command that reads RRD files accepts `--reader native|rrdtool` (default `native`) and `--rrdtool PATH` for the fallback.
Only a Java runtime is required.

## Testing

**Unit:**

- Native RRDtool reader against `rrdtool dump` on golden fixtures generated on x86_64 and aarch64 (the spike files and generator are the starting point), exact for all fields and values within dump precision.
- Format detection on mixed `.jrb` and `.rrd` directories; a truncated file fails the size check.
- RRA stitching, porting the converter's existing tests.
- Counter reconstruction against RRD files created in the test with `rrdtool create/update` from a known raw sequence; deltas must match exactly.
- Exact anchor: the reconstructed final value equals `last_ds` bit for bit.
- Negative values and a zero crossing, checked through Newts `Counter` rate semantics.
- Store-by-group and store-by-metric walking; missing group information.
- Sanitizers pinned against the plugin jar.
- Index merge priority and ambiguous detection.
- Writer retry and pause classification per HTTP status.

**Integration (Testcontainers):**

- Prometheus with the out-of-order window: backfill a real RRD fixture, verify passes.
- Prometheus without the window: preflight fails with the out-of-order message.
- VictoriaMetrics: backfill and verify pass; preflight catches a retention shorter than the fixture.

**End to end (lab):**

- Horizon 36 with the remote-write plugin and meta tags configured.
- Collect on `rrd` long enough to populate at least the 5-minute and 1-hour RRAs (an `rrdtool`-generated fixture may stand in for older data).
- Record Measurements API results for a set of resources and ranges.
- Cut over, run the workflow, compare Measurements API results for the same ranges.
- Include one deleted interface to exercise the orphan path.

## Known limits

- 1-day MIN and MAX archives are not migrated (D7).
- Historical graphs are incomplete until the backfill finishes (D1).
- Rollback to `rrd` loses data collected since cutover (D1).
- Orphans lack external tags.
  The plugin's `findMetrics` only looks back `maxSeriesLookback` (default 90 days), so orphans older than that do not appear in the OpenNMS resource tree.
  They remain queryable from PromQL and Grafana.
- Directories from a former `storeByForeignSource` layout show up as orphans.
- The tool follows the remote-write plugin's label scheme and must track changes to its sanitizers.

## Open items

- Confirm JRobin 1.6.0 is published to a public Maven repository (it resolved from the OpenNMS repository in the spike); otherwise vendor it.
- Collect real `share/rrd` samples from 32-bit or big-endian installs, if any are still in the field, to extend the native reader's verified layouts.
- Measure real throughput to replace the duration estimate in `plan`.
- File the Jira story for `opennms:tss-export-labels`.
