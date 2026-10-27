-- Headless end-to-end test of the Neovim integration (run from a built checkout or an installed distribution):
--   nvim --headless -l tests/nvim/smoke.lua
--   CONFORM_PATH=/path/to/conform.nvim nvim --headless -l ...   additionally tests the conform.nvim adapter
-- Expected results come from the ijfmt CLI, so this checks the editor plumbing, not the formatter itself.
local here = vim.fn.fnamemodify(debug.getinfo(1, "S").source:sub(2), ":p:h")
vim.opt.runtimepath:prepend(vim.fn.fnamemodify(here, ":h:h"))
if vim.env.CONFORM_PATH then
  vim.opt.runtimepath:prepend(vim.env.CONFORM_PATH)
end
-- `nvim -l` skips the usual startup, including filetype detection.
vim.cmd("filetype on")

local ijfmt = require("ijfmt")
ijfmt.setup({ format_on_save = { "java" } })
vim.notify = function(msg)
  print("notify: " .. msg)
end

local failures = 0
local function check(name, ok, detail)
  if ok then
    print("ok   " .. name)
  else
    failures = failures + 1
    print("FAIL " .. name .. (detail and ("\n" .. detail) or ""))
  end
end

local dir = vim.fn.tempname() .. "-ijfmt-smoke"
vim.fn.mkdir(dir .. "/src", "p")
vim.fn.writefile({ "root = true", "[*.java]", "indent_size = 2", "ij_java_space_before_method_call_parentheses = true" },
  dir .. "/.editorconfig")
local unformatted = { "class A {", "void m(){int x=1;", "if(x>0){System.out.println(x);}}", "}" }

local function cli_format(path, lines)
  local cmd = ijfmt.command({ "--stdin-filepath", path })
  local result = vim.system(cmd, { stdin = table.concat(lines, "\n") .. "\n", text = true }):wait()
  assert(result.code == 0, "ijfmt CLI failed: " .. (result.stderr or ""))
  local out = vim.split((result.stdout:gsub("\r\n", "\n")), "\n", { plain = true })
  if out[#out] == "" then
    table.remove(out)
  end
  return out
end

-- 1. format on save (BufWritePre) of a file on disk
local file = dir .. "/src/A.java"
vim.fn.writefile(unformatted, file)
vim.cmd("edit " .. vim.fn.fnameescape(file))
vim.cmd("write")
local expected = cli_format(file, unformatted)
local on_disk = vim.fn.readfile(file)
check("format on save", vim.deep_equal(on_disk, expected), table.concat(on_disk, "\n"))
check("editorconfig ij_* applied",
  (on_disk[2] or ""):match("^  void m%(%) {$") ~= nil and table.concat(on_disk, "\n"):find("println %(x%)") ~= nil,
  table.concat(on_disk, "\n"))

-- 2. unsaved edits are formatted from the buffer, not from disk
vim.api.nvim_buf_set_lines(0, 1, 2, false, { "void m(){int   y=2;" })
local buffer_before = vim.api.nvim_buf_get_lines(0, 0, -1, false)
vim.cmd("IjfmtFormat")
check("formats buffer content", vim.deep_equal(vim.api.nvim_buf_get_lines(0, 0, -1, false), cli_format(file, buffer_before)))
check("disk untouched by :IjfmtFormat", vim.deep_equal(vim.fn.readfile(file), expected))
vim.cmd("edit!")

-- 3. range formatting only touches the selected lines
vim.api.nvim_buf_set_lines(0, 0, -1, false, { "class A {", "int a=1;", "int b=2;", "int c=3;", "}" })
vim.cmd("3,3IjfmtFormat")
local ranged = vim.api.nvim_buf_get_lines(0, 0, -1, false)
check("range formatting", vim.deep_equal(ranged, { "class A {", "int a=1;", "  int b = 2;", "int c=3;", "}" }),
  table.concat(ranged, "\n"))
vim.cmd("edit!")

-- 4. a new buffer that was never written uses its future path for .editorconfig
local new_file = dir .. "/src/nested/New.java"
vim.cmd("edit " .. vim.fn.fnameescape(new_file))
vim.api.nvim_buf_set_lines(0, 0, -1, false, { "class New {", "void m(){}}" })
vim.cmd("IjfmtFormat")
local new_lines = vim.api.nvim_buf_get_lines(0, 0, -1, false)
check("unsaved new file", vim.deep_equal(new_lines, { "class New {", "  void m() {", "  }", "}" }), table.concat(new_lines, "\n"))
vim.cmd("bwipeout!")

-- 5. conform.nvim adapter
local has_conform, conform = pcall(require, "conform")
if has_conform then
  conform.setup({ formatters = { ijfmt = require("ijfmt.conform") }, formatters_by_ft = { java = { "ijfmt" } } })
  vim.cmd("edit " .. vim.fn.fnameescape(file))
  vim.api.nvim_buf_set_lines(0, 0, -1, false, unformatted)
  local ok = conform.format({ bufnr = 0, async = false, timeout_ms = 20000 })
  local formatted = vim.api.nvim_buf_get_lines(0, 0, -1, false)
  check("conform adapter", ok and vim.deep_equal(formatted, expected), table.concat(formatted, "\n"))
else
  print("skip conform adapter (set CONFORM_PATH)")
end

vim.fn.delete(dir, "rf")
print(failures == 0 and "ALL PASSED" or (failures .. " FAILED"))
vim.cmd(failures == 0 and "qall!" or "cquit! 1")
