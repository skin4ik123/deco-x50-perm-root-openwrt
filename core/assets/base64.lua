
local function decode64(s)
  assert(#s % 4 == 0, "base64 length")
  local alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
  local map = {}
  for i=1,#alphabet do map[alphabet:byte(i)] = i-1 end
  local out = {}
  for i=1,#s,4 do
    local a,b,c,d = s:byte(i,i+3)
    assert(map[a] and map[b], "base64 prefix")
    assert(c == 61 or map[c], "base64 character")
    assert(d == 61 or map[d], "base64 character")
    assert(c ~= 61 or d == 61, "base64 padding")
    assert((c ~= 61 and d ~= 61) or i+3 == #s, "base64 final padding")
    local n = map[a]*262144 + map[b]*4096 + (map[c] or 0)*64 + (map[d] or 0)
    out[#out+1] = string.char(math.floor(n/65536) % 256)
    if c ~= 61 then out[#out+1] = string.char(math.floor(n/256) % 256) end
    if d ~= 61 then out[#out+1] = string.char(n % 256) end
  end
  return table.concat(out)
end
