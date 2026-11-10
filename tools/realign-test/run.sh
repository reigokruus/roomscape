#!/bin/zsh
# Tests the S24's re-alignment after a tracking loss (Realign.java) on a synthetic room with known shifts, on the Mac.
D=${0:A:h}; T=$(mktemp -d); J=$(ls -d ~/DEV/tools/jdk-21*/Contents/Home)/bin
mkdir -p $T/src/com/hackathon/setscan
cp $D/../../android/app/src/main/java/com/hackathon/setscan/Realign.java $D/RealignTest.java $T/src/com/hackathon/setscan/
$J/javac -d $T/out $T/src/com/hackathon/setscan/*.java && $J/java -cp $T/out com.hackathon.setscan.RealignTest
