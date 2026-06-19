#!/bin/sh
set -e
export PATH="/Users/marimuthu/bin/idris2-0.8.3/exec:$PATH"
cat > /tmp/core-ext/src/Core.idr <<'IDR'
module Core

||| The library's public greeting.
export
greeting : String
greeting = "hello from core v1"
IDR
( cd /tmp/core-ext && idris2 --install core.ipkg )
echo "Restored core v1 from /tmp/core-ext. Reload Idris Project to clear the error."
