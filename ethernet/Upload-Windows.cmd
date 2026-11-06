@echo off
setlocal
cd /d "%~dp0"
where ssh.exe >nul 2>&1
if errorlevel 1 goto missing
where scp.exe >nul 2>&1
if errorlevel 1 goto missing
if not exist "rtl8367b-familyc.ko" goto missing
if not exist "InternetDeco_X50_Ethernet_RAM_v1.sh" goto missing
certutil -hashfile "rtl8367b-familyc.ko" SHA256 | findstr /i /c:"d02f5bf162eb2557b7f1c1b1e8c6f3909962874cc9ebc6abb64a35d73fcdc1b1" >nul
if errorlevel 1 goto badhash
echo Connect this laptop to X50-OpenWrt Wi-Fi first.
echo This uploads two files to RAM. It does not execute the driver change.
echo Verify the SSH key for 192.168.1.1 if asked. The original RAM root password is empty.
set "SCPOPT=-O"
scp.exe -O 2>&1 | findstr /i /c:"unknown option" /c:"illegal option" /c:"invalid option" >nul
if not errorlevel 1 set "SCPOPT="
scp.exe %SCPOPT% "rtl8367b-familyc.ko" "InternetDeco_X50_Ethernet_RAM_v1.sh" root@192.168.1.1:/tmp/
if errorlevel 1 goto failed
echo.
echo Upload complete. In your existing OpenWrt SSH terminal, run:
echo sh /tmp/InternetDeco_X50_Ethernet_RAM_v1.sh --apply
pause
exit /b 0
:missing
echo Missing files or Windows OpenSSH client. Keep both files beside this uploader.
pause
exit /b 1
:badhash
echo Driver checksum does not match. No upload or driver change was performed.
pause
exit /b 1
:failed
echo Upload failed. No driver change was requested. Check Wi-Fi and SSH access.
pause
exit /b 1
