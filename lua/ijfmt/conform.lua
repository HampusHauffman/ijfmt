-- conform.nvim formatter backed by the ijfmt daemon (direct socket, no process spawn per format).
--
--   require("conform").setup({
--     formatters = { ijfmt = require("ijfmt.conform") },
--     formatters_by_ft = { java = { "ijfmt" } },
--   })
local ijfmt = require("ijfmt")

return {
  meta = {
    description = "IntelliJ IDEA's own formatter (honours .editorconfig ij_* properties and .idea/codeStyles).",
  },
  format = function(_, ctx, lines, callback)
    local text = table.concat(lines, "\n") .. "\n"
    local range = nil
    if ctx.range then
      range = ctx.range.start[1] .. ":" .. ctx.range["end"][1]
    end
    ijfmt.format_text(vim.fn.fnamemodify(ctx.filename, ":p"), text, range, function(err, formatted)
      vim.schedule(function()
        if err then
          return callback(err)
        end
        local new_lines = vim.split(formatted, "\n", { plain = true })
        if new_lines[#new_lines] == "" then
          table.remove(new_lines)
        end
        callback(nil, new_lines)
      end)
    end)
  end,
}
