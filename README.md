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
- Quick documentation: the symbol's doc comment together with its type
  signature, totality and visibility (`:type-of` + `:docs-for`), resolved to
  the exact symbol under the caret rather than every same-named definition
- Go-to-definition across modules (semantic cache + `:name-at`)
- File outline in the Structure tool window and the ⌘F12 File Structure popup;
  selecting a declaration jumps the editor to it
- Code folding for declarations, breadcrumbs showing the nested path to the
  caret (e.g. a function and its `where`-local), and highlight-usages (⌘F7) of
  the identifier under the caret
- Code completion backed by `:repl-completions`
- Type-driven interactive editing via Alt-Enter intentions:
  case split, add clause, proof search (with "next solution" cycling),
  generate definition (also cyclable), make lemma, make case, make with,
  introduce constructor, refine hole with an expression
- Holes tool window listing each hole's type and context, refreshed on every
  load; double-click navigates to the hole
- Idris REPL tool window multiplexed over the same compiler session
- Literate Idris (`.lidr`, bird-track style) with prose-aware highlighting

Known limitation: the ide-mode protocol has no rename and no position-based
find-references, so those are not offered.

## Feature tour

Compiler diagnostics with precise spans, refreshed on save — including the
compiler's "did you mean" hints on hover:

![Compiler diagnostics](docs/images/diagnostics.gif)

Quick documentation on the symbol under the caret - the full doc comment
alongside the type signature, totality and visibility:

![Quick documentation](docs/images/quick-doc.png)

Go-to-definition across modules:

![Go to definition](docs/images/goto-def.gif)

File navigation by declaration — the Structure tool window lists a file's
declarations and clicking one jumps the editor there; the ⌘F12 File Structure
popup does the same with type-to-filter and keyboard selection:

![Structure view and File Structure popup](docs/images/structure-nav.gif)

Code folding, breadcrumbs, and highlight usages — highlight every occurrence of
the name under the caret (⌘F7), watch the breadcrumb show the nested path to the
caret (here `stMain › quitWithError`), and collapse the file to an outline of
its declarations:

![Code folding, breadcrumbs and highlight usages](docs/images/folding-breadcrumbs-usages.gif)

Code completion backed by the compiler's `:repl-completions`:

![Code completion](docs/images/completion.gif)

Type-driven editing with Alt-Enter intentions — case split on a pattern
variable:

![Case split](docs/images/case-split.gif)

…and proof search to fill a hole:

![Proof search](docs/images/proof-search.gif)

The Holes tool window lists each hole with its type and context; double-click
jumps to the hole:

![Holes tool window](docs/images/holes.gif)

An Idris REPL multiplexed over the same compiler session:

![Idris REPL](docs/images/repl.gif)

## Setup

1. Install the plugin, then set the `idris2` executable under
   **Settings | Languages & Frameworks | Idris 2**. For the JVM backend point
   it at `<idris-jvm>/exec/idris2` (release zip) or
   `<idris-jvm>/build/exec/idris2` (source build). `JAVA_OPTS` is honored by
   that launcher script. **JVM backend builds must be 0.8.3 or newer**
   (available from
   [GitHub releases](https://github.com/mmhelloworld/idris-jvm/releases) and
   Maven Central) — older JVM builds have a blocking-`fEOF` runtime bug that
   stalls ide-mode replies (see docs/PROTOCOL.md), and the plugin refuses to
   start against them; Scheme-built compilers of any version are fine.
2. Open any project containing `.idr` files. The plugin spawns one
   `idris2 --ide-mode` process per `.ipkg` root (falling back to the content
   root) and loads files as you open/save them.

## Development

- `./gradlew build` — build + unit tests (protocol codec, fake-server
  connection tests, lexer)
- `IDRIS2_EXEC=/path/to/idris2 ./gradlew test` — additionally runs integration
  tests against a real compiler
- `./gradlew runIde` — launch a sandbox IDE (2024.2, the oldest supported
  platform); open the `sample/` project
- `./gradlew runIdeLatest` — same, but on the newest IntelliJ IDEA (2026.1;
  the unified distribution that replaced IDEA Community since 2025.3)
- `./gradlew verifyPlugin` — plugin verifier

### Signing and publishing

Marketplace uploads must be signed. Generate a key pair once:

```sh
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

then export the credentials and sign/publish:

```sh
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD=...   # the passphrase chosen above
export PUBLISH_TOKEN=...          # from Marketplace profile | My Tokens

./gradlew signPlugin     # produces build/distributions/<name>-signed.zip
./gradlew publishPlugin  # signs implicitly, then uploads
```

Note: `./gradlew verifyPluginSignature` is broken in IntelliJ Platform Gradle
Plugin 2.16.0 when the chain comes from `CERTIFICATE_CHAIN` (it mangles the
CLI arguments; exit value 64). Verify directly with the zip-signer CLI
instead:

```sh
java -cp ~/.gradle/caches/modules-2/files-2.1/org.jetbrains/marketplace-zip-signer/*/*/marketplace-zip-signer-*-cli.jar \
  org.jetbrains.zip.signer.ZipSigningTool verify \
  -in build/distributions/idris-intellij-<version>-signed.zip -cert chain.crt
```

The wire-protocol notes shared with future clients (VS Code) live in
[docs/PROTOCOL.md](docs/PROTOCOL.md). The `protocol/` package has no IntelliJ
dependencies by design.

## License

[BSD-3-Clause](LICENSE), same as
[idris-jvm](https://github.com/mmhelloworld/idris-jvm).
