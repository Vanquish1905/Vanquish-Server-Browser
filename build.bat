@echo off
rem Builds the mod from inside this folder. No Fabric template files needed.
rem Usage: build.bat            (builds the jar)
rem        build.bat runClient  (starts a test Minecraft)
setlocal
cd /d "%~dp0"

set GRADLE_VERSION=9.6.0
set TASKS=%*
if "%TASKS%"=="" set TASKS=build

rem --- 1. Java: a full JDK 25 is required (a JRE alone has no compiler) ---
where javac >nul 2>nul
if errorlevel 1 (
  echo ERROR: no Java compiler ^(javac^) found. Install the full JDK 25, for example Temurin from https://adoptium.net
  echo and make sure it is on your PATH, then open a NEW terminal window.
  exit /b 1
)

rem --- 2. Gradle: use the wrapper if present, otherwise download Gradle into this folder ---
if exist gradle\wrapper\gradle-wrapper.jar if exist gradlew.bat (
  call gradlew.bat %TASKS%
  goto :done
)

set DIST=.gradle-dist\gradle-%GRADLE_VERSION%
if not exist "%DIST%\bin\gradle.bat" (
  echo Downloading Gradle %GRADLE_VERSION% ^(one time, about 130 MB^)...
  if not exist .gradle-dist mkdir .gradle-dist
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '.gradle-dist\gradle.zip'; Expand-Archive -Force '.gradle-dist\gradle.zip' '.gradle-dist'; Remove-Item '.gradle-dist\gradle.zip'"
  if errorlevel 1 (
    echo ERROR: could not download Gradle.
    exit /b 1
  )
)
call "%DIST%\bin\gradle.bat" %TASKS%

:done
if exist build\libs (
  echo.
  echo Done. Your jar is in build\libs\ ^(use the one WITHOUT "-sources"^):
  dir /b build\libs
)
endlocal
