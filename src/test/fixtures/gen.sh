#!/bin/sh
# Copyright 2026 Ronny Trommer <ronny@no42.org>
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Creates RRD files the way OpenNMS does (default RRAs), fills them with
# deterministic data and writes a `rrdtool dump` XML next to each file.
# grp.raw keeps the last 8 days of raw updates for counter tests.
set -eu
out=${1:?output directory}
mkdir -p "$out"
cd "$out"
rm -f ./*.rrd ./*.xml ./*.raw

step=300
now=$(date +%s)
start=$(( (now - 400*86400) / step * step ))
end=$(( now / step * step ))
RRAS="RRA:AVERAGE:0.5:1:2016 RRA:AVERAGE:0.5:12:1488 RRA:AVERAGE:0.5:288:366 RRA:MAX:0.5:288:366 RRA:MIN:0.5:288:366"

rrdtool create grp.rrd --start $((start - step)) --step $step \
  DS:ifInOctets:COUNTER:600:0:U DS:ifOutOctets:COUNTER:600:0:U \
  DS:ifHCInOctets:COUNTER:600:0:U DS:ifHCOutOctets:COUNTER:600:0:U \
  DS:ifInErrors:COUNTER:600:0:U DS:ifInDiscards:DERIVE:600:0:U \
  DS:ifSpeed:GAUGE:600:0:U DS:cpuPercent:GAUGE:600:0:100 $RRAS
rrdtool create icmp.rrd --start $((start - step)) --step $step DS:icmp:GAUGE:600:0:U $RRAS
rrdtool create longname.rrd --start $((start - 60)) --step 60 DS:abcdefghijklmnopqrs:GAUGE:120:U:U RRA:AVERAGE:0.5:1:1440 RRA:AVERAGE:0.5:60:720
rrdtool create empty.rrd --start $((start - step)) --step $step DS:x:GAUGE:600:U:U $RRAS

awk -v s=$start -v e=$end -v st=$step 'BEGIN {
  srand(42); in32=4000000000; hc=9.2e18; hco=1.5e12; err=0; disc=0; n=0
  for (t=s; t<=e; t+=st) {
    n++
    if (n % 2600 >= 2576) continue
    in32=(in32 + int(rand()*5e7)) % 4294967296
    out32=int(t % 4294967296)
    hc=hc + int(rand()*1e9); hco=hco + int(rand()*1e8)
    if (rand() < 0.01) err=err + int(rand()*5)
    disc=disc + int(rand()*3)
    printf "%d:%d:%d:%.0f:%.0f:%d:%d:1000000000:%.3f\n", t, in32, out32, hc, hco, err, disc, rand()*100 > "grp.upd"
    printf "%d:%.6f\n", t, 0.5 + rand()*20 > "icmp.upd"
  }
}'
awk -v s=$(( now - 2*86400 )) -v e=$now 'BEGIN { srand(7); for (t=int(s/60)*60; t<=e; t+=60) printf "%d:%.9f\n", t, sin(t/3600)*1e6 > "longname.upd" }'

for f in grp icmp longname; do
  xargs -n 500 rrdtool update $f.rrd < $f.upd
done
tail -n 2304 grp.upd > grp.raw
rm -f ./*.upd
for f in *.rrd; do rrdtool dump "$f" > "${f%.rrd}.xml"; done
uname -m > arch.txt
rrdtool --version | head -1 >> arch.txt
