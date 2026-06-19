#!/bin/sh
# Break `core` by rebuilding/installing it from a copy OUTSIDE the opened
# project (/tmp/core-ext). Nothing under sample-multi changes, so the automatic
# .ttc/.ttm watcher cannot see it -- this is the case that needs the manual
# "Reload Idris Project" action.
set -e
export PATH="/Users/marimuthu/bin/idris2-0.8.3/exec:$PATH"
cat > /tmp/core-ext/src/Core.idr <<'IDR'
module Core

||| Now a Nat -- breaks App.message, which expects a String.
export
greeting : Nat
greeting = 0
IDR
( cd /tmp/core-ext && idris2 --install core.ipkg )
echo "Installed core v2 (greeting : Nat) from /tmp/core-ext."
echo "Nothing under the project changed -> App.idr will NOT auto-update."
echo "Use Tools > Reload Idris Project to pick it up."
