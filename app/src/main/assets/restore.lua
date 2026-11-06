-- Deco X50 owner critical-data backup: @NONCE@

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
local parts={"/tmp/deco-x50-ssh-@NONCE@-part-00.b64","/tmp/deco-x50-ssh-@NONCE@-part-01.b64","/tmp/deco-x50-ssh-@NONCE@-part-02.b64","/tmp/deco-x50-ssh-@NONCE@-part-03.b64","/tmp/deco-x50-ssh-@NONCE@-part-04.b64","/tmp/deco-x50-ssh-@NONCE@-part-05.b64","/tmp/deco-x50-ssh-@NONCE@-part-06.b64","/tmp/deco-x50-ssh-@NONCE@-part-07.b64","/tmp/deco-x50-ssh-@NONCE@-part-08.b64"}
local encoded={}
for i,path in ipairs(parts) do
  local f=assert(io.open(path,"rb"))
  encoded[i]=assert(f:read("*a")); assert(f:close())
end
local original=decode64(table.concat(encoded))
assert(#original==19955 and original:sub(1,5)=="\027LuaQ","restore identity")
local original_fn=assert(loadstring(original))
local f=assert(io.open("/tmp/luci-indexcache","wb"))
assert(f:write(original)); assert(f:close())
local verify=assert(io.open("/tmp/luci-indexcache","rb"))
local restored=verify:read("*a"); assert(verify:close())
assert(restored==original,"restore readback")
return original_fn()
