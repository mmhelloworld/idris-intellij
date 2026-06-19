# Multi-project sample (`app` depends on `core`)

Two Idris packages in one IDE project:

- `core/` — library exposing `Core.greeting`
- `app/`  — `App.message` uses `Core.greeting`; `app.ipkg` has `depends = core`

`core` is consumed as an **installed package** (in the idris2 package database).
There are two ways its changes reach `app`, exercising the two halves of the fix.

## One-time setup

```sh
export PATH="/Users/marimuthu/bin/idris2-0.8.3/exec:$PATH"
( cd core && idris2 --install core.ipkg )   # install core v1 into the package db
( cd app  && idris2 --build   app.ipkg )    # sanity check it resolves
```

## A. Automatic — dependency rebuilt in-tree (the common case)

`idris2 --install core.ipkg` *builds first*, so it regenerates
`core/build/ttc/.../Core.ttc` **inside the opened project**. The automatic
`.ttc`/`.ttm` watcher (`IdrisBuildOutputListener`) sees that and invalidates the
cache — no manual step.

1. Open `app/src/App.idr` — loads clean, `message : String`.
2. `./break-core.sh` (edits `core/src/Core.idr` to `Nat` and reinstalls).
3. Return focus to the IDE: `App.idr` shows the type error **on its own** (a VFS
   refresh fires on frame activation, the watcher invalidates the cache, the
   daemon reloads). `./restore-core.sh` + refocus clears it.

## B. Manual — dependency rebuilt fully out-of-tree

When the dependency is rebuilt somewhere outside the project, nothing under the
project changes, so the watcher can't see it. This is what **Reload Idris
Project** is for. `break-core-out-of-tree.sh` rebuilds/installs `core` from a
copy at `/tmp/core-ext`.

1. With `app/src/App.idr` open and clean, run `./break-core-out-of-tree.sh`.
2. Return focus to the IDE: `App.idr` still shows **no error** — nothing in the
   project changed, so the cached load survived.
3. **Tools ▸ Reload Idris Project** (or the refresh button in the Idris REPL tool
   window, or ⇧⇧ "Reload Idris Project"). The type error now appears — the
   out-of-tree rebuild was picked up without restarting the IDE.
4. Restore: `./restore-core-out-of-tree.sh`, then Reload again.
