# ijfmt — IntelliJ IDEA's formatter, everywhere

`ijfmt` formats code **with IntelliJ IDEA's own formatter** — not a re-implementation, not an approximation — so you can
write Java (and Kotlin, XML, JSON, YAML, …) in Neovim or any other editor and get byte-for-byte the output your
IntelliJ-using teammates get. Your `.editorconfig` with all its `ij_*` properties and your `.idea/codeStyles` are
honoured exactly like in the IDE.

It is fast enough for format-on-save: a headless IntelliJ runs as a background daemon, so a format request takes
a few tens of milliseconds (measured: ~40 ms median from Neovim for a 114-line file that needs heavy reformatting)
instead of the ~18 s of `idea format`.

```
 Neovim (lua, direct socket) ─┐
 conform.nvim adapter ────────┤        HTTP on 127.0.0.1 + token        ┌─────────────────────────────────────┐
 ijfmt CLI (stdin / files) ───┴───────────────────────────────────────▶│ headless IntelliJ IDEA (your install)│
                                                                        │  + ijfmt-daemon plugin               │
                                                                        │  CodeStyleManager.reformatText(...)  │
                                                                        │  + EditorConfig plugin, save pipeline│
                                                                        └─────────────────────────────────────┘
```

## Why this is identical to IntelliJ

The daemon is your installed IntelliJ IDEA started headless (the same way `idea format` starts it: same JVM options,
class path and bundled plugins), with a tiny plugin that keeps it alive and serves requests. Each request runs the
exact pipeline of IntelliJ's command line formatter (`com.intellij.formatting.commandLine.FileSetFormatter`):

1. code style = per-project scheme from the nearest `.idea/codeStyles` (if it uses per-project settings), otherwise
   IntelliJ's defaults (or your exported scheme, see `codestyle.fallback`);
2. `.editorconfig` files (including every `ij_*` property) layered on top by IntelliJ's EditorConfig plugin;
3. `CodeStyleManager.reformatText` on the file's document (all formatter post-processors: javadoc, braces, …);
4. IntelliJ's save pipeline: when the text differs from disk, the "before save" listeners run — trailing whitespace
   stripping (respecting `trim_trailing_whitespace` and "modified lines only"), final newline (`insert_final_newline`),
   — and the file's line separator is used.

Editor buffers are applied to the real file's document as a line diff before formatting, so IntelliJ sees exactly the
edits you made (this matters for "strip trailing spaces on modified lines"). The file on disk is never touched in
buffer mode; a vetoer blocks every save that wasn't explicitly requested.

