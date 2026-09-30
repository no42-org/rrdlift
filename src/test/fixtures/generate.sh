#!/bin/sh
# Copyright 2026 Ronny Trommer <ronny@no42.org>
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Generates fixtures on aarch64 (Horizon 36 image, RRDtool 1.8.0) and x86_64
# (Debian bookworm, RRDtool 1.7.2). The x86_64 Horizon image needs x86-64-v3
# and does not run under Docker emulation on Apple Silicon.
# Note: `--platform` pulls replace a local tag of the other architecture.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
res="$here/../resources/fixtures"
mkdir -p "$res"
docker run --rm --platform linux/arm64 -v "$here":/gen -v "$res":/out \
  --entrypoint sh opennms/horizon:36.0.4 /gen/gen.sh /out/aarch64
docker run --rm --platform linux/amd64 -v "$here":/gen -v "$res":/out debian:bookworm \
  sh -c 'apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -qq install -y rrdtool >/dev/null && sh /gen/gen.sh /out/x86_64'
