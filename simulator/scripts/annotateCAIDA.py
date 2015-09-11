import argparse
from scipy import stats

parser = argparse.ArgumentParser(description="Annotates CAIDA data with latencies whose distribution matches that found in the iplane dataset")
parser.add_argument('convertedCAIDA', metavar='CAIDA', nargs=1, help = "converted CAIDA file")
parser.add_argument('latencyDistribution', metavar='latDist', nargs=1, help = "latency distribution file generated by latencyDistribution.awk")
parser.add_argument('--distSeed', metavar='distSeed', default=1)
parser.add_argument('outFile', metavar = 'outputFile', nargs=1, help = 'file to output annotated data')

args = parser.parse_args()
#open files
convertedCAIDAFile = open(args.convertedCAIDA[0], 'r')
outFile = open(args.outFile[0], 'w')
latencyDistFile = open(args.latencyDistribution[0], 'r')
#get distrbutino seed
distSeed = int(args.distSeed)

#holds the distribution
scipyDist = None

#holds the AS to pop (1 per AS)
asToPoP = {}
nextUnusedPoP = 0 #unused PoP number

#initializes the latency distribution from the file
def initializeDistribution():
    xk = []
    pk = []
    global scipyDist
    for line in latencyDistFile:
        split = line.split(' ')
        xk.append(int(split[0]))
        pk.append(float(split[1]))
    scipyDist = stats.rv_discrete(name='scipyDist', values=(xk, pk), seed=distSeed)

def assignPoP(asNum):
    global nextUnusedPoP
    if asNum not in asToPoP:
        asToPoP[asNum] = nextUnusedPoP
        nextUnusedPoP += 1

#main
initializeDistribution()                  
#print scipyDist.rvs(size=1)


for line in convertedCAIDAFile:
    splitLine = line.strip().split(' ')
    as1 = splitLine[0]
    as2 = splitLine[1]
    rel = splitLine[2]
    latency = scipyDist.rvs(size=1)
    assignPoP(as1)
    assignPoP(as2)
    pop1 = asToPoP[as1]
    pop2 = asToPoP[as2]
    outLine = str(as1) + ' ' + str(as2) + ' ' + rel +  ' ' + str(latency[0]) + ' ' + str(pop1) + ' ' + str(pop2) + '\n'
    outFile.write(outLine)
    
    