**And it is verified**: a differential conformance suite compares ijfmt with the stock `idea format` of the same IDE
build on thousands of inputs — see [Testing](#testing-conformance-with-intellij).

## Install

Requirements: IntelliJ IDEA installed locally (developed and verified with 2026.2; the daemon plugin is compiled
against your installed IDE, and requires at least that build). Nothing else: the build uses the JetBrains Runtime
bundled with your IDE.

```sh
# JAVA_HOME can be the IDE's bundled runtime, e.g. "<IDE>/jbr" (Windows/Linux) or "<IDE>.app/Contents/jbr/Contents/Home"
./gradlew install                         # -> ~/.ijfmt   (or -PinstallDir=/somewhere)
export PATH="$HOME/.ijfmt/bin:$PATH"      # Windows: add %USERPROFILE%\.ijfmt\bin to PATH
ijfmt ides                                # shows which IntelliJ installations were found
ijfmt daemon start                        # optional: start it now (first start ~3-15 s)
```

The IDE is auto-detected (newest IntelliJ IDEA in the standard locations, including JetBrains Toolbox). Pick one with
`--ide`, `IJFMT_IDE_HOME` or `ide.home` in the config file. The build compiles against the detected IDE too
(`-PideHome=...` to override).

**Use the same IntelliJ version as your team.** The formatter evolves between IDE releases; ijfmt is identical to the
build it runs on (`ijfmt daemon status` shows it).

## Neovim

With [lazy.nvim](https://github.com/folke/lazy.nvim), pointing at the installed distribution:

```lua
{
  dir = vim.fn.expand("~/.ijfmt/nvim"),
  name = "ijfmt",
  config = function()
    require("ijfmt").setup({
      -- Start the daemon when one of these is opened, so the first format is instant (default shown).
      prestart_filetypes = { "java", "kotlin", "groovy" },
      -- Let ijfmt format these on :w itself (leave empty if you use conform.nvim, below).
      format_on_save = { "java" },
    })
  end,
}
```

Commands: `:IjfmtFormat` (also with a range: `:'<,'>IjfmtFormat`), `:IjfmtStatus`, `:IjfmtRestart`, `:IjfmtLog`.

### With conform.nvim

The adapter talks to the daemon directly (no process spawn per format):

```lua
require("conform").setup({
  formatters = { ijfmt = require("ijfmt.conform") },
  formatters_by_ft = { java = { "ijfmt" }, kotlin = { "ijfmt" } },
  format_on_save = { timeout_ms = 2000 },
})
```

Keep `require("ijfmt").setup()` (with an empty `format_on_save`) for the daemon prestart and the commands. Prefer the
CLI instead? It works as a plain stdin formatter too:
`formatters = { ijfmt = { command = "ijfmt", args = { "--stdin-filepath", "$FILENAME" }, stdin = true } }`.

Headless self-test of the integration: `nvim --headless -l ~/.ijfmt/nvim/tests/smoke.lua`
(`CONFORM_PATH=/path/to/conform.nvim` to include the conform adapter).

## Command line

```sh
ijfmt --stdin-filepath src/main/java/Foo.java < Foo.java      # stdin -> stdout (the path picks language/style; it need not exist)
ijfmt --stdin-filepath Foo.java --lines 10:20 < Foo.java      # only reformat lines 10-20
ijfmt src/                                                    # format files/directories in place (skips hidden dirs)
ijfmt --mask '*.java,*.kt' .                                  # only matching file names
ijfmt --check --mask '*.java' .                               # CI / pre-commit: list unformatted files, exit 1 if any
ijfmt daemon start|stop|restart|status|log
```

In-place formatting goes through IntelliJ's own save (charset, BOM and line separators handled by IntelliJ), exactly
like `idea format`. Exit codes: `0` ok, `1` `--check` found unformatted files, `2` usage/config error,
`3` unsupported file type, `4` daemon failure.

Other editors: anything that can run a stdin formatter works with `ijfmt --stdin-filepath <file>` (Helix
`formatter = { command = "ijfmt", args = ["--stdin-filepath", "%{buffer_name}"] }`, Emacs apheleia, VS Code
"Custom Local Formatters", …). The HTTP API (below) is simple enough for a native integration.

## Configuration

`config.properties` in `%APPDATA%\ijfmt` (Windows), `~/Library/Application Support/ijfmt` (macOS) or
`~/.config/ijfmt` (Linux):

| key | default | |
|---|---|---|
| `ide.home` | newest installed IntelliJ IDEA | IDE to format with |
| `codestyle.fallback` | IntelliJ defaults | scheme XML used when a project has no per-project code style — export yours from *Settings › Editor › Code Style › ⚙ › Export › IntelliJ IDEA code style XML* |
| `daemon.idleTimeoutMinutes` | `240` | daemon exits after this much inactivity (`0` = never) |
| `daemon.maxHeap` | IDE's `-Xmx` | e.g. `1g` |
| `daemon.startTimeoutSeconds` | `120` | |

Environment: `IJFMT_IDE_HOME`, `IJFMT_CONFIG` (config file), `IJFMT_STATE_DIR` (daemon state, default
`%LOCALAPPDATA%\ijfmt` / `~/Library/Caches/ijfmt` / `~/.cache/ijfmt`), `IJFMT_JAVA` (JVM for the CLI).

The daemon uses private IDE config/system directories, so it runs side by side with your IDE and never touches your
IDE settings. One daemon per IDE build is shared by all editors and terminals. It runs from a copy of the plugin, so
re-installing ijfmt while it runs is fine — clients restart it automatically when the installation changed.

### Things that come from the IDE's *global* settings

Like `idea format`, ijfmt uses IntelliJ's defaults for IDE-wide (non-project) settings. If your team relies on any of
these, put them in `.editorconfig` (that's what makes them shared anyway):

* *Strip trailing spaces on save* (default: modified lines) → `trim_trailing_whitespace = true|false`
* *Ensure every saved file ends with a line break* → `insert_final_newline = true|false`
* The IDE-level code style scheme (projects without per-project settings) → `codestyle.fallback`

*Reformat Code* only — no *Optimize Imports* / *Rearrange Code* (those need a fully indexed project with a class path).
Files are parsed at the IDE's default Java language level rather than your module's (also like `idea format`); this
only matters for unusual code such as contextual keywords (`record`, `yield`, …) used as identifiers.

## Testing: conformance with IntelliJ

```sh
./gradlew fetchCorpus    # sparse checkout of IntelliJ's own formatter test data + real Java sources (pinned commit)
./gradlew conformance    # ~14k cases (~30 min); prints a summary and writes a report of any difference
```

For every *style variant* × *input mutation* × *corpus file*, the suite builds identical directory trees, runs
IntelliJ's stock command line formatter (`bin/format.sh|bat -r -allowDefaults`) on one, and ijfmt on the other, and
requires byte-identical results for all three ijfmt modes: **buffer** (editor/stdin), **check** and **in place**.

* corpus: built-in fixtures (modern Java, broken code, module-info, Kotlin, Gradle KTS, XML, JSON, YAML) + JetBrains'
  formatter test inputs (`java/java-tests/testData/psi/formatter`) + real-world sources (`java/java-psi-api/src`);
* variants: IntelliJ defaults; a team `.editorconfig` with 144 `ij_*` properties; a per-project `.idea/codeStyles`
  scheme with an `.editorconfig` layered on top; tabs + CRLF + no final newline;
* mutations: original, indentation stripped, random whitespace (re-indented, joined/split lines, padded punctuation),
  CRLF + trailing whitespace. Practically every input is changed by the formatter, so this exercises real formatting.

Plus `DaemonBehaviorTest`: things a one-shot run can't observe — `.editorconfig` / `.idea/codeStyles` edits picked up
between requests, buffer mode never writing to disk, unsaved new files, line ranges, line separators.

**Run it against your own code base** (uses your repository's `.editorconfig`/`.idea/codeStyles`):

```sh
./gradlew conformance -Pconformance.corpus=/path/to/your/repo -Pconformance.extensions=java,kt
```

Options: `-Pconformance.variants=<names or dirs>`, `-Pconformance.mutations=original,flattened,jittered,crlf-trailing`,
`-Pconformance.maxFiles=<per corpus root>`, `-Pconformance.seed=<n>`, `-Pconformance.work=<dir>`.
Failures show the first differing lines plus paths of the input, the oracle's and ijfmt's output.

Unit tests of the client: `./gradlew :cli:test`.

## HTTP API

The daemon listens on `127.0.0.1:<port>`; port and token are in `<state dir>/<IDE build>/daemon.json`
(`ijfmt daemon start --json` prints them). Every request needs the header `X-Ijfmt-Token`.

| request | |
|---|---|
| `POST /v1/format?path=<abs path>[&lines=A:B,C:D]` body: UTF-8 text | formatted text (input's line separators) |
| `POST /v1/format-file?path=<abs path>` | format in place → `{"changed":bool}` |
| `POST /v1/check-file?path=<abs path>` | dry run → `{"changed":bool}` |
| `GET /v1/status`, `POST /v1/shutdown` | |

`415` unsupported file type, `422` rejected by the formatter, `400` bad request.

## Layout

```
daemon/       IntelliJ plugin: headless app starter (`ijfmt-server`), formatting pipeline, HTTP API, save vetoer
cli/          dependency-free Java 17 client: IDE discovery, daemon lifecycle, CLI
nvim/         Neovim plugin (direct socket client, conform.nvim adapter, headless smoke test)
conformance/  differential test suite against IntelliJ's command line formatter
dist/bin/     launcher scripts (ijfmt, ijfmt.cmd)
```
