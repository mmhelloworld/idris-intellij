module Collections

import Java.Util

-- Java collections through the JVM FFI. The binding module `Java/Util.idr` is
-- generated on demand by `idris2 --jvm-ffi-import`, which only emits the members
-- you actually reference (here, just `ArrayList.new`). Completion offers the
-- class's FULL surface anyway — members not yet generated are tagged
-- `java (import)`, and accepting one (re)generates its binding in the background
-- so the reference resolves.
--
-- Demo: after `ArrayList.new` below, add another statement and type
-- `ArrayList.` to see the whole surface; accept e.g. `add` to import it.
main : IO ()
main = do
  xs <- the (IO (ArrayList String)) ArrayList.new
  _ <- ArrayList.getFirst
  pure ()
