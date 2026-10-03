@rem Windows launcher reusing the Gradle wrapper committed in Week_3/thu.
@echo off
setlocal
set "PROJECT_DIR=%~dp0"
call "%PROJECT_DIR%..\..\Week_3\thu\gradlew.bat" -p "%PROJECT_DIR%." %*
exit /b %ERRORLEVEL%
