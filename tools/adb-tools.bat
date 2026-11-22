@echo off
chcp 65001 >nul
cd /d "%~dp0.."
title 高德导航 ADB 快捷工具
if not exist screenshots mkdir screenshots

:menu
cls
echo ============ 手机调试快捷工具 ============
echo.
echo   1  安装最新 APK 到手机
echo   2  抓取手机当前截图到 screenshots 目录
echo   3  查看已连接设备
echo   4  开启无线调试（先插 USB，再输入手机 IP）
echo   0  退出
echo.
set /p c=请选择: 
if "%c%"=="1" goto install
if "%c%"=="2" goto shot
if "%c%"=="3" goto devices
if "%c%"=="4" goto wireless
exit /b

:install
echo.
echo 正在安装 android-app\dist\cn.dsh.gaodenav.apk ...
adb install -r "android-app\dist\cn.dsh.gaodenav.apk"
echo.
pause
goto menu

:shot
echo.
adb exec-out screencap -p > "screenshots\adb-shot-%RANDOM%.png"
echo 截图已保存到 screenshots 目录
echo.
pause
goto menu

:devices
echo.
adb devices -l
echo.
pause
goto menu

:wireless
echo.
echo 请先用 USB 连接手机并允许“USB 调试”，然后输入手机的 WiFi IP：
set /p ip=手机 IP: 
adb tcpip 5555
adb connect %ip%:5555
adb devices -l
echo.
echo 成功后可以拔掉 USB；以后直接用 1/2 即可无线安装和截图。
echo.
pause
goto menu
