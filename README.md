# Idris 2 (JVM) — IntelliJ Plugin

IntelliJ IDEA support for [Idris 2](https://www.idris-lang.org/), talking
directly to the compiler's IDE-mode protocol. Built for (and tested against)
the [JVM backend](https://github.com/mmhelloworld/idris-jvm), but works with
any `idris2` build that speaks ide-mode protocol version 2.

## Features

- Syntax highlighting: instant lexical highlighting plus the compiler's own
  semantic highlighting (types, functions, data constructors, bound variables)
  after each successful load
- Compiler diagnostics with precise spans, refreshed when the file is saved;
  build progress is shown in the status bar, and long first-time builds are
  fine — the compiler session only times out after sustained silence (idle
  timeout), not on total duration
- Quick documentation (type-at-point via `:type-of`, docs via `:docs-for`)
- Go-to-definition across modules (semantic cache + `:name-at`)
- Type-driven interactive editing via Alt-Enter intentions:
  case split, add clause, proof search, generate definition, make lemma,
  make case, make with
- Idris REPL tool window multiplexed over the same compiler session

Known limitation: the ide-mode protocol has no rename and no position-based
find-references, so those are not offered.

## Setup

1. Install the plugin, then set the `idris2` executable under
   **Settings | Languages & Frameworks | Idris 2**. For the JVM backend point
   it at `<idris-jvm>/build/exec/idris2`. `JAVA_OPTS` is honored by that
   launcher script. **JVM backend builds must be 0.8.2 or newer** — older JVM
   builds have a blocking-`fEOF` runtime bug that stalls ide-mode replies
   (see docs/PROTOCOL.md); Scheme-built compilers of any version are fine.
2. Open any project containing `.idr` files. The plugin spawns one
   `idris2 --ide-mode` process per `.ipkg` root (falling back to the content
   root) and loads files as you open/save them.

## Development

- `./gradlew build` — build + unit tests (protocol codec, fake-server
  connection tests, lexer)
- `IDRIS2_EXEC=/path/to/idris2 ./gradlew test` — additionally runs integration
  tests against a real compiler
- `./gradlew runIde` — launch a sandbox IDE; open the `sample/` project
- `./gradlew verifyPlugin` — plugin verifier

The wire-protocol notes shared with future clients (VS Code) live in
[docs/PROTOCOL.md](docs/PROTOCOL.md). The `protocol/` package has no IntelliJ
dependencies by design.
