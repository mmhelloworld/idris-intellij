# Idris 2 (JVM) Changelog

## [Unreleased]

## [0.1.2]

### Added

- File **structure view** (outline) of modules, `data`/`record`/`interface` declarations and their members, and top-level functions.
- **Code folding** for declarations and their indented bodies.
- **Breadcrumbs** showing the enclosing declaration at the caret.
- **Highlight usages** of the identifier under the caret.

### Changed

- **Quick documentation** (hover) now shows the full doc comment together with the type signature, totality and visibility, resolved to the exact symbol under the caret instead of listing every same-named definition from other modules. The popup separates the type, documentation, totality and visibility into distinct sections.
- The structure view omits `import` lines to keep the outline focused.
- Improved code completion.

## [0.1.1]

- Initial Marketplace release.
