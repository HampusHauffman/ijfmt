-- Builds the ijfmt distribution inside the plugin checkout (<repo>/build/install/ijfmt). Used as a plugin manager
-- build hook, e.g. lazy.nvim: `build = function() require("ijfmt.build").run() end`, or manually: `:IjfmtBuild`.
--
-- Gradle needs a JDK: $JAVA_HOME if set, otherwise the JetBrains Runtime bundled with the newest IntelliJ IDEA found.
local M = {}

local is_windows = vim.fn.has("win32") == 1

local function repo_root()
  local source = debug.getinfo(1, "S").source:sub(2)
  return vim.fn.fnamemodify(source, ":p:h:h:h")
end

local function java_executable(home)
  return home .. (is_windows and "/bin/java.exe" or "/bin/java")
end

--- JetBrains Runtime of an installed IntelliJ IDEA (newest by path name), or nil.
function M.find_jbr()
  local home = vim.fn.expand("~")
  local patterns = {}
  if is_windows then
    local local_app_data = vim.env.LOCALAPPDATA or (home .. "/AppData/Local")
    local program_files = vim.env.ProgramFiles or "C:/Program Files"
    vim.list_extend(patterns, {
      local_app_data .. "/Programs/IntelliJ IDEA*/jbr",
      program_files .. "/JetBrains/IntelliJ IDEA*/jbr",
      local_app_data .. "/JetBrains/Toolbox/apps/*/*/jbr",
      local_app_data .. "/JetBrains/Toolbox/apps/*/*/*/jbr",
    })
  elseif vim.fn.has("mac") == 1 then
    vim.list_extend(patterns, {
      "/Applications/IntelliJ IDEA*.app/Contents/jbr/Contents/Home",
      home .. "/Applications/IntelliJ IDEA*.app/Contents/jbr/Contents/Home",
      home .. "/Library/Application Support/JetBrains/Toolbox/apps/*/*/*.app/Contents/jbr/Contents/Home",
    })
  else
    vim.list_extend(patterns, {
      "/opt/idea*/jbr",
      "/opt/intellij*/jbr",
      home .. "/.local/share/JetBrains/Toolbox/apps/*/jbr",
      home .. "/.local/share/JetBrains/Toolbox/apps/*/*/jbr",
      home .. "/.local/share/JetBrains/Toolbox/apps/*/*/*/jbr",
      "/snap/intellij-idea-*/current/jbr",
    })
  end
  local found = {}
  for _, pattern in ipairs(patterns) do
    for _, dir in ipairs(vim.fn.glob(pattern, false, true)) do
      if vim.fn.executable(java_executable(dir)) == 1 then
        table.insert(found, dir)
      end
    end
  end
  table.sort(found)
  return found[#found]
end

--- Runs `gradlew installDist` synchronously; raises an error (shown by the plugin manager) on failure.
function M.run()
  local root = repo_root()
  local env = {}
  local java_home = vim.env.JAVA_HOME
  if not java_home or vim.fn.executable(java_executable(java_home)) ~= 1 then
    java_home = M.find_jbr()
    if not java_home then
      error("ijfmt: no JDK found. Install IntelliJ IDEA or set JAVA_HOME, then rebuild the plugin.")
    end
    env.JAVA_HOME = java_home
  end
  -- `sh gradlew` doesn't depend on the executable bit surviving a commit from Windows.
  local cmd = is_windows and { "cmd.exe", "/c", root .. "\\gradlew.bat" } or { "sh", root .. "/gradlew" }
  vim.list_extend(cmd, { "installDist", "--no-daemon", "--quiet", "--console=plain" })
  print("ijfmt: building (first build downloads Gradle, takes a minute)...")
  local result = vim.system(cmd, { cwd = root, env = env, text = true }):wait()
  if result.code ~= 0 then
    error("ijfmt: build failed (exit " .. result.code .. "):\n" .. (result.stderr or "") .. (result.stdout or ""))
  end
  print("ijfmt: built " .. root .. "/build/install/ijfmt")
end

return M
