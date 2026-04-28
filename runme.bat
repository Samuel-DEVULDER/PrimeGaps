cd /d %~dp0src
java -version
java --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -cp ../bin primegap.sieve.parallel.Parallel2SieveGap$FastForward$DoubleBuffer
pause