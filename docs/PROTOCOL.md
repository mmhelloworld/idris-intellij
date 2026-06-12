# Idris 2 IDE-Mode Protocol — Client Implementation Notes

Ground truth verified against the Idris 2 compiler source (paths below are in
the [idris-jvm](https://github.com/mmhelloworld/idris-jvm) tree, identical to
upstream Idris2 for these modules). This document is the shared wire spec for
this IntelliJ plugin and any future client (e.g. a VS Code extension).

## Transport & framing

- Start the server with `idris2 --ide-mode` (stdin/stdout) or
  `--ide-mode-socket [host:port]` (TCP).
- Every message: `XXXXXX<payload>` where `XXXXXX` is the payload length as
  **6 lowercase hex digits**, zero-padded (`Idris/IDEMode/Commands.idr:41-47`).
- The payload is the rendered s-expression **plus `"\n"`, and the newline is
  counted in the length**.
- The length counts **characters (Unicode codepoints), not bytes** — Idris
  string length is codepoint-based (`Idris/IDEMode/REPL.idr:105-116` reads N
  characters). In Java/Kotlin use `codePointCount`; in JS count codepoints, not
  UTF-16 units. Make readers defensive: if the payload is unbalanced after N
  units, read on to the newline.
- On startup the server sends `(:protocol-version 2 1)` (`REPL.idr:510`).
- The server is **strictly sequential** (`REPL.idr:462-490`): one in-flight
  request at a time; the client must queue.

### JVM-backend stdio quirk (verified empirically against idris2-jvm 0.8.x)

The server loop calls `fEOF` between reading a request and replying, and the
JVM runtime's `fEOF` (`ByteBufferIo.isEof`) performs a **blocking read** when
its buffer is empty. A spec-framed request leaves the buffer exactly empty, so
the reply to command N is withheld until command N+1's bytes arrive (a single
command never gets a reply at all). Workaround used by this client: append a
**sacrificial sync line** of six non-hex characters plus newline (`??!!??\n`)
after every frame. It keeps the buffer non-empty (reply flushes immediately),
and the server consumes it as one unparseable line, emitting a spurious
`(:return (:error "Parse error: Can't recognise token...") <last-id>)` that
clients must ignore (the id belongs to an already-completed request). The
trailer is harmless on Scheme-built compilers. The proper fix is in the
idris-jvm runtime: `fEOF` should report the EOF flag without triggering a
blocking read (C `feof` semantics).

## S-expressions (`Protocol/SExp.idr`)

- Strings escape **only** `\` and `"`.
- Booleans render as `:True` / `:False`.
- Symbols render with a leading colon; integers as decimal.
- Requests are `(<command-sexp> <request-id-integer>)`.

## Reply taxonomy (`Protocol/IDE.idr:99-113`)

| Wire form | Meaning |
|---|---|
| `(:return (:ok <result> [<spans>]) <id>)` | final success |
| `(:return (:error "<msg>" [<spans>]) <id>)` | final failure (no positions!) |
| `(:output (:ok (:highlight-source (<hl>...))) <id>)` | async semantic highlights |
| `(:write-string "<s>" <id>)` | async console output |
| `(:set-prompt "<s>" <id>)` | ignore |
| `(:warning ("<file>" (<sl> <sc>) (<el> <ec>) "<msg>" [<spans>]) <id>)` | diagnostic |

**Compile errors also arrive as `:warning`** (`Idris/REPL/Common.idr:104-140`);
the final `:error` return for `:load-file` carries no positions. Classify
severity client-side: if the load failed, messages not starting with
"Warning"/deprecation are errors.

## Position indexing — THE TRAP ZONE

- **Server → client: 0-based line and column, end-exclusive**
  (`Idris/IDEMode/Commands.idr:18-23` passes raw internal values through).
- **Client → server: editing commands take 1-based lines**
  (`Idris/REPL.idr` `processEdit` subtracts 1).
- Columns going to the server are inconsistent per command. Use the 2-argument
  forms (`(:case-split L "name")` — column 0 means "whole line") to sidestep.
  `:type-of name L C` takes a 0-based column.
- Diagnostic/highlight filenames are relative to the process working directory;
  `:name-at` results are **absolute** (`REPL.idr:416`).

## Process model

- `:load-file` triggers `findIpkg`, which searches **upward from the process
  cwd** for an `.ipkg`, chdirs to it, and applies `sourcedir`
  (`Idris/Package.idr:1098-1118`). Spawn one process per nearest-`.ipkg` root,
  with cwd there, and send root-relative paths.
- The compiler holds **one loaded file at a time**; all other commands operate
  on the last-loaded file. Reload on file switch.
- The compiler reads from **disk** — save before loading.

## v1 command set & response shapes

All editing commands return display text — **the client applies the edits**:

| Command | `:ok` result | Edit semantics |
|---|---|---|
| `(:load-file "rel.idr")` | `()` after async warnings/highlights | — |
| `(:interpret "e")` | result string | print |
| `(:type-of "n")` / `(:type-of "n" L C)` | `"n : T"` | hover |
| `(:docs-for "n" :full)` | docs string | hover |
| `(:case-split L "n")` | newline-joined clauses | replace line L |
| `(:add-clause L "n")` | one clause incl. indentation | insert after line L |
| `(:proof-search L "n")` | expression | replace the `?n` hole token |
| `(:generate-def L "n")` | (multi-line) definition | insert after line L |
| `(:make-lemma L "n")` | `(:metavariable-lemma (:replace-metavariable "app") (:definition-type "sig"))` | replace hole with app; insert sig above enclosing decl |
| `(:make-case L "n")` / `(:make-with L "n")` | multi-line string | replace line L |
| `(:name-at "name")` | `(("Ns.name" (:filename "/abs") (:start l c) (:end l c)) ...)` | goto targets |
| `(:metavariables 80)` | holes with premises and types | holes view |
| `(:repl-completions "prefix")` | `((c1 c2 ...) "")` | completion |

Not implemented server-side (`todoCmd` in `REPL.idr`): `:add-missing`,
`:name-at` with a position argument, `:who-calls`, `:apropos`. There is **no
rename and no position-based find-references** in the protocol.

## Semantic highlighting (`Protocol/IDE/Highlight.idr`)

Emitted during `:load-file` (default on). Each entry is
`(<fileContext> (<props>))`:

- Full form props: `((:name "n") (:namespace "Ns") (:decor :function)
  (:implicit :False) (:key "") (:doc-overview "") (:type ""))` —
  `doc-overview`/`type` are always empty (compiler TODO). The location is the
  **occurrence**, not the definition.
- Lightweight form props: `((:decor :keyword))`.
- Decorations: `:comment :type :function :data :keyword :bound :namespace
  :postulate :module` (`Protocol/IDE/Decoration.idr`).
- **Go-to-definition** = cached highlight name at caret → `(:name-at "name")`
  with the BARE name (qualified queries return `()`), then filter the results
  by the highlight's namespace-qualified name.
