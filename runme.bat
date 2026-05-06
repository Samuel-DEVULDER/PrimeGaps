@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: run.bat - Lance PrimeGap avec les meilleures options JVM
:: Detecte : GraalVM, Oracle JDK 23+, HotSpot C2, OpenJ9
:: ============================================================

where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: java introuvable dans le PATH.
    pause
    exit /b 1
)

:: --- Capturer les proprietes JVM dans un fichier temp ------
set TMPFILE=%TEMP%\jvmdetect_%RANDOM%.txt
java -XshowSettings:property -version 2>"%TMPFILE%"

:: --- Lire le vendor ----------------------------------------
set JAVA_VENDOR=unknown
for /f "tokens=1,* delims==" %%a in ('findstr /i "java.vendor " "%TMPFILE%"') do (
    set RAWVENDOR=%%b
    for /f "tokens=* delims= " %%c in ("!RAWVENDOR!") do set JAVA_VENDOR=%%c
)

:: --- Lire la version majeure (java.specification.version) --
set JAVA_MAJOR=0
for /f "tokens=1,* delims==" %%a in ('findstr "java.specification.version" "%TMPFILE%"') do (
    for /f "tokens=1 delims=. " %%v in ("%%b") do set JAVA_MAJOR=%%v
)

del "%TMPFILE%" 2>nul

echo.
echo === Detection JVM ===
echo Vendor  : %JAVA_VENDOR%
echo Version : %JAVA_MAJOR%
echo.

:: --- Options de base (toujours requises) -------------------
set BASE=--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED

:: --- GC + memoire ------------------------------------------
set GC=-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -Xms256m

:: --- Detection GraalVM standalone --------------------------
set IS_GRAAL=0
echo %JAVA_VENDOR% | findstr /i "graal" >nul
if not errorlevel 1 set IS_GRAAL=1

:: --- Detection OpenJ9 / IBM Semeru -------------------------
set IS_OPENJ9=0
echo %JAVA_VENDOR% | findstr /i "openj9 semeru ibm" >nul
if not errorlevel 1 set IS_OPENJ9=1

:: --- Detection Oracle JDK >= 23 ----------------------------
set IS_ORACLE23=0
echo %JAVA_VENDOR% | findstr /i "oracle" >nul
if not errorlevel 1 (
    if %JAVA_MAJOR% GEQ 23 set IS_ORACLE23=1
)

:: --- Selection des options JIT -----------------------------

if "%IS_GRAAL%"=="1" (
    echo [Mode] GraalVM standalone - Graal Enterprise JIT
    set JIT=-Djdk.graal.CompilerConfiguration=enterprise -Djdk.graal.VectorAPISupport=true -Djdk.graal.VectorizeSIMD=true -Djdk.graal.OptimizeExceptionPaths=true -Djdk.graal.LoopUnswitch=true -XX:+EnableVectorSupport
    goto :launch
)

if "%IS_ORACLE23%"=="1" (
    echo [Mode] Oracle JDK %JAVA_MAJOR% - Graal JIT embarque
    set JIT=-XX:+UseGraalJIT -Djdk.graal.VectorAPISupport=true -Djdk.graal.VectorizeSIMD=true -XX:+EnableVectorSupport
    goto :launch
)

if "%IS_OPENJ9%"=="1" (
    echo [Mode] OpenJ9 / IBM Semeru
    set JIT=-Xjit:count=0 -Xaggressive -XX:+EnableVectorSupport
    goto :launch
)

echo [Mode] HotSpot C2 standard
set JIT=-XX:+EnableVectorSupport -XX:+OptimizeFill -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:ReservedCodeCacheSize=256m -XX:+TieredCompilation -XX:CompileThreshold=1000

:launch
echo =====================================================
echo.
set CMD=java -XX:+UnlockExperimentalVMOptions %BASE% %JIT% %GC% -cp "%~dp0bin" primegap.sieve.simd.SIMDSieveGap$FastForward$DoubleBuffer
rem primegap.sieve.parallel.Parallel2SieveGap$FastForward$DoubleBuffer
rem primegap.sieve.simd.SIMDSieveGap$FastForward$DoubleBuffer
rem primegap.sieve.parallel.Parallel2SieveGap$FastForward
rem $DoubleBuffer
echo %CMD%
echo.
%CMD%

pause