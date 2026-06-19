#!/bin/sh
set -e
export PATH="/Users/marimuthu/bin/idris2-0.8.3/exec:$PATH"
cat > core/src/Core.idr <<'IDR'
module Core

||| The library's public greeting.
export
greeting : String
greeting = "hello from core v1"
IDR
( cd core && idris2 --install core.ipkg )
echo "core restored to v1 (greeting : String). Reload Idris Project to clear the error."
