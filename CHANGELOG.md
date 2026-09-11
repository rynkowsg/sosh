# Changelog

## [Unreleased](https://github.com/rynkowsg/sosh/compare/v0.2.1...HEAD)

## [0.2.1](https://github.com/rynkowsg/sosh/compare/v0.2.0...v0.2.1) (2026-09-11)

### Fixed

- **cli**: Fix crash on `sosh` without arguments, so the help prints again

### Changed

- Stop declaring the dependencies Babashka already ships, so the first run downloads less

## [0.2.0](https://github.com/rynkowsg/sosh/compare/v0.1.2...v0.2.0) (2024-04-02)

- Rename 'shellpack' to 'sosh'
- Add skipping source on "shellpack skip"
- Fix paths resolution in BATS files

## [0.1.2](https://github.com/rynkowsg/sosh/compare/v0.1.1...v0.1.2) (2024-03-16)

- **pack**: Fix crash when skip encountered

## [0.1.1](https://github.com/rynkowsg/sosh/compare/v0.1.0...v0.1.1) (2024-03-15)

- **pack**: Fix adding same dependency more than once

## [0.1.0](https://github.com/rynkowsg/sosh/commits/v0.1.0) (2024-03-15)

Initial version supports following:

#### Fetching dependencies via https

The script evaluates wheater the sourced path is remote and
if so it can fetch it when subcommand `fetch` is run.
To determine the source path is remote or not, it looks for certain tokens like:
`.github_deps`, `.github`, `.https`, `.http`, `@github`, `@github_deps`.
Examples:
- `source ".github_deps/rynkowsg/shell-gr@0.1.0/lib/color.bash"`
- `source ".github/rynkowsg/shell-gr@0.1.0/lib/color.bash"`
- `source ".https/raw.githubusercontent.com/rynkowsg/sosh/0.1.0/lib/color.bash"`
- `source ".https_deps/raw.githubusercontent.com/rynkowsg/sosh/0.1.0/lib/color.bash"`
- `source "@github/rynkowsg/shell-gr@0.1.0/lib/color.bash"`
- `source "@https/raw.githubusercontent.com/rynkowsg/sosh/0.1.0/lib/color.bash"`

#### Bundling the multi-file script into one file

The script visits all files following `source` declarations.

> [!IMPORTANT]
> It doesn't support `source` declarations from within a function.

#### Resolution of paths that contains bash variables

Example:
```bash
SHELL_GR_DIR="${ROOT_DIR}/.github_deps/rynkowsg/shell-gr@0.1.0"}"
source "${SHELL_GR_DIR}/lib/color.bash"
```

#### Transitive dependencies resolution

Scenario when we source remote file and that remote file sources the file in the same directory.
This is done via reliance on resolved absolute path.

Example:

- root script
  ```bash
  SHELL_GR_DIR="${ROOT_DIR}/.github_deps/rynkowsg/shell-gr@0.1.0"}"
  source "${SHELL_GR_DIR}/lib/log.bash"
  ```

- remote log.bash
  ```bash
  _SHELL_GR_DIR="${SHELL_GR_DIR:-"${_GR_LOG_ROOT_DIR}"}"
  source "${_SHELL_GR_DIR}/lib/color.bash"
  ```

In the above example, if the SHELL_GR_DIR is defined before, it is taken,
otherwise it is set to a root dir computed in the library file earlier.
