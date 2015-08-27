import argparse

parser = argparse.ArgumentParser(description='Program to gather information as to why iplane data does not appear in the CAIDA dataset.  Requires iplaneNcaida file generated by annotateIplaneData.py and converted CAIDA file produced by convert_caida_dataset')
parser.add_argument('iplaneNcaida', metavar='iplaneNcaida', nargs=1, help = 'file containing iplane links not in caida')
parser.add_argument('caidaDataset', metavar='caidadataset', nargs=1, help = 'caida relationships converted by convert_caida_dataset')


args = parser.parse_args()
iplaneNcaidaFile = open(args.iplaneNcaida[0], 'r')
caidaDatasetFile = open(args.caidaDataset[0], 'r')

CUSTOMER_PROVIDER = -1
PEER_PEER = 0
PROVIDER_CUSTOMER = 1

caidaAdjacency = {}


def buildCaidaGraph():
    for line in caidaDatasetFile:
        split = line.split()
        AS1 = split[0]
        AS2 = split[1]
        relationship = split[2]

        if AS1 not in caidaAdjacency:
            caidaAdjacency[AS1] = {AS2 : relationship}
        if AS2 not in caidaAdjacency:
            if relationship == CUSTOMER_PROVIDER:
                caidaAdjacency[AS2] = {AS1 : PROVIDER_CUSTOMER} 
            else:
                caidaAdjacency[AS2] = {AS1 : relationship}

        if AS1 in caidaAdjacency and AS2 not in caidaAdjacency[AS1]:
            caidaAdjacency[AS1][AS2] = relationship
        
        if AS2 in caidaAdjacency and AS1 not in caidaAdjacency[AS2]:
            if relationship == CUSTOMER_PROVIDER:
                caidaAdjacency[AS2][AS1] = PROVIDER_CUSTOMER
            else:
                caidaAdjacency[AS2][AS1] = relationship

def shortestPath(AS1, AS2):
    shortestPath = []

    searchQueue = [AS1]
    seenEntries = {AS1 : ''}
    found = False
    while len(searchQueue) > 0:
        searchEntry = searchQueue.pop(0)
        try:
            adjacentNodes = caidaAdjacency[searchEntry]
        except KeyError:
            return []
        if searchEntry == AS2:
            found = True
            break
        for key in adjacentNodes:
            if key not in seenEntries:                
                seenEntries[key] = searchEntry
                searchQueue.append(key)
    if found:
        shortestPath.append(AS2)
        parent = seenEntries[AS2]
        while parent != '':
            shortestPath.append(parent)
            parent = seenEntries[parent]
        
    return shortestPath.reverse()
    
buildCaidaGraph()

print "test shortest path: ", shortestPath(35872, 40197)
for line in iplaneNcaidaFile:
    split = line.split()
    AS1 = split[0]
    AS2 = split[1]

    print shortestPath(AS1, AS2)
