# Idris 2 (JVM) Changelog

## [Unreleased]

## [0.1.2]

### Added

- File **structure view** (outline) of modules, `data`/`record`/`interface` declarations and their members, and top-level functions.
- **Code folding** for declarations and their indented bodies.
- **Breadcrumbs** showing the enclosing declaration at the caret.
- **Highlight usages** of the identifier under the caret.
- **Reload Idris Project** action (Tools menu, Idris REPL toolbar, and Find Action) — drops the cached compiler results and re-analyses open files so changes built outside the IDE are picked up, without restarting the IDE or the compiler processes. Use this when a dependency was rebuilt/installed from the command line into a location outside the project's content roots.

### Fixed

- A project no longer keeps showing stale diagnostics/highlights after a dependency it imports is rebuilt outside the IDE. The cached `:load-file` result was only invalidated by the edited file's own modification stamp, so a rebuilt dependency was ignored until the file was edited or the IDE restarted. Build outputs (`.ttc`/`.ttm`) under the project's content roots are now watched and invalidate the caches automatically. For dependencies rebuilt entirely outside the project's content roots (where there is no in-project artifact to watch), use the new **Reload Idris Project** action to pick up the change without restarting the IDE.

### Changed

- **Quick documentation** (hover) now shows the full doc comment together with the type signature, totality and visibility, resolved to the exact symbol under the caret instead of listing every same-named definition from other modules. The popup separates the type, documentation, totality and visibility into distinct sections.
- The structure view omits `import` lines to keep the outline focused.
- Improved code completion.

## [0.1.1]

- Initial Marketplace release.
