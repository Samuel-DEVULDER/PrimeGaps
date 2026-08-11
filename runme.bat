@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: run.bat - Lance PrimeGap avec les meilleures options JVM
:: Detecte : GraalVM, Oracle JDK 23+, HotSpot C2, OpenJ9
:: Selectionne la meilleure classe selon nb de CPUs
:: ============================================================

where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: java introuvable dans le PATH.
    pause
    exit /b 1
)

set TMPFILE=%TEMP%\jvmdetect_%RANDOM%.txt
java -XshowSettings:property -version 2>"%TMPFILE%"

set JAVA_VENDOR=unknown
for /f "tokens=1,* delims==" %%a in ('findstr /i "java.vendor " "%TMPFILE%"') do (
    set RAWVENDOR=%%b
    for /f "tokens=* delims= " %%c in ("!RAWVENDOR!") do set JAVA_VENDOR=%%c
)

set JAVA_MAJOR=0
for /f "tokens=1,* delims==" %%a in ('findstr "java.specification.version" "%TMPFILE%"') do (
    for /f "tokens=1 delims=. " %%v in ("%%b") do set JAVA_MAJOR=%%v
)

del "%TMPFILE%" 2>nul

set CPU_COUNT=1
for /f "tokens=2 delims==" %%c in ('wmic cpu get NumberOfLogicalProcessors /value 2^>nul ^| findstr "="') do set CPU_COUNT=%%c
for /f "tokens=* delims= " %%c in ("!CPU_COUNT!") do set CPU_COUNT=%%c

echo.
echo === Detection JVM ===
echo Vendor  : %JAVA_VENDOR%
echo Version : %JAVA_MAJOR%
echo CPUs    : %CPU_COUNT%
echo.

set BASE=--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
set GC=-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -Xms256m

set IS_GRAAL=0
echo %JAVA_VENDOR% | findstr /i "graal" >nul
if not errorlevel 1 set IS_GRAAL=1

set IS_OPENJ9=0
echo %JAVA_VENDOR% | findstr /i "openj9 semeru ibm" >nul
if not errorlevel 1 set IS_OPENJ9=1

set IS_ORACLE23=0
echo %JAVA_VENDOR% | findstr /i "oracle" >nul
if not errorlevel 1 (
    if %JAVA_MAJOR% GEQ 23 set IS_ORACLE23=1
)

if "%IS_GRAAL%"=="1" (
    echo [Mode] GraalVM standalone
    set JIT=-Djdk.graal.CompilerConfiguration=enterprise -Djdk.graal.VectorAPISupport=true -Djdk.graal.VectorizeSIMD=true -Djdk.graal.OptimizeExceptionPaths=true -Djdk.graal.LoopUnswitch=true -XX:+EnableVectorSupport
    goto :select_class
)
if "%IS_ORACLE23%"=="1" (
    echo [Mode] Oracle JDK %JAVA_MAJOR% - Graal JIT embarque
    set JIT=-XX:+UseGraalJIT -Djdk.graal.VectorAPISupport=true -Djdk.graal.VectorizeSIMD=true -XX:+EnableVectorSupport
    goto :select_class
)
if "%IS_OPENJ9%"=="1" (
    echo [Mode] OpenJ9 / IBM Semeru
    set JIT=-Xjit:count=0 -Xaggressive -XX:+EnableVectorSupport
    goto :select_class
)

echo [Mode] HotSpot C2 standard
set JIT=-XX:+EnableVectorSupport -XX:+OptimizeFill -XX:+DoEscapeAnalysis -XX:+EliminateLocks -XX:ReservedCodeCacheSize=256m -XX:+TieredCompilation -XX:CompileThreshold=1000

:select_class
set CLASS=primegap.sieve.SieveGap$FF
if %CPU_COUNT% GEQ 4  set CLASS=primegap.sieve.SieveGap$FF$DB
if %CPU_COUNT% GEQ 8  set CLASS=primegap.sieve.parallel.ParallelPrimesSieveGap$FF$DB

echo [Classe] %CLASS%

:launch
echo =====================================================
echo.
set CMD=java -XX:+UnlockExperimentalVMOptions %BASE% %JIT% %GC% -cp "%~dp0bin" %CLASS%
echo %CMD%
echo.
%CMD%

pause