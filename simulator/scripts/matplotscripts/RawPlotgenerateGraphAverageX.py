import numpy
import pylab
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
import argparse
import random
import math
import re

DEBUG = 1

parser = argparse.ArgumentParser(description='Creates a pdf graph from experimentoutput, no ybreak')
parser.add_argument('experimentOutput', metavar='expOutput', nargs ="+", help = 'experimental output, relevent info should have GRAPH and LEGEND ENDLEGEND')
#parser.add_argument('pdfName', metavar='nameOfPdf', nargs=1, help = 'The name of the pdf')
parser.add_argument('--xlabel', metavar='x axis label', help = 'label for the x axis')
parser.add_argument('--ylabel', metavar='y axis label', help = 'label for the y axis')
parser.add_argument('--title', metavar = 'graph title', help = 'title for the graph')
parser.add_argument('--scale', metavar = 'scale y axis', help = 'value to scale down by', default=1)
parser.add_argument('--metric', metavar = 'use rib or fib sum', help = 'use the rib metric or not', default='FIB')
parser.add_argument('--legendLoc', metavar = 'legend location', help = 'location to put legend (see pylab doc)', default = 2)

#open files based on arguments
args = parser.parse_args()
inputDataList = args.experimentOutput
pdfName = args.title + '.pdf'
xlabel = args.xlabel
ylabel = args.ylabel
title = args.title
scalingFactor = args.scale
metric = args.metric
legendLoc = args.legendLoc
rawData = [] #list of tuples (xydictionary, legendname)
X = []
Y = []
ymax = 0

def parseInput():
    for entry in inputDataList:
        expOutput = open(entry, 'r')
        metricRE = re.compile(metric + '.*END' + metric)
        p = re.compile('GRAPH.*ENDGRAPH')
        legendRE = re.compile('LEGEND.*ENDLEGEND')
        legendName = ' '
        xyDict = {}
        for line in expOutput:
            if metricRE.search(line):
                m = p.search(line)
                line = m.group()
                splitLine = line.split()
                x = splitLine[1]
                y = splitLine[2]
                if DEBUG:
                    print x,y
                if x not in xyDict:
                    xyDict[x] = []
                xyDict[x].append(y)
                
            if legendRE.search(line):
                m = legendRE.search(line)
                line = m.group()
                splitLine = line.split(' ', 1)
                if DEBUG:
                    print 'splitline: ', splitLine
                splitLine = splitLine[1].rsplit(' ', 1)[0]
                if DEBUG:
                    print 'splitline: ', splitLine
                legendName = splitLine
        rawData.append((xyDict, legendName))
        if DEBUG:
            print rawData

#computes the average from a list of floats
def getAverage(list):
    sum = 0
    for item in list:
        sum += float(item)
    average = sum/len(list)
    if DEBUG:
        print "sum: ", sum
        print "listlen: ", len(list)
        print "Average: ",average
    return average

#return list of y values and the best y value (the last one)
#input dictionary of xyvalues
def getY(xyDict):
    y = []
    for key in sorted(xyDict):
        xAverage = getAverage(xyDict[key])
        y.append(xAverage/float(scalingFactor))
    if DEBUG:
        print "y: ", y
    return y, y[-1]

#get x values associated with this dictionary
def getX(xyDict):
    x = []
    for key in sorted(xyDict):
        x.append(key)
    if DEBUG:
        print 'x: ', x
    return x

#scale y list down to be a percentage
#return the list
def scaleY(y, scaler):
    zeroith = y[0] #bgp status quo, what we scale by
    dataRange = zeroith -  scaler
    newY = []
    for element in y:
        distance = zeroith - element
        percentInto = distance/dataRange
        newY.append(percentInto)
    return newY


#find the minimum y in the raw data
#returns it
def minY(rawData):
    minY = 99999999999
    maxY = 0
    for tuple in rawData:
        xyDict = tuple[0]
        y, betterY = getY(xyDict)
        tmpMin = min(y)
        tmpMax = max(y)
        if tmpMin < minY:
            minY = tmpMin
        if tmpMax > maxY:
            maxY = tmpMax
    return minY, maxY

parseInput()
#fillXY()
        
pp = PdfPages(pdfName)
arr = numpy.asarray

#y = arr(Y)
#y = arr([[1457270.5],
#         [1479158.9],
#         [1476073.413],
#         [1444782.44],
#         [1372953.26],
#         [1308710.05],
#         [1187560.917],
#         [1141159.451],
#         [1018126.513],
#         [863181.1922],
#         [698950.2]]).flatten()
#
#c= arr([[ 49.81, 34.67], #.1
#        [65.74, 35.85], #.2
#        [32.56, 42.57], #.3
#        [40.29, 38.37], #.4
#        [35.07, 26.19], #.5
#        [32.9, 15.24], #.6
#        [13.71, 15.22],   #.7
#        [20.77, 48.8],  #.8
#        [5.78, 12.43],  #.9
#        [0, 0]]).T     #1


#plt.figure()
#plt.errorbar(x, y, yerr=0)
legendHandles = []
bestY = None
for tuple in rawData:
    xyDict = tuple[0]
    legendName = tuple[1]
    splitLegend = legendName.split()
    style = '-'
    if splitLegend[0] == 'contiguous':
        style = '--'
    if DEBUG:
        print 'legendname: ', legendName
    y, betterY = getY(xyDict)
    if not math.isnan(betterY):
        bestY = betterY
    #y = scaleY(y, bestY)
    if DEBUG:
        print 'scaley: ', y
    x = getX(xyDict)
    handle, =plt.plot(arr(x),arr(y), linestyle=style, label=legendName)
    legendHandles.append(handle)

plt.legend(fontsize=10, loc=2)
    
#plt.axis([0,1, 0, 1520000])
plt.ylabel(ylabel)
plt.xlabel(xlabel)
plt.xticks(numpy.arange(0, 1.1, .1))
#plt.yticks(numpy.arange(-.3, 1.01, .1))
#plt.grid(which='major', axis = 'both')
#pylab.ylim(ymin=-.3, ymax=1)
pp.savefig()
pp.close()
plt.show()

#pylab.errorbar(x, y, yerr=c)
#pylab.show()
