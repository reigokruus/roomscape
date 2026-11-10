#!/bin/zsh
# Realign speed on saved scans: ./run.sh <scan folder>...
D=${0:A:h}; T=$(mktemp -d); J=$(ls -d ~/DEV/tools/jdk-21*/Contents/Home)/bin
S=$D/../../android/app/src/main/java/com/hackathon/setscan
mkdir -p $T/src/com/hackathon/setscan
cp $S/Realign.java $D/RealignBench.java $T/src/com/hackathon/setscan/
$J/javac -d $T/out $T/src/com/hackathon/setscan/*.java && for s in "$@"; do $J/java -Xmx3g -cp $T/out com.hackathon.setscan.RealignBench $s; done
