# sosh


[![CircleCI Build Status][ci-build-badge]][ci-build]
[![License][license-badge]][license]

_**so**urce **.sh**_ to the rescue!

Leverage `source` to fetch remote shell scripts and optionally bundle them into one file.

Status: **ALPHA**

## Briefly

sosh a shell tool that uses `source` declaration in shell scripts to fetch remote scripts and/or bundle them into one file.

Example: let's assume you need some color definitions and functions to serialize arrays in a Bash script. You can take them adding following `source` declaration:
```bash
source "${ROOT_DIR}/.github_deps/rynkowsg/shell-gr@v0.7.0/lib/color.bash"
source "${ROOT_DIR}/.https_deps/gist.githubusercontent.com/TekWizely/c0259f25e18f2368c4a577495cd566cd/raw/b9e87c74565fb90a39bb7a1033f950773201dbf7/serialize_array.bash"
```
If you call `sosh fetch` on the script, sosh will get these files for you.

If you like to bundle the script into one file, you can use `sosh pack` and sosh will save the bundled file to expected location.

That's that simple.

## Motivation

So, it all started when I was trying to share code between CircleCI orbs and their commands.
The thing is, orbs only let you use one bash script per command.
That's a bummer because if your commands share similarities or worse, they are kind of the same just with different defaults, you end up copying and pasting the same stuff over and over.

After a couple of days, it hit me that it'd be super handy to share not just bits
and pieces between orb commands, but also with all the other bash scripts I had lying around.
I'm talking about the stuff we use all the time, like logging, handling errors, and other goodies.

## Install

```sh
curl -s https://raw.githubusercontent.com/rynkowsg/sosh/v0.2.0/main/src/pl/rynkowski/sosh.cljc -o ~/.bin/sosh
chmod +x ~/.bin/sosh
```
The line above installs the script in `~/.bin`. That installation directory needs to be added to `PATH`.

> [!WARNING]
> The tool requires [Babashka](https://github.com/babashka/babashka) to work. If you like to see this a standalone binary rise your voice [HERE](https://github.com/rynkowsg/sosh/issues/1).

## Usage

**FETCH**

```sh
sosh fetch ./test/res/test_suite/4_import_remote/entry.bash
```

**PACK**

```sh
# pack the script on input to the path on output
sosh pack -i ./test/res/test_suite/3_import_with_variables/entry.bash -o ./bundled.bash

# optionally you can set current working directory (useful if the entry script doesn't use absolute path for sourced files)
sosh pack -i ./entry.bash -o ./bundled.bash
```

## What's there

**Features**
- recursive dependency resolution (your script requires A, A requires B, B requires C)
- command to bundle script and sourced deps, either local or remote, into one file
- option to ignore `source` line if contains `# sosh: skip` at the end
- support for BATS files[^bats-disclaimer]

Some aspects are better explained in [CHANGELOG](https://github.com/rynkowsg/sosh/blob/main/CHANGELOG.md#010-2024-03-15) notes to version 0.1.0.

[^bats-disclaimer]: But script paths need to consider they can be called not only by bats, but also by Bash ([example](https://github.com/rynkowsg/sosh/blob/63d85c5/test/res/test_suite/7_bats_import/entry.bats#L6)).

**Examples**

I use this tools in my own repos:
- [rynkowsg/asdf-orb] - CircleCI orb providing asdf
- [rynkowsg/checkout-orb] - CircleCI orb providing advanced checkout
- [rynkowsg/shell-gr] - my library of bash snippets ([lib examples](https://github.com/rynkowsg/shell-gr/tree/main/lib))

Examples:
- source remote scripts from github:
  - [install_asdf.bash](https://github.com/rynkowsg/asdf-orb/blob/main/src/scripts/install_asdf.bash) - script behind [rynkowsg/asdf-orb]
  - [clone_git_repo.bash](https://github.com/rynkowsg/checkout-orb/blob/main/src/scripts/clone_git_repo.bash) - script behind [rynkowsg/checkout-orb]
- source remote scripts by URL:
  - [bats_assert.bash](https://github.com/rynkowsg/shell-gr/blob/main/lib/bats_assert.bash) - wrapper for bats_assert to fetch all necessary files at once
- path initialization in a remote script that requires yet another scripts[^re-path-initialization]:
  - [error.bash](https://github.com/rynkowsg/shell-gr/blob/main/lib/error.bash#L5)
  - most of the files in [shell-gr repository](https://github.com/rynkowsg/shell-gr/tree/main/lib)

[^re-path-initialization]: Path initialization can be complicated, especially when writing bits sourcing other bits.

[rynkowsg/asdf-orb]: https://github.com/rynkowsg/asdf-orb
[rynkowsg/checkout-orb]: https://github.com/rynkowsg/checkout-orb
[rynkowsg/shell-gr]: https://github.com/rynkowsg/shell-gr

## Development

Every tool this repo needs is pinned in [`mise.toml`](mise.toml), down to an exact version, and
`mise.lock` records the download URL and checksum for each one. Install [mise](https://mise.jdx.dev/)
yourself; it brings the rest - Babashka, Java, bats, shellcheck and shfmt.

```sh
mise trust    # mise ignores a config it doesn't trust
mise install
```

The tools reach `PATH` through the `mise activate` hook in your shell rc.

With [direnv](https://direnv.net/), `direnv allow` does the install for you on entering the directory,
and repeats it whenever `mise.toml` changes. Copy `.envrc.local.example` to `.envrc.local` for values
that belong to your machine rather than the repo, such as `MISE_GITHUB_TOKEN`.

Then the tasks:

| Command             | What it does                                            |
|---------------------|---------------------------------------------------------|
| `bb lint`           | shellcheck over the bash and bats files                 |
| `bb format`         | shfmt over the same files, `bb format check` to verify  |
| `bb test`           | the bats suite, plus the test runs under bb and the JVM |
| `bb deps`           | report outdated Clojure dependencies                    |
| `mise run deps`     | report outdated tools                                   |
| `mise deps:upgrade` | bump the tool versions, interactively                   |

`bb lint` and `bb format` start by running sosh on their own scripts, so the shell-gr files they
source get fetched first.

## License

Copyright © 2024 Greg Rynkowski

Released under the [MIT license][license].

[ci-build-badge]: https://circleci.com/gh/rynkowsg/sosh.svg?style=shield "CircleCI Build Status"
[ci-build]: https://circleci.com/gh/rynkowsg/sosh
[license-badge]: https://img.shields.io/badge/license-MIT-lightgrey.svg
[license]: https://raw.githubusercontent.com/rynkowsg/sosh/main/LICENSE
