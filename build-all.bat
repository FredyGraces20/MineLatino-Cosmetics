@echo off
REM Build MineLatino Cosmetics for every Minecraft version in versions.properties.
REM Usage: build-all.bat [mcVersion ...]
REM   With no arguments, builds every version listed in versions.properties.

setlocal enabledelayedexpansion
cd /d "%~dp0"

set "PROPS=versions.properties"
set "DIST=dist"

if not "%~1"=="" (
    set "VERSIONS=%*"
) else (
    set "VERSIONS="
    for /f "usebackq tokens=1,2,3 delims=." %%a in ("%PROPS%") do (
        if "%%d"=="fabric" (
            set "VERSIONS=!VERSIONS! %%a.%%b.%%c"
        )
    )
)

if "!VERSIONS!"=="" (
    echo No Minecraft versions found in %PROPS%
    exit /b 1
)

if exist "%DIST%" rd /s /q "%DIST%"
md "%DIST%"

echo === Building MineLatino Cosmetics ===

for %%v in (!VERSIONS!) do (
    echo.
    echo --- Fabric %%v ---
    call gradlew.bat "-PmcVersion=%%v" :fabric:build
    if errorlevel 1 exit /b 1

    findstr /b "%%v.forge.version=" "%PROPS%" >nul 2>&1
    if not errorlevel 1 (
        echo --- Forge %%v ---
        call gradlew.bat "-PmcVersion=%%v" :forge:build
        if errorlevel 1 exit /b 1
    ) else (
        echo --- Forge %%v: no entry, skipping ---
    )

    for %%f in (fabric\build\libs\*%%v*.jar forge\build\libs\*%%v*.jar) do (
        if exist "%%f" copy "%%f" "%DIST%\" >nul
    )
)

echo.
echo === Build complete. JARs in %DIST%: ===
dir /b "%DIST%\*.jar"
