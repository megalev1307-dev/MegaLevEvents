@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul || (echo Java JDK 25 not found. Install JDK 25 and reopen terminal. & exit /b 1)
if not exist paper-api.jar (echo Put paper-api.jar next to this script. & exit /b 1)
if not exist build\classes mkdir build\classes
javac -encoding UTF-8 -cp paper-api.jar -d build\classes src\main\java\fun\megalev\events\MegaLevEvents.java
if errorlevel 1 exit /b 1
xcopy /Y /Q src\main\resources\* build\classes\ >nul
jar --create --file MegaLevEvents.jar -C build\classes .
if errorlevel 1 exit /b 1
echo BUILT: MegaLevEvents.jar
