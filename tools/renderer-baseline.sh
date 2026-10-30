#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 23)
  export JAVA_HOME
fi
revision=$(git rev-parse --short HEAD)
frames=${FRAMES:-600}
warmup=${WARMUP_FRAMES:-120}
capture=${CAPTURE:-renderer-baseline.png}
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dstratadiver.benchmarkFrames=$frames -Dstratadiver.warmupFrames=$warmup -Dstratadiver.capture=$capture -Dstratadiver.revision=$revision"
printf 'Renderer baseline: revision=%s warmup=%s frames=%s capture=%s\n' "$revision" "$warmup" "$frames" "$capture"
mvn -q compile exec:exec
