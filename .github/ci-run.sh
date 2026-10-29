#!/bin/bash
# Runs a command, keeps the full log, and on failure publishes the important lines as workflow annotations
# (visible on the run page and through the API) so build errors are easy to read and share.
LOG="$RUNNER_TEMP/ci-$$.log"
"$@" 2>&1 | tee "$LOG"
code=${PIPESTATUS[0]}
if [ "$code" -ne 0 ]; then
  grep -nE '^e: |error:|FAILED|AssertionError|What went wrong|Execution failed|Unresolved|Caused by' "$LOG" | head -60 > "$LOG.sel"
  n=0
  while mapfile -t -n 6 chunk && [ ${#chunk[@]} -gt 0 ] && [ $n -lt 9 ]; do
    msg=$(printf '%s%%0A' "${chunk[@]}" | sed 's/\r//g' | cut -c1-3500)
    echo "::error title=Build error::${msg}"
    n=$((n+1))
  done < "$LOG.sel"
fi
exit "$code"
