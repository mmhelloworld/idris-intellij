#!/bin/sh
# Flip core.greeting to Nat (a breaking change for app) and reinstall, all
# outside the IDE — to demonstrate "Reload Idris Project".
set -e
export PATH="/Users/marimuthu/bin/idris2-0.8.3/exec:$PATH"
cat > core/src/Core.idr <<'IDR'
module Core

||| Now a Nat — breaks App.message, which expects a String.
export
greeting : Nat
greeting = 0
IDR
( cd core && idris2 --install core.ipkg )
echo "core reinstalled as v2 (greeting : Nat). Now use Reload Idris Project in the IDE."
