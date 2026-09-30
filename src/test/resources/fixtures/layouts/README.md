# Storage-layout fixtures

These trees were written by Horizon 36.0.4 (RRDtool, aarch64) in the #7 storage-layout lab on 2026-09-30.
The agents were snmpsim Net-SNMP simulators on private lab addresses, so the trees hold no customer data.

| Directory | storeByForeignSource | storeByGroup |
|---|---|---|
| `fs-group` | true | true |
| `fs-metric` | true | false |
| `id-group` | false | true |
| `id-metric` | false | false |

Each tree keeps one node-level group (`ucd-loadavg`), the `mib2-X-interfaces` data of `eth0` and `eth2`, and `response/<ip>/icmp` of node `router-a`.
`eth2` was removed from the agent before the cutover, so it has no live series.
`ds.properties` lists only the groups kept here.

`labels-prometheus.json` holds the label sets the Prometheus remote-write plugin wrote live for these resources, taken from `rrdlift snapshot-labels`.
`opennms/etc/opennms.properties.d/zz-lab.properties` is the lab's layout and meta-tag configuration.
