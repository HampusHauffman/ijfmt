-- ijfmt.nvim: format buffers with IntelliJ IDEA's own formatter through the ijfmt daemon.
--
-- The daemon is started (once, shared by all editors) through the ijfmt CLI; formatting requests then go straight to
-- the daemon over a loopback socket, so a format costs a few milliseconds instead of a JVM start.
local uv = vim.uv or vim.loop

local M = {}

M.config = {
  -- ijfmt launcher. nil = <install>/bin/ijfmt next to this plugin, else `ijfmt` on PATH.
  cmd = nil,
  -- Start the daemon in the background as soon as one of these filetypes is opened (hides IntelliJ's startup time).
  prestart_filetypes = { "java", "kotlin", "groovy" },
  -- Filetypes formatted on :w by this plugin. Leave empty when using conform.nvim's format_on_save instead.
  format_on_save = {},
  -- Max time for one format request, and for starting the daemon.
  timeout_ms = 10000,
  start_timeout_ms = 120000,
}

local endpoint = nil -- { port = number, token = string, stateFile = string }
local starting = false
local start_callbacks = {}

local is_windows = vim.fn.has("win32") == 1

local function notify(msg, level)
  vim.schedule(function()
    vim.notify("ijfmt: " .. msg, level or vim.log.levels.INFO)
  end)
end

--- The ijfmt distribution: either this plugin lives inside one (<install>/lua/ijfmt/init.lua), or it is a git checkout
--- that was built with `require("ijfmt.build").run()` (<repo>/build/install/ijfmt).
function M.install_dir()
  local source = debug.getinfo(1, "S").source:sub(2)
  local root = vim.fn.fnamemodify(source, ":p:h:h:h")
  for _, dir in ipairs({ root, root .. "/build/install/ijfmt" }) do
    if vim.fn.filereadable(dir .. "/lib/ijfmt.jar") == 1 then
      return dir
    end
  end
  return nil
end

local install_dir = M.install_dir

--- Command prefix for the CLI. Runs java directly when possible: avoids cmd.exe/sh wrappers and their quoting.
local function cli_command()
  if M.config.cmd then
    return { M.config.cmd }
  end
  local dir = install_dir()
  if dir then
    local java = nil
    local home_file = dir .. "/lib/java.home"
    if vim.fn.filereadable(home_file) == 1 then
      local home = vim.trim(vim.fn.readfile(home_file)[1] or "")
      local candidate = home .. (is_windows and "/bin/java.exe" or "/bin/java")
      if vim.fn.executable(candidate) == 1 then
        java = candidate
      end
    end
    java = java or vim.env.IJFMT_JAVA or (vim.env.JAVA_HOME and (vim.env.JAVA_HOME .. "/bin/java")) or "java"
    return {
      java,
      "-XX:+IgnoreUnrecognizedVMOptions",
      "-XX:TieredStopAtLevel=1",
      "-XX:+UseSerialGC",
      "-XX:SharedArchiveFile=" .. dir .. "/lib/ijfmt.jsa",
      "-XX:+AutoCreateSharedArchive",
      "-Xlog:cds=off",
      "-Xlog:cds+dynamic=off",
      "-jar",
      dir .. "/lib/ijfmt.jar",
    }
  end
  return { is_windows and "ijfmt.cmd" or "ijfmt" }
end

--- Full command line for the ijfmt CLI with the given arguments.
function M.command(args)
  local cmd = cli_command()
  vim.list_extend(cmd, args)
  return cmd
end

local cli = M.command

--- Starts (or connects to) the daemon asynchronously; callback(err, endpoint).
function M.start(callback)
  callback = callback or function() end
  if endpoint then
    return callback(nil, endpoint)
  end
  table.insert(start_callbacks, callback)
  if starting then
    return
  end
  starting = true
  local ok, err = pcall(vim.system, cli({ "daemon", "start", "--json" }), { text = true, timeout = M.config.start_timeout_ms },
    function(result)
      starting = false
      local ep, failure = nil, nil
      if result.code == 0 then
        local decoded_ok, decoded = pcall(vim.json.decode, vim.trim(result.stdout or ""))
        if decoded_ok and type(decoded) == "table" and decoded.port then
          ep = decoded
        else
          failure = "unexpected output from `ijfmt daemon start --json`: " .. (result.stdout or "")
        end
      else
        failure = vim.trim((result.stderr or "") .. (result.stdout or ""))
        if failure == "" then
          failure = "`ijfmt daemon start` failed with exit code " .. tostring(result.code)
        end
      end
      endpoint = ep
      local callbacks = start_callbacks
      start_callbacks = {}
      for _, cb in ipairs(callbacks) do
        cb(failure, ep)
      end
    end)
  if not ok then
    starting = false
    start_callbacks = {}
    local hint = install_dir() and "set require('ijfmt').setup({ cmd = ... })"
      or "the plugin is not built yet: run :IjfmtBuild (or add the build hook to your plugin spec)"
    callback("cannot run ijfmt (" .. tostring(err) .. "); " .. hint, nil)
  end
end

local function url_encode(value)
  return (value:gsub("[^%w%-%._~]", function(c)
    return string.format("%%%02X", string.byte(c))
  end))
end

--- One HTTP/1.1 exchange with the daemon; callback(err, status, body).
local function http(ep, method, target, body, callback)
  local tcp = uv.new_tcp()
  local chunks = {}
  local finished = false
  local function finish(err, status, response_body)
    if finished then
      return
    end
    finished = true
    if not tcp:is_closing() then
      tcp:close()
    end
    callback(err, status, response_body)
  end
  tcp:connect("127.0.0.1", ep.port, function(connect_err)
    if connect_err then
      return finish(connect_err)
    end
    local request = table.concat({
      method .. " " .. target .. " HTTP/1.1",
      "Host: 127.0.0.1:" .. ep.port,
      "Connection: close",
      "X-Ijfmt-Token: " .. ep.token,
      "Content-Type: text/plain; charset=utf-8",
      "Content-Length: " .. #body,
      "",
      "",
    }, "\r\n") .. body
    tcp:write(request)
    tcp:read_start(function(read_err, data)
      if read_err then
        return finish(read_err)
      end
      if data then
        table.insert(chunks, data)
        return
      end
      local raw = table.concat(chunks)
      local head_end = raw:find("\r\n\r\n", 1, true)
      local status = tonumber(raw:match("^HTTP/%d%.%d (%d%d%d)"))
      if not head_end or not status then
        return finish("malformed response from the ijfmt daemon")
      end
      finish(nil, status, raw:sub(head_end + 4))
    end)
  end)
end

--- Formats text as the content of `path`; callback(err, formatted_text). Starts/reconnects the daemon when needed.
function M.format_text(path, text, lines, callback)
  local target = "/v1/format?path=" .. url_encode(path) .. (lines and ("&lines=" .. url_encode(lines)) or "")
  local function attempt(retry)
    M.start(function(start_err, ep)
      if start_err then
        return callback(start_err)
      end
      http(ep, "POST", target, text, function(err, status, body)
        if err then
          -- Daemon gone (idle timeout, restart, upgrade): forget it and try once more with a fresh one.
          endpoint = nil
          if retry then
            return attempt(false)
          end
          return callback("request failed: " .. tostring(err))
        end
        if status == 200 then
          return callback(nil, body)
        elseif status == 415 then
          return callback("unsupported file type: " .. body)
        elseif status == 401 and retry then
          endpoint = nil
          return attempt(false)
        end
        callback("HTTP " .. status .. ": " .. body)
      end)
    end)
  end
  attempt(true)
end

local function buffer_text(bufnr)
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local text = table.concat(lines, "\n")
  if vim.bo[bufnr].eol or vim.bo[bufnr].fixeol then
    text = text .. "\n"
  end
  return text
end

local function split_lines(text)
  local lines = vim.split(text, "\n", { plain = true })
  if lines[#lines] == "" then
    table.remove(lines)
  end
  return lines
end

--- Replaces only the changed hunks, so marks, folds, extmarks and the cursor survive formatting.
function M.apply(bufnr, new_lines)
  local old_lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local old_text = table.concat(old_lines, "\n") .. "\n"
  local new_text = table.concat(new_lines, "\n") .. "\n"
  if old_text == new_text then
    return false
  end
  local diff = vim.text and vim.text.diff or vim.diff
  local hunks = diff(old_text, new_text, { result_type = "indices", algorithm = "histogram" })
  for i = #hunks, 1, -1 do
    local start_a, count_a, start_b, count_b = unpack(hunks[i])
    local replacement = vim.list_slice(new_lines, start_b, start_b + count_b - 1)
    local first = count_a == 0 and start_a or start_a - 1
    vim.api.nvim_buf_set_lines(bufnr, first, first + count_a, false, replacement)
  end
  return true
end

local function buffer_path(bufnr)
  local name = vim.api.nvim_buf_get_name(bufnr)
  if name == "" then
    return nil
  end
  return vim.fn.fnamemodify(name, ":p")
end

--- Formats a buffer (or a line range) in place. Synchronous by default so it can run from BufWritePre.
--- opts: { bufnr, range = { start_line, end_line } (1-based), async = false }
function M.format(opts)
  opts = opts or {}
  local bufnr = (opts.bufnr == nil or opts.bufnr == 0) and vim.api.nvim_get_current_buf() or opts.bufnr
  local path = buffer_path(bufnr)
  if not path then
    return notify("buffer has no file name; ijfmt needs one to pick the language and .editorconfig", vim.log.levels.WARN)
  end
  local text = buffer_text(bufnr)
  local tick = vim.api.nvim_buf_get_changedtick(bufnr)
  local lines = opts.range and (opts.range[1] .. ":" .. opts.range[2]) or nil
  local done, failure = false, nil

  M.format_text(path, text, lines, function(err, formatted)
    vim.schedule(function()
      done = true
      if err then
        failure = err
        if opts.async then
          notify(err, vim.log.levels.ERROR)
        end
        return
      end
      if not vim.api.nvim_buf_is_valid(bufnr) or vim.api.nvim_buf_get_changedtick(bufnr) ~= tick then
        failure = "buffer changed while formatting; result discarded"
        return
      end
      M.apply(bufnr, split_lines(formatted))
    end)
  end)

  if not opts.async then
    local timeout = endpoint and M.config.timeout_ms or M.config.start_timeout_ms
    if not vim.wait(timeout, function() return done end, 5) then
      notify("timed out after " .. timeout .. "ms", vim.log.levels.ERROR)
    elseif failure then
      notify(failure, vim.log.levels.ERROR)
    end
  end
end

local function run_cli(args)
  vim.system(cli(args), { text = true }, function(result)
    local output = vim.trim((result.stdout or "") .. (result.stderr or ""))
    notify(output ~= "" and output or ("exit code " .. result.code))
  end)
end

function M.setup(opts)
  M.config = vim.tbl_deep_extend("force", M.config, opts or {})
  local group = vim.api.nvim_create_augroup("ijfmt", { clear = true })

  if #M.config.prestart_filetypes > 0 then
    local function prestart()
      M.start(function(err)
        if err then
          notify("could not start the daemon: " .. err, vim.log.levels.WARN)
        end
      end)
    end
    vim.api.nvim_create_autocmd("FileType", { group = group, pattern = M.config.prestart_filetypes, callback = prestart })
    -- When lazy-loaded by the FileType event itself, the autocmd above was created too late for this buffer.
    if vim.tbl_contains(M.config.prestart_filetypes, vim.bo.filetype) then
      prestart()
    end
  end

  if #M.config.format_on_save > 0 then
    vim.api.nvim_create_autocmd("BufWritePre", {
      group = group,
      callback = function(args)
        if vim.tbl_contains(M.config.format_on_save, vim.bo[args.buf].filetype) then
          M.format({ bufnr = args.buf })
        end
      end,
    })
  end

  vim.api.nvim_create_user_command("IjfmtFormat", function(cmd)
    local range = cmd.range > 0 and { cmd.line1, cmd.line2 } or nil
    M.format({ range = range })
  end, { range = true, desc = "Format the buffer (or range) with IntelliJ's formatter" })
  vim.api.nvim_create_user_command("IjfmtStatus", function() run_cli({ "daemon", "status" }) end, { desc = "ijfmt daemon status" })
  vim.api.nvim_create_user_command("IjfmtRestart", function()
    endpoint = nil
    run_cli({ "daemon", "restart" })
  end, { desc = "Restart the ijfmt daemon" })
  vim.api.nvim_create_user_command("IjfmtLog", function() run_cli({ "daemon", "log" }) end, { desc = "Show the ijfmt daemon log" })
  vim.api.nvim_create_user_command("IjfmtBuild", function()
    endpoint = nil
    require("ijfmt.build").run()
  end, { desc = "(Re)build ijfmt inside the plugin checkout" })
end

return M
