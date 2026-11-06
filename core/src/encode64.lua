
local function encode64(s)
  local a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
  local out = {}
  for i=1,#s,3 do
    local x,y,z = s:byte(i,i+2)
    local n = x*65536 + (y or 0)*256 + (z or 0)
    local p,q,r,t = math.floor(n/262144)%64,math.floor(n/4096)%64,
                    math.floor(n/64)%64,n%64
    out[#out+1] = a:sub(p+1,p+1)..a:sub(q+1,q+1)..
      (y and a:sub(r+1,r+1) or "=")..(z and a:sub(t+1,t+1) or "=")
  end
  return table.concat(out)
end
