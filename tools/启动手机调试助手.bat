@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 高德导航 手机调试助手
echo 正在启动，稍后把屏幕上的地址输入手机浏览器...
echo.
python dev-loop.py
echo.
echo 服务已停止。
pause
