#!/bin/bash

#echo arg1 is the astypes to generate, 501-wiser, 504 sbgp, 505 bw 507 replacement
#echo arg2 is the sim to perform in the simulator; 4 replacement, 5 truecost
#echo arg3 is where to monitor in the simulator, participating 0, all 1, gulf 2
#echo arg4 is whether to use bandwidth numbers in the simulator
#echo arg5 is what metric to use RIBMETRIC 0, FIBMETRIC 1
#echo arg6 is max number of propagated paths
#echo arg7 is the legend name
echo "arg8 is the random method, 0 for pool, 1 for weigthed, 2 for uniform"
astypesFile=$*astypes.txt
echo $astypesFile
echo "LEGEND "$7" ENDLEGEND\n"

python ../richWorldASTypes.py --seedWiser 1 ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" --numTransits 0 --seedTransit 1 --sim $1 --randomMethod $8
java -Xmx10000M -Dfile.encoding=Cp1252 -cp .:../../src:../../../deps/argparse4j-0.6.0.jar  simulator.Simulator ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" ../../results/delete.txt --seed 1 --sim $2 --monitorFrom $3 --useBandwidth $4 --forX 0 --metric $5 --maxPaths $6
	java -Xmx1500M -Dfile.encoding=Cp1252 -classpath "C:\cygwin64\home\David\repos\commonlanguage\simulator\src;C:\Users\spart\.m2\repository\net\sourceforge\argparse4j\argparse4j\0.6.0\argparse4j-0.6.0.jar;C:\Users\spart\.m2\repository" simulator.Simulator ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" ../../results/delete.txt --seed 1 --sim $2 --monitorFrom $3 --useBandwidth $4 --forX 0 --metric $5 --maxPaths $6

for j in $(seq .5 .5 9.5);
do
    test=$(echo "scale = 2; $j / 10" | bc)
    printf "\nTesting for stub/transit percentage: %f\n"  $test
    for i in $(seq 1 10);
    do
	printf "\nTesting on Transit seed %s\n" $i
	python ../richWorldASTypes.py --seedWiser 1 ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" --numTransits $test --seedTransit $i --sim $1 --randomMethod $8
	java -Xmx10000M -Dfile.encoding=Cp1252 -cp .:../../src:../../../deps/argparse4j-0.6.0.jar  simulator.Simulator ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" ../../results/delete.txt --seed 1 --sim $2 --monitorFrom $3 --useBandwidth $4 --forX $test --metric $5 --maxPaths $6
	java -Xmx1500M -Dfile.encoding=Cp1252 -classpath "C:\cygwin64\home\David\repos\commonlanguage\simulator\src;C:\Users\spart\.m2\repository\net\sourceforge\argparse4j\argparse4j\0.6.0\argparse4j-0.6.0.jar;C:\Users\spart\.m2\repository" simulator.Simulator ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" ../../results/delete.txt --seed 1 --sim $2 --monitorFrom $3 --useBandwidth $4 --forX $test --metric $5 --maxPaths $6
    done
done

python ../richWorldASTypes.py --seedWiser 1 ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" --numTransits 1 --seedTransit 1 --sim $1 --randomMethod $8
java -Xmx10000M -Dfile.encoding=Cp1252 -cp .:../../src:../../../deps/argparse4j-0.6.0.jar  simulator.Simulator ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" ../../results/delete.txt --seed 1 --sim $2 --monitorFrom $3 --useBandwidth $4 --forX 1.000 --metric $5 --maxPaths $6
	java -Xmx1500M -Dfile.encoding=Cp1252 -classpath "C:\cygwin64\home\David\repos\commonlanguage\simulator\src;C:\Users\spart\.m2\repository\net\sourceforge\argparse4j\argparse4j\0.6.0\argparse4j-0.6.0.jar;C:\Users\spart\.m2\repository" simulator.Simulator ../../formattedData/annotatedBrite.txt ../../formattedData/"$astypesFile" ../../results/delete.txt --seed 1 --sim $2 --monitorFrom $3 --useBandwidth $4 --forX 1.000 --metric $5 --maxPaths $6
