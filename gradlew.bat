@echo off
set ROOT_DIR=%~dp0
call "%ROOT_DIR%services\gradlew.bat" -p "%ROOT_DIR%services" %*
