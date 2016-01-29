package simulator;
import integratedAdvertisement.IA;
import integratedAdvertisement.Protocol;
import integratedAdvertisement.RootCause;

import java.util.*;
import java.io.*;
import java.lang.reflect.Array;

import simulator.AS.PoPTuple;
//import jdk.nashorn.internal.runtime.regexp.joni.constants.Arguments;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * file: Simulator.java
 * @author John
 *
 */

/**
 * This is the main driver class for the simulation. This class is
 * responsible for maintaining the clock, the event queue, and 
 * instantiating all the ASes (and setting up their peers).
 * 
 * The events are handled by ASes to whom they are directed
 */
public class Simulator {

	public static final boolean bw = true;
	public static int NUM_PATH_CAP = 10;
    private static final int MRAI_TIMER_VALUE = 30000; //30 seconds
    	private static final int TIER1_THRESHOLD = 50;
    //	private static final int TIER1_THRESHOLD = 0;
	private static final int MIN_LOOP_DURATION = 500;
	
	private static PriorityQueue<Event> eventQueue = new PriorityQueue<Event>();
	private static long simTime = 0;
	private static HashMap<Integer, AS> asMap = new HashMap<Integer, AS>();
	/** Contains the mapping between ASNumber and number of single homed children it has */
	private static HashMap<Integer, Integer> numSingleChildren = new HashMap<Integer, Integer>();
	private static long seedVal = 1;
	private static int numUpdateMessages = 0;
	private static int numUpdatesEnqueued = 0;
	private static int numBGPEnqueued = 0;
	private static int numWithdrawMessages = 0;
	private static long totalLoopDuration = 0;
	private static long loopStart = -1;
	private static long loopResolved = -1;
        private static int simMode = -1;

	// this is the current AS to whom we are sending the withdrawal
	// basically, this is the node upstream of the failed link
	private static int currentTarget = -1;
	// this is the multi-homed stub AS who's link is failing
	private static int currentCustomer = -1;

	// this value indicates whether to take timers for other destinations into consideration
	// as of now, it just adds rand(0,1)*MRAI to the timer
	public static boolean otherTimers = true;
	private static HashMap<Integer, FloodMessage> floodMap = new HashMap<Integer, FloodMessage>();

	// in order to ensure fifo for events scheduled at the same time
	// this is needed for snapshot to be correct!
	private static int tieBreaker = 0;
	static Random r = new Random(seedVal);
	static Random specialR = new Random(seedVal);
	static BufferedWriter out ;
	static BufferedWriter outFile; //file to write results to

	public static HashSet<Integer> disconnectedASes = new HashSet<Integer>();
	public static HashSet<Integer> affectedASes = new HashSet<Integer>();
	public static HashSet<Integer> loopAffectedASes = new HashSet<Integer>();
	public static HashSet<Integer> allLoopAffectedASes = new HashSet<Integer>();
	
	// variable which stores whether to check for disconnectivity duration
	public static boolean instrumented = false;

	/** Used to store the set of ASes currently connected to the target (for availability stats) */
	public static HashSet<Integer> currentConnectedASes = new HashSet<Integer>();

	/** Used to compare the set of connected ASes before and after an update */
	public static HashSet<Integer> prevConnectedASes = new HashSet<Integer>();

	// the total disconnect time suffered by an AS
	public static HashMap<Integer, Long> totalDownTime = new HashMap<Integer, Long>();
	// the last simTime at which the AS got disconnected
	public static HashMap<Integer, Long> prevDisconTime = new HashMap<Integer, Long>();

	// this data structure stores the set of upstream ASes for each AS. Whenever an AS changes
	// its best path, it moves everyone upstream of it from the old path to the new path.
	private static HashMap<Integer, HashSet<Integer>> upstreamASes = new HashMap<Integer, HashSet<Integer>>(); 
	
	// stores the set of marker nodes in loops -- these don't satisfy the upstream property
	private static HashSet<Integer> loopMarkerAS = new HashSet<Integer>();
	
	private static long longestLoop;
	
	private static HashSet<Integer> loopyAS = new HashSet<Integer>();
	// this stores the AS -> Loop mapping, so that we can remove the loop and the ASes involved
	// when the loop is in fact resolved
	private static HashMap<Integer, HashSet<Integer>> asLoopMap = new HashMap<Integer, HashSet<Integer>>();

	private static HashMap<HashSet<Integer>, Long> loopTimeMap = new HashMap<HashSet<Integer>, Long>(); 

	private static HashMap<Integer, Integer> tier1DistanceMap = new HashMap<Integer,Integer>();
	
	private static ArrayList<Integer> failureCustomer;
	private static ArrayList<Integer> failureProvider;

	private static HashSet<Integer> tier1ASes = new HashSet<Integer>();
	private static HashSet<Integer> transitASes = new HashSet<Integer>();
	
	// moved from pathChanged for efficiency
	static HashSet<Integer> oldSet = new HashSet<Integer>();
	static HashSet<Integer> newSet = new HashSet<Integer>();
	static HashSet<Integer> seenHops = new HashSet<Integer>();

	static HashSet<RootCause> activeTriggers = new HashSet<RootCause>();
	static HashSet<RootCause> unfinishedThisEpoch = new HashSet<RootCause>();
	static int numFloodsDone;
	static long lastSimTime = 0;
	static long lastTransitSimTime = 0;

	static int numFloods = 0;
	static int numAses = 0;
	static int numTransitASes = 0;
	
	//definitions where the special AS numbers goes.  used in read topo to create the special AS types
	static HashMap<Integer, Integer> asTypeDef = new HashMap<Integer, Integer>();
	
	public static void addDiscon(int asn) {
	    // System.out.println("Adding disconnected " + asn);
		disconnectedASes.add(asn);
		disconnectedASes.addAll(upstreamASes.get(asn));

	}

	public static void addAffected(int asn) {
		if(currentTarget == asn)
			return;
		affectedASes.add(asn);
		affectedASes.addAll(upstreamASes.get(asn)); // all the upstream nodes of this affected AS are also affected!
	}

	// increments the total down time for all ASes in upstreamSet
	private static void incrementDisconDuration(HashSet<Integer> upstreamSet) {
		for(Iterator<Integer> it = upstreamSet.iterator(); it.hasNext();) {
			int tempAS = it.next();
			long duration = simTime - prevDisconTime.get(tempAS);
			incrementDisconDuration(tempAS, duration);
			
		}

	}

	// increments the downtime for the given AS by duration
	private static void incrementDisconDuration(int tempAS, long duration) {
		long durationSoFar = 0;
		if(totalDownTime.containsKey(tempAS)) {
			durationSoFar = totalDownTime.get(tempAS);
		}
		durationSoFar += duration;
		totalDownTime.put(tempAS, durationSoFar);
	}

	private static void recordDisconnectTime(HashSet<Integer> upstreamSet) {
		for(Iterator<Integer> it = upstreamSet.iterator(); it.hasNext();) {
			int tempAS = it.next();
			prevDisconTime.put(tempAS, simTime);
		}
	}

	private static void addLoopAffected(int as) {
		loopAffectedASes.add(as);
		loopAffectedASes.addAll(upstreamASes.get(as));

	}

	// we are adding code to measure duration of disconnectivity here because this is the most
	// efficient place to add it.
	public static void changedPathNoCheck(int as, int dst, IA oldPath, IA newPath) {
		boolean initiallyConnected = false;
		if(instrumented) {
			initiallyConnected = upstreamASes.get(currentCustomer).contains(as);
		}

		HashSet <Integer> upstreamSet = upstreamASes.get(as);
		if(upstreamSet == null) {
			upstreamSet = new HashSet<Integer>();
			upstreamSet.add(as);
			upstreamASes.put(as, upstreamSet);
		}
		//the upstreamSet should always contain self!
		upstreamSet.add(as);
		// Case 1: both paths non-null
		// find the set of ASes in oldPath-newPath - remove upstreamNodes from these
		// find the set of ASes in newPath-oldPath - add upstream nodes to these
		oldSet.clear();
		int oldHop = -1;
		int nextHop = -1;
		if(oldPath != null && oldPath.getPath() != null) {
			//			oldSet.addAll(oldPath.path);
			oldHop = oldPath.getFirstHop();
		}
		if(newPath != null && newPath.getPath() != null) {
			nextHop = newPath.getFirstHop();
		}
		seenHops.clear();
		seenHops.add(as);


		while( oldHop != -1 ) {
			if(oldHop == currentCustomer && upstreamSet.contains((int)197)) {
				System.out.println("AS" + as + " Removed 197 from upstream(target) " + upstreamASes.get(currentCustomer).contains((int)197));
			}
			upstreamASes.get(oldHop).removeAll(upstreamSet);
			oldHop = asMap.get(oldHop).getNextHop(dst);
			if(seenHops.contains(oldHop)) { // loop found .. do stuff?
				break;
			}
			seenHops.add(oldHop);
		}

		seenHops.clear();
		seenHops.add(as);


		while( nextHop != -1 ) {
			if(!upstreamASes.containsKey(nextHop)) {
				upstreamASes.put(nextHop, new HashSet<Integer>());
			}
			if(upstreamSet.contains(nextHop)) {
				System.out.println(" LOOP: adding " + as + " to upstream(" + nextHop + ")");
			}
			if(nextHop == currentCustomer && upstreamSet.contains((int)197)) {
				System.out.println("AS" + as + " Added 197 to upstream(target) " + upstreamASes.get(currentCustomer).contains((int)197));
			}
			upstreamASes.get(nextHop).addAll(upstreamSet);
			nextHop = asMap.get(nextHop).getNextHop(dst);
			if(seenHops.contains(nextHop)) { // loop found .. do stuff?
				break;
			}
			seenHops.add(nextHop);
		}

		if(instrumented) {
			boolean finallyConnected = upstreamASes.get(currentCustomer).contains(as);
			
			if(upstreamSet.contains((int)2363)) {
				System.out.print("AS2363 due to AS" + as + " @ " + simTime + ": " + (oldPath!=null?oldPath.getPath():oldPath) + 
						initiallyConnected + " -> " + (newPath!=null?newPath.getPath():newPath) + finallyConnected);
				seenHops.clear();
				int testAS = (int)2363;
				seenHops.add(testAS);

				nextHop = asMap.get(testAS).getNextHop(dst);	
				while( nextHop != -1 ) {
					System.out.print(" " + nextHop);
					nextHop = asMap.get(nextHop).getNextHop(dst);
					if(seenHops.contains(nextHop)) { // loop found .. do stuff?
						break;
					}
					seenHops.add(nextHop);
				}
				System.out.println();
			}
			if(upstreamSet.contains((int)197)) {
				System.out.print("AS197 due to AS" + as + " @ " + simTime + ": " + (oldPath!=null?oldPath.getPath():oldPath) + 
						initiallyConnected + " -> " + (newPath!=null?newPath.getPath():newPath) + finallyConnected);
				seenHops.clear();
				int testAS = (int)197;
				seenHops.add(testAS);

				nextHop = asMap.get(testAS).getNextHop(dst);	
				while( nextHop != -1 ) {
					System.out.print(" " + nextHop);
					nextHop = asMap.get(nextHop).getNextHop(dst);
					if(seenHops.contains(nextHop)) { // loop found .. do stuff?
						break;
					}
					seenHops.add(nextHop);
				}
				System.out.println();
			}
			if(upstreamSet.contains((int)4)) {
				System.out.print("AS4 due to AS" + as + " @ " + simTime + ": " + (oldPath!=null?oldPath.getPath():oldPath) + 
						initiallyConnected + " -> " + (newPath!=null?newPath.getPath():newPath) + finallyConnected);
				seenHops.clear();
				int testAS = (int)4;
				seenHops.add(testAS);

				nextHop = asMap.get(testAS).getNextHop(dst);	
				while( nextHop != -1 ) {
					System.out.print(" " + nextHop);
					nextHop = asMap.get(nextHop).getNextHop(dst);
					if(seenHops.contains(nextHop)) { // loop found .. do stuff?
						break;
					}
					seenHops.add(nextHop);
				}
				System.out.println();
			}
			if(initiallyConnected && !finallyConnected) { // upstreamSet got disconnected now .. record time
				recordDisconnectTime(upstreamSet);
			} 
			else if(!initiallyConnected && finallyConnected) { // upstreamSet got connected .. increment total downtime
				incrementDisconDuration(upstreamSet);
			}
		}

	}

	// we are adding code to measure duration of disconnectivity here because this is the most
	// efficient place to add it.
	// Realised that maintaining loop info is required for correctness! If we do not account for loops,
	// then the upstream set of each node in the loop would contain the other, and when the loop is
	// broken, information is lost.
	public static void changedPathCheck(int as, int dst, IA oldPath, IA newPath) {
		boolean initiallyConnected = false;
		if(instrumented) {
			initiallyConnected = upstreamASes.get(currentCustomer).contains(as);

		}
		HashSet <Integer> upstreamSet = upstreamASes.get(as);
		if(upstreamSet == null) {
			upstreamSet = new HashSet<Integer>();
			upstreamSet.add(as);
			upstreamASes.put(as, upstreamSet);
		}
		//the upstreamSet should always contain self!
		upstreamSet.add(as);
		// Case 1: both paths non-null
		// find the set of ASes in oldPath-newPath - remove upstreamNodes from these
		// find the set of ASes in newPath-oldPath - add upstream nodes to these
		oldSet.clear();
		int oldHop = -1;
		int nextHop = -1;
		if(oldPath != null && oldPath.getPath() != null) {
			oldHop = oldPath.getFirstHop();
		}
		if(newPath != null && newPath.getPath() != null) {
			nextHop = newPath.getFirstHop();
		}
		seenHops.clear();
		seenHops.add(as);
		long loopDuration = 0;
		HashSet<Integer> currentLoop;
		while( oldHop != -1 ) {
			if(upstreamASes.get(oldHop).contains(as)) {
				upstreamASes.get(oldHop).removeAll(upstreamSet);
			}
//			else {
//				if(!loopMarkerAS.contains(as)) {
//					System.out.println("Assertion Failed! loopMarkerAS.contains(" + as + ")");
//					System.exit(-1);
//				}
				// the current 'as' is the loopMarker
				// it is definitely part of the loop, and the loop is being broken
				// no point going further, as upstream property doesn't hold after this node
				
				// the loopmarker is breaking the loop, so nothing needs to be done,
				// upstreamSet is consistent
				if(loopMarkerAS.contains(as)) {
					currentLoop = asLoopMap.get(as);
					loopDuration = (simTime - loopTimeMap.remove(currentLoop));
//					System.out.println(currentLoop + " " + loopDuration);
					// mark all the upstream ASes as being affected by the loop
					allLoopAffectedASes.addAll(upstreamSet);
					if(loopDuration > MIN_LOOP_DURATION)
						loopAffectedASes.addAll(upstreamASes.get(as));
					if(loopDuration > longestLoop)
						longestLoop = loopDuration;
					loopMarkerAS.remove(as);
					asLoopMap.remove(as);

//					System.out.println("LOOP being broken: loopMarker = " + as);
					break;
				}
			if(loopMarkerAS.contains(oldHop)) {
				// the current hop we are processing is a loop marker
				// thus .. theres no point going further because 'as' is not
				// in the upstreamSet of the next node
//				HashSet<Short> tempSeen = new HashSet<Short>();
//				tempSeen.add(as);
//				System.out.print("Path = " + as);
//				short tempNextHop = -1;
//				if(oldPath != null && oldPath.path != null) {
//					tempNextHop = oldPath.getFirstHop();
//				}
//				while( tempNextHop != -1 ) {
//					System.out.print(" " + tempNextHop);
//					tempNextHop = asMap.get(tempNextHop).getNextHop(dst);
//					if(tempSeen.contains(tempNextHop)) { // loop found .. do stuff?
//						break;
//					}
//					tempSeen.add(tempNextHop);
//				}
//				
//				tempSeen.clear();
//				tempSeen.add(as);
//				System.out.print(" New Path = " + as);
//				tempNextHop = asMap.get(as).getNextHop(dst);	
//				while( tempNextHop != -1 ) {
//					System.out.print(" " + tempNextHop);
//					tempNextHop = asMap.get(tempNextHop).getNextHop(dst);
//					if(tempSeen.contains(tempNextHop)) { // loop found .. do stuff?
//						break;
//					}
//					tempSeen.add(tempNextHop);
//				}
//				System.out.println();

				currentLoop = asLoopMap.get(oldHop);
				if(!currentLoop.contains(as)) {
					// the loop is not being broken because some AS upstream of the loop
					// is changing its next hop
					allLoopAffectedASes.addAll(upstreamSet);
					break;
				}
				
				// the AS changing its next hop is in the loop, so this loop no longer exists
				// since loop is being broken, remove the mapping
				loopMarkerAS.remove(oldHop);
				asLoopMap.remove(oldHop);
				loopDuration = (simTime - loopTimeMap.remove(currentLoop));
//				System.out.println(currentLoop + " " + loopDuration);
				// mark all the upstream ASes as being affected by the loop
				allLoopAffectedASes.addAll(upstreamSet);
				if(loopDuration > MIN_LOOP_DURATION)
					loopAffectedASes.addAll(upstreamASes.get(as));
				if(loopDuration > longestLoop)
					longestLoop = loopDuration;

//				System.out.println("LOOP being broken: loopMarker = " + oldHop);
				// now make the ex-loopmarker as upstream of all nodes upto current node
				int loopHop = asMap.get(oldHop).getNextHop(dst);
				HashSet<Integer> markerUpstream = upstreamASes.get(oldHop);
				
				// hopefully no null pointers here ..
				while(loopHop != as) {
//					System.out.println("AS = " + as + " LoopHop = " + loopHop);
//					if(markerUpstream.contains((short)5) && loopHop == 197) {
//						System.out.println(simTime + ": AS" + as + " Adding 5 to upstream(197) loop-break-code");
//					}
					upstreamASes.get(loopHop).addAll(markerUpstream);
					loopHop = asMap.get(loopHop).getNextHop(dst);
				} 
				upstreamASes.get(loopHop).addAll(markerUpstream);
				break;
			}
			oldHop = asMap.get(oldHop).getNextHop(dst);
			if(seenHops.contains(oldHop)) { // loop found .. do stuff?
				break;
			}
			seenHops.add(oldHop);
		}

		seenHops.clear();
		seenHops.add(as);


		while( nextHop != -1 ) {
			if(!upstreamASes.containsKey(nextHop)) {
				upstreamASes.put(nextHop, new HashSet<Integer>());
			}
			if(upstreamSet.contains(nextHop)) { // we are forming a loop
				loopMarkerAS.add(as);
//				System.out.println("Avoiding loop .. AS" + as + ": " + nextHop + " " + asMap.get(nextHop).getNextHop(dst));
				while( !seenHops.contains(nextHop)) {
					seenHops.add(nextHop);
					nextHop = asMap.get(nextHop).getNextHop(dst);
				}
				currentLoop = new HashSet<Integer>(seenHops);
				asLoopMap.put(as,currentLoop);
				
				// store start time of the loop
				loopTimeMap.put(currentLoop, simTime);
				
//				System.out.println("Current loop = " + seenHops);
				break;
			}
//			if(upstreamSet.contains((short)5) && nextHop == 197) {
//				System.out.println(simTime + ": AS" + as + " Adding 5 to upstream(197)");
//			}
			upstreamASes.get(nextHop).addAll(upstreamSet);
			if(loopMarkerAS.contains(nextHop))
				break;
			nextHop = asMap.get(nextHop).getNextHop(dst);
			
			if(seenHops.contains(nextHop)) { // loop found .. do stuff?
				break;
			}
			seenHops.add(nextHop);
		}
		if(instrumented) {
			boolean finallyConnected = upstreamASes.get(currentCustomer).contains(as);
//			HashSet<Short> tempSet = getConnectedASes(currentCustomer);
//			System.out.print("AS" + as + " Dst=" + dst + " upstream(customer).size=" + upstreamASes.get(currentCustomer).size()
//					+ " connectedASes.size()=" + tempSet.size() + " Missing " );
//			HashSet<Short> newTemp = new HashSet<Short>(upstreamASes.get(currentCustomer));
//			for(Iterator<Short>it = newTemp.iterator(); it.hasNext();) {
//				System.out.print(pathExists(it.next(), currentCustomer));
//			}
//			if(!tempSet.equals(newTemp)) {
//				System.out.println("Error .. values don't match");
//				tempSet.removeAll(newTemp);
//				System.out.print(tempSet);
//				System.exit(-1);
//			}
//			System.out.println();

//			short problemAS = 1;
//			if(upstreamSet.contains(problemAS)) {
//				System.out.println(simTime + ": AS" + as + ": Path: " + printPath(problemAS, currentCustomer));
//			}
			if(initiallyConnected && !finallyConnected) { // upstreamSet got disconnected now .. record time
				recordDisconnectTime(upstreamSet);
			} 
			else if(!initiallyConnected && finallyConnected) { // upstreamSet got connected .. increment total downtime
				incrementDisconDuration(upstreamSet);
			}
		}

	}
	
	public static void changedPath(int as, int dst, IA oldPath, IA newPath) {
		//		System.out.println(as + " " + dst);
		switch(simMode) {
		case 0:
		case 1:
		case 5:
		case 6:
		case 7: //CHANGED
			changedPathLoopCheck(as, dst, oldPath, newPath);
			break;

		default:
			//changedPathCheck(as, dst, oldPath, newPath);
			changedPathLoopCheck(as, dst, oldPath, newPath);		
			break;
		}
	}

	public static void changedPathLoopCheck(int as, int dst, IA oldPath, IA newPath) {
//		debug("AS" + as + ": oldPath = " + (oldPath==null?oldPath:oldPath.path) + "; newPath = " + (newPath==null?newPath:newPath.path) );
		HashSet <Integer> upstreamSet = upstreamASes.get(as);
		if(upstreamSet == null) {
			upstreamSet = new HashSet<Integer>();
			upstreamSet.add(as);
			upstreamASes.put(as, upstreamSet);
		}
		//the upstreamSet should always contain self!
		upstreamSet.add(as);

		// Case 1: both paths non-null
		// find the set of ASes in oldPath-newPath - remove upstreamNodes from these
		// find the set of ASes in newPath-oldPath - add upstream nodes to these
		int nextHop = -1;
		int oldHop = -1;

		if(oldPath != null && oldPath.getPath() != null) {
			oldHop = oldPath.getFirstHop();
		}
		if(newPath != null && newPath.getPath() != null) {
			nextHop = newPath.getFirstHop();
		}
		seenHops.clear();
		seenHops.add(as);


		while( oldHop != -1 ) {
			upstreamASes.get(oldHop).removeAll(upstreamSet);
			oldHop = asMap.get(oldHop).getNextHop(dst);
			if(seenHops.contains(oldHop)) { // loop found .. do stuff?
				break;
			}
			seenHops.add(oldHop);
		}

		seenHops.clear();
		seenHops.add(as);

		ArrayList<Integer> pathSoFar = new ArrayList<Integer>(as);
		HashSet<Integer> loop = new HashSet<Integer>();

		while( nextHop != -1 ) {
			if(!upstreamASes.containsKey(nextHop)) {
				upstreamASes.put(nextHop, new HashSet<Integer>());
			}
			upstreamASes.get(nextHop).addAll(upstreamSet);
			nextHop = asMap.get(nextHop).getNextHop(dst);
			pathSoFar.add(nextHop);
			if(seenHops.contains(nextHop)) { // loop found .. do stuff?
				int index = pathSoFar.indexOf(nextHop);
				// approximation ... loop exists till it is resolved.

//				System.out.print(simTime + ": AS" + as +": Routing loop involving : ");
				for(int i=index; i<pathSoFar.size(); i++) {
//					System.out.print(pathSoFar.get(i) + " ");
					loop.add(pathSoFar.get(i));
					if(loopStart == -1)
						loopStart = simTime;
				}
				addLoop(loop, simTime);
//				System.out.println();
				break;
			}
			seenHops.add(nextHop);
		}
		if(nextHop == -1) { // there was no loop
			if(loopyAS.contains(as)) { // there was a loop before .. so loop is resolved
//				System.out.println(simTime + ": Loop resolved : AS" + as);
				removeLoop(as, simTime);
				loopResolved = simTime;
			}
		}
		else { // there was a loop 
			addLoopAffected(as);
		}
//		for(Iterator<Short> it = toAdd.iterator(); it.hasNext();) {
//		short asn = it.next();
//		if(!upstream.containsKey(asn)) {
//		upstream.put(asn, new HashSet<Short>());
//		}
//		upstream.get(asn).addAll(upstreamSet);
//		}
	}

	private static void removeLoop(int as, long simTime2) {
		HashSet<Integer> loop = asLoopMap.get(as);
		for(Iterator<Integer> it = loop.iterator(); it.hasNext();) {
			int asn = it.next();
			loopyAS.remove(asn);
			asLoopMap.remove(asn);
		}
		loopTimeMap.remove(loop);
		long time = 0;
		if(!loopTimeMap.containsKey(loop))
			return;
		totalLoopDuration = simTime2 - time;
	}

	private static void addLoop(HashSet<Integer> loop, long simTime2) {
		boolean newLoop = false;
		for(Iterator<Integer> it = loop.iterator(); it.hasNext();) {
			int asn = it.next();
			asLoopMap.put(asn, loop);
			if(!loopyAS.contains(asn)) { // new loop
				loopyAS.add(asn);
				newLoop = true;
			}
		}
		if(newLoop) {
			loopTimeMap.put(loop, simTime2);
		}

	}

	/**
	 * @return The current simulation time
	 */
	public static long getTime() {
		return simTime;
	}

	public static void addEvent(Event e) {
		e.setTieBreaker(tieBreaker++);
		eventQueue.add(e);
		
		if(e.eventType == Event.MSG_EVENT) {
			if(e.msg.messageType == Message.UPDATE_MSG || e.msg.messageType == Message.WITHDRAW_MSG) {
				numUpdatesEnqueued++;
				numBGPEnqueued++;
			}
			else if(e.msg.messageType == Message.CONTROL_MSG) {
				numUpdatesEnqueued++;
				numBGPEnqueued++;
			}
		}
		else if(e.eventType == Event.MRAI_EVENT) {
			numBGPEnqueued++;
		}

	}

	public static void recordFlood(int asn, FloodMessage msg, Set<RootCause> unfinished) {
		// only transit ASes participate in snapshot and flooding
		// hack! -- ignore if not transit
		if(!transitASes.contains(asn))
			return;
		
		if(floodMap.containsKey(asn)) {
			System.out.println("Previous Flood didn't complete");
			System.exit(-1);
		}
		floodMap.put(asn, msg);
		numFloods++;
//		System.out.println("@" + simTime + " " + numFloods + "/" + numAses);
		activeTriggers.addAll(unfinished);
		unfinishedThisEpoch.addAll(unfinished);
//		System.out.println(simTime + ": " + numFloods + ": Flood received from AS" + asn);
//		System.out.println(msg);
		if(numFloods == numTransitASes) { // we have received all floods .. now compute closure

			HashSet<RootCause> incomplete = new HashSet<RootCause>();
			HashSet<UpdateDependency> condIncomplete = new HashSet<UpdateDependency>();
			int numIncomplete = 0;
			int numCondIncomplete = 0;
			for(Iterator<FloodMessage> it = floodMap.values().iterator(); it.hasNext();) {
				FloodMessage m = it.next();

				incomplete.addAll(m.incompleteUpdates);
				numIncomplete += m.incompleteUpdates.size();

				condIncomplete.addAll(m.condIncompleteUpdates);
				numCondIncomplete += m.condIncompleteUpdates.size();
			}
//			System.out.println(allIncomplete);
			System.out.print("@" + simTime + " ");
			// print numIncompleteThisEpoch:numSeenSoFar
			System.out.print(unfinishedThisEpoch.size() + ":" + activeTriggers.size() + " ");
			// print #incompleteThisEpoch:#conditionalThisEpoch
			System.out.print(numIncomplete + ":" + numCondIncomplete + " ");
			// print #incompleteOptimized:#conditionalOptimized
			System.out.print(incomplete.size() + ":" + condIncomplete.size() + " ");
//			System.out.println();
			Set<RootCause> allIncomplete = computeAllIncompleteUpdates(incomplete, condIncomplete);
			System.out.println(allIncomplete.size());
			// now return this information to each of the ASes
			for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
				it.next().floodCompleted(allIncomplete);

			}
			numFloods = 0;
			floodMap.clear();
			unfinishedThisEpoch.clear();
			numFloodsDone++;

		}
	}
	public static HashSet<Integer> computeTier1() {
		HashSet<Integer> temp = new HashSet<Integer>();
		for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
			AS a = it.next();
			if(a.providers.size() == 0) {
				//				System.out.println("AS" + a.asn + ": #customers = " + a.customers.size() + " #peers = " + a.peers.size() );
				if(a.customers.size() + a.peers.size() > TIER1_THRESHOLD)
					temp.add(a.asn);
			}
		}
		return temp;
	}

	// Any AS that has atleast 1 customer is a transit AS
	public static HashSet<Integer> computeTransit() {
		HashSet<Integer> temp = new HashSet<Integer>();
		for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
			AS a = it.next();
			if(a.customers.size() > 0 && (a.providers.size() + a.peers.size() > 0)) {
				temp.add(a.asn);
			}
		}
		return temp;
	}
	
	/**
	 * computes stub ases.  A stub is an AS with no customers
	 * @return hashset of stub asnums
	 */
	public static HashSet<Integer> computeStubs(){
		HashSet<Integer> temp = new HashSet<Integer>();
		for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
			AS a = it.next();
			if(a.customers.size() == 0) {
				temp.add(a.asn);
			}
		}
		return temp;
	}

	// computes the distance to a tier-1 AS from each of the ases
	private static void getDistanceToTier1(HashSet<Integer> asSet) {
		int numFailed = 0;
		for(Iterator <Integer> it = asSet.iterator(); it.hasNext();) {
			int srcAS = it.next();
			int currentAS = srcAS;
			int numHops = 0;
			while( !tier1ASes.contains(currentAS)) {
				AS cAS = asMap.get(currentAS);
				if(cAS.providers.size() > 0)
					currentAS = cAS.providers.get(Math.abs(r.nextInt())%cAS.providers.size());
				else if( cAS.peers.size() > 0 )
					currentAS = cAS.peers.get(Math.abs(r.nextInt())%cAS.peers.size());
				else
					break;
				numHops++;
			}
			if(!tier1ASes.contains(currentAS)) {
				numFailed++;
			}
			
			System.out.println(srcAS + " -> " + currentAS + " hops = " + numHops);

		}
		System.out.println("Failed to reach tier-1 " + numFailed + " out of " + asSet.size());
		
	}

	/**
	 * This computes the fixed point of all incomplete updates given the set of definitely incomplete
	 * updates and the set of conditionally incomplete updates
	 * @param incompleteUpdates
	 * @param condIncompleteUpdates
	 * @return
	 */
	private static Set<RootCause> computeAllIncompleteUpdates(Set<RootCause> incompleteUpdates, Set<UpdateDependency>condIncompleteUpdates) {
		HashSet<RootCause> allIncompleteUpdates = new HashSet<RootCause>(incompleteUpdates);
		// we need to compute the fixed point of this set.
		// for now, we use brute force. might think of more efficient way later 
		boolean fixedPt = false;
		while(!fixedPt) {
			fixedPt = true;
			for(Iterator<UpdateDependency> it = condIncompleteUpdates.iterator(); it.hasNext(); ) {
				UpdateDependency temp = it.next();
				if(allIncompleteUpdates.contains(temp.dependsOn) && !allIncompleteUpdates.contains(temp.update)) {
					allIncompleteUpdates.add(temp.update);
					it.remove();
					fixedPt = false;
				}
			}
		}
		return allIncompleteUpdates;
	}

	//thread to have AS asynchronosly process events.
	public static class HandleEventThread implements Runnable
	{
		public AS targetAS;
		public Event e;
		public boolean running;
		
		public HandleEventThread(){
			running = false;
		}
		public HandleEventThread(AS targetAS, Event e)
		{
			this.targetAS = targetAS;
			this.e = e;
		}
		@Override
		public void run() {
			running = true;
			System.out.println("threadstart");
			targetAS.handleEvent(e);
			running = false;
			System.out.println("threadend");
			
		}
		
	};
	
	private static int NUMTHREADS = 1; //specify number of threads for the threadpool of run()
	private static long timeout = 30000;
	
	/**
	 * This is the main function which runs the simulation. It picks events out of the queue
	 * and updates the current time, and sends the events to the correct AS to handle.
	 *
	 */
	public static boolean run() {
//		System.out.println("Starting the run");
//		long startTime = System.currentTimeMillis();
//		HashSet<Short> disconnected = new HashSet<Short>();
		numUpdateMessages = 0;
		numWithdrawMessages = 0;
/*		HandleEventThread[] threadPool = new HandleEventThread[NUMTHREADS]; 
		for(int i = 0; i < NUMTHREADS; i++)
		{
			threadPool[i] =  new HandleEventThread();
		}*/
		long currentTime = System.currentTimeMillis();
		PriorityQueue<Event> eventqueuecopy = eventQueue;
//		for(AS aAS : asMap.values())
//		{
//			if(aAS.bestpathNullCheck())
//			{
//				System.out.println("a best path has a null value");
//			}
//		}
		while(true) {
			if(System.currentTimeMillis() - currentTime > timeout)
			{
				System.out.println("taking longer than: " + timeout/1000 + "stopping this run");
				eventQueue.clear();
//				for(AS aAS : asMap.values())
//				{
//					if(aAS.bestpathNullCheck())
//					{
//						System.out.println("a best path has a null value");
//					}
//				}
				return false;
				//System.exit(1);
			}
		
		//	if(eventQueue.size() % 100 == 0)
			//	System.out.println("eventqueue size: " + eventQueue.size());
	//		synchronized(eventQueue){ //get lock on eventQueue
			//	System.out.println(eventQueue.size());
			Event e = eventQueue.poll();
	//		}
			
			if( e == null) {
				// system is in a stable state
				// however, this might not happen if we have snapshot messages periodically
				//				System.out.println("Queue Empty!");
			/*	if(!slept){ //if we get here, try sleeping to allow threads to finish if we haven't already done so.
					try {
						Thread.sleep(4000);
					} catch (InterruptedException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					slept = true;
				}
				else{ //we slept once and no new thread have been added since, we're done
					return;
				}*/
				return true;

			}
			else { //got a thread, make sure slept is false
				// the message is printed first and then processed!
				if(e.eventType == Event.MSG_EVENT) {
					debug(e.toString());
					if(e.msg.messageType == Message.UPDATE_MSG || e.msg.messageType == Message.WITHDRAW_MSG) {
						numUpdatesEnqueued--;
						numBGPEnqueued--;
						if(e.msg.messageType == Message.UPDATE_MSG)
							numUpdateMessages++;
						else
							numWithdrawMessages++;
						lastSimTime = e.scheduledTime;
						if(transitASes.contains(e.eventFor)) { // this is a update/withdraw for a transit AS
							lastTransitSimTime = e.scheduledTime;
						}
					}
					else if(e.msg.messageType == Message.CONTROL_MSG) {
						numUpdatesEnqueued--;
						numBGPEnqueued--;
					}
				}
				else if(e.eventType == Event.MRAI_EVENT) {
					numBGPEnqueued--;
				}
				simTime = e.scheduledTime;
				int asn = e.eventFor;
				AS targetAS = asMap.get(asn);
				if(targetAS == null) {
					// error!!
					System.err.println("Invalid target AS: " + asn);
					System.exit(-1);
				}
				
				//get a thread off the thread pool, continue looping until a thread is not running
				boolean foundThread = false;
				HandleEventThread availableThread = null;
			/*	while(!foundThread)
				{
					for(int i = 0; i < NUMTHREADS; i++)
					{
						availableThread = threadPool[i];
						if(!availableThread.running) //found a thread that isn't running
						{
							foundThread = true;
							break; //break
						}
						
					}
				}*/
			//	availableThread.e = e;
			//	availableThread.targetAS = targetAS;
			//	new Thread(availableThread).start();
				targetAS.handleEvent(e);

				// system is stable once all updates have been processed
				// however, we want it to run one more flood!
//				if(numBGPEnqueued == 0 && numUpdatesEnqueued == 0) {
//				if(numFloodsDone == 1)
//				return;
//				else
//				numFloodsDone = 0;
//				}

			}
		} // end while
	} // end function run()

	/**
	 * This is the main function which runs the simulation. It picks events out of the queue
	 * and updates the current time, and sends the events to the correct AS to handle.
	 *
	 */
	public static void runInstrumented() {
//		System.out.println("Starting the run");
//		long startTime = System.currentTimeMillis();
//		HashSet<Short> disconnected = new HashSet<Short>();
		numUpdateMessages = 0;
		numWithdrawMessages = 0;
		while(true) {
			Event e = eventQueue.poll();
			if( e == null) {
				// system is in a stable state
				// however, this might not happen if we have snapshot messages periodically
//				System.out.println("Queue Empty!");
				return;
			}
			else {
				if(e.eventType == Event.MSG_EVENT) {
					if(e.msg.messageType == Message.UPDATE_MSG || e.msg.messageType == Message.WITHDRAW_MSG) {
//						debug(e.toString());
						numUpdatesEnqueued--;
						numBGPEnqueued--;
						if(e.msg.messageType == Message.UPDATE_MSG)
							numUpdateMessages++;
						else
							numWithdrawMessages++;
						lastSimTime = simTime;
					}
					else if(e.msg.messageType == Message.CONTROL_MSG) {
						numUpdatesEnqueued--;
						numBGPEnqueued--;
					}
				}
				else if(e.eventType == Event.MRAI_EVENT) {
					numBGPEnqueued--;
				}
				simTime = e.scheduledTime;
				int asn = e.eventFor;
				AS targetAS = asMap.get(asn);
				if(targetAS == null) {
					// error!!
					System.err.println("Invalid target AS: " + asn);
					System.exit(-1);
				}
				targetAS.handleEvent(e);

				if(e.eventType == Event.MSG_EVENT) {
					if(e.msg.messageType == Message.UPDATE_MSG || e.msg.messageType == Message.WITHDRAW_MSG) {
//						System.out.println(numUpdateMessages);
						// since we just processed an update/withdrawal, we need to check the connectivity
						// it might be more efficient to do this in the loop-check, but this keeps that code
						// from getting even more cluttered
						currentConnectedASes = new HashSet<Integer>(upstreamASes.get(currentCustomer));
						// if current = prev, then no change in connectivity has happened
						// else, current-prev is the set which just got connectivity restored
						// and prev-current is the set which just lost connectivity
						if(currentConnectedASes.equals(prevConnectedASes)) {

						}
						else { // there has been a connectivity change
							currentConnectedASes.removeAll(prevConnectedASes); // the set of ASes which just got reconnected
							// increment their downtime by their duration of disconnection
							for(Iterator<Integer> it = currentConnectedASes.iterator(); it.hasNext();) {
								int tempAS = it.next();
								// if prevDisconTime doesn't exist for tempAS, something's fishy!
								long duration = simTime - prevDisconTime.get(tempAS);
//								System.out.println("AS" + tempAS + ": Duration = " + duration);
								incrementDisconDuration(tempAS, duration);
							}
							prevConnectedASes.removeAll(upstreamASes.get(currentCustomer)); // the set of ASes which just got disconnected
							for(Iterator<Integer> it = prevConnectedASes.iterator(); it.hasNext();) {
								int tempAS = it.next();
								// store current time as the time when the AS got disconnected
								prevDisconTime.put(tempAS, simTime);
							}
						}
						prevConnectedASes = new HashSet<Integer>(upstreamASes.get(currentCustomer));
					}
				}		
			}
		} // end while
	} // end function run()

	
	public static String topoFile;
	/**
		 * Reads input files to figure out topology. Initializes all the ASes
		 * @param args
		 */
		public static void main(String[] args) throws Exception {
			/*if( args.length != 5 ) {
				System.err.println("Usage:\n\t java Simulator <topology-file> <link-failure-file> <single-homed-parents-file> <seed-value> <mode>\n");
				System.exit(-1);
			}*/
			
			ArgumentParser parser = ArgumentParsers.newArgumentParser("Simulator")
					.defaultHelp(true)
					.description("Simulator to simulate integrated advertisements and passthroughs");
			parser.addArgument("ASRelationships").metavar("ASRel").type(String.class);
			parser.addArgument("ASTypesFile").metavar("ASTypes").type(String.class);
			parser.addArgument("--IntraDomainFile").metavar("IntraDomain").type(String.class);
			parser.addArgument("outFile").metavar("file to output results").type(String.class);
			parser.addArgument("--failLinksFile").metavar("FailLinks").type(String.class);
			parser.addArgument("--parentsFile").metavar("ParentsFile").type(String.class);
			parser.addArgument("--seed").required(true).metavar("seed").type(Long.class);
			parser.addArgument("--sim").required(true).metavar("sim").type(Integer.class);
			parser.addArgument("--monitorFrom").required(true).metavar("monitoring").type(Integer.class);
			parser.addArgument("--useBandwidth").required(true).metavar("useBandwidth").type(Integer.class);
			parser.addArgument("--forX").required(true).metavar("forX").type(Float.class);
			parser.addArgument("--metric").required(true).metavar("metric to use").type(Integer.class);
			parser.addArgument("--maxPaths").metavar("max paths for replacement").type(Integer.class).setDefault(10);
			Namespace arguments = null;
			try{
	//			System.out.println(parser.parseArgs(args));
				arguments = parser.parseArgs(args);				
			}
			catch(ArgumentParserException e){
				parser.handleError(e);
				System.exit(1);
			}
				
			
			NUM_PATH_CAP = arguments.getInt("maxPaths");			
			System.out.println("numpathcap: " + NUM_PATH_CAP);
			out = new BufferedWriter(new FileWriter("output.log"));
			outFile = new BufferedWriter(new FileWriter(arguments.getString("outFile")));
			seedVal = arguments.getLong("seed");
			String topologyFile = arguments.getString("ASRelationships");
			topoFile = topologyFile; 
			//file for AStypes
			String typeFile = arguments.getString("ASTypesFile");
			String intraFile = arguments.getString("IntraDomainFile");
			String linkFile = arguments.getString("--failLinksFile");
			String parentsFile = arguments.getString("--parentsFile");
			int monitorFrom = arguments.getInt("monitorFrom");
			boolean useBandwidth = (arguments.getInt("useBandwidth") == 1) ? true : false;
			simMode = arguments.getInt("sim");
			float xVal = arguments.getFloat("forX");
			int metric = arguments.getInt("metric");			
			int primaryType = readTypes(typeFile); //reading types must go before readtopology, otherwise allnodes will be bgp
			readTopology(topologyFile, useBandwidth);
			preProcessReplacement();
		//	readIntraDomain(intraFile);
			//readLinks(linkFile);
			//readParents(parentsFile);
			r = new Random(seedVal);
			trimASMap(largestConnectedComponent()); //trims the AS map to be one connected component
			numAses = asMap.size();
			if(xVal == 0)
			{
				monitorFrom = ALL;
			}
			switch(simMode) {
			case 0:
		//	    runFCPSimulations();
			    break;
	
			case 1:
		//	    runFCPRandomSimulations();
			    break;
	
			case 2:
		//	    runNewRegularSimulations();
			    break;
	
			case 3:
				iaBasicSimulationRichWorld(monitorFrom, useBandwidth, xVal, metric, primaryType);
			    break;
	
			case 4:
				iaBasicSimulationReplacementNumPaths(monitorFrom, useBandwidth, xVal, metric);
			    break;
	
			case 5:
			//    runTAASSimulations();
				iaBasicSimulationTransitsOnlyTrueCost(monitorFrom, useBandwidth, xVal, metric);
			    break;
	
			case 6:
			//	iaBasicSimulation();
				iaBasicSimulationTransitsOnlyWiserCost(monitorFrom);
			    break;
			  
			case 7:
	//			System.out.println("Number of connected components: " + numConnectedComponents() + "\n");
	//			System.out.println("Number of connected components: " + numConnectedComponents() + "\n");
			//	iaBasicSimulation();
				//iaSumSimulation();
				iaBasicSimulationTransitsOnlySBGP(monitorFrom);
				break;
	
			default:
			    System.err.println("Invalid simulation mode!");
			    break;
			}
			out.close();
		}


	/**
		 * Reads input files to figure out topology. Initializes all the ASes
		 * @param args
		 *//*
		public static void main(String[] args) throws Exception {
			if( args.length != 5 ) {
				System.err.println("Usage:\n\t java Simulator <topology-file> <link-failure-file> <single-homed-parents-file> <seed-value> <mode>\n");
				System.exit(-1);
			}
			
			ArgumentParser parser = ArgumentParsers.newArgumentParser("Simulator")
					.defaultHelp(true)
					.description("Simulator to simulate integrated advertisements and passthroughs");
			parser.addArgument("ASRelationships").metavar("ASRel").type(String.class);
			parser.addArgument("ASTypesFile").metavar("ASTypes").type(String.class);
			parser.addArgument("--IntraDomainFile").metavar("IntraDomain").type(String.class);
			parser.addArgument("outFile").metavar("file to output results").type(String.class);
			parser.addArgument("--failLinksFile").metavar("FailLinks").type(String.class);
			parser.addArgument("--parentsFile").metavar("ParentsFile").type(String.class);
			parser.addArgument("--seed").required(true).metavar("seed").type(Long.class);
			parser.addArgument("--sim").required(true).metavar("sim").type(Integer.class);
			Namespace arguments = null;
			try{
	//			System.out.println(parser.parseArgs(args));
				arguments = parser.parseArgs(args);				
			}
			catch(ArgumentParserException e){
				parser.handleError(e);
				System.exit(1);
			}
				
			
			
			out = new BufferedWriter(new FileWriter("output.log"));
			outFile = new BufferedWriter(new FileWriter(arguments.getString("outFile")));
			seedVal = arguments.getLong("seed");
			String topologyFile = arguments.getString("ASRelationships");
			//file for AStypes
			String typeFile = arguments.getString("ASTypesFile");
			String intraFile = arguments.getString("IntraDomainFile");
			String linkFile = arguments.getString("--failLinksFile");
			String parentsFile = arguments.getString("--parentsFile");
			simMode = arguments.getInt("sim");
			readTypes(typeFile); //reading types must go before readtopology, otherwise allnodes will be bgp
			readTopology(topologyFile);
		//	readIntraDomain(intraFile);
			//readLinks(linkFile);
			//readParents(parentsFile);
			
			r = new Random(seedVal);
			trimASMap(largestConnectedComponent()); //trims the AS map to be one connected component
			numAses = asMap.size();
			switch(simMode) {
			case 0:
		//	    runFCPSimulations();
			    break;
	
			case 1:
		//	    runFCPRandomSimulations();
			    break;
	
			case 2:
		//	    runNewRegularSimulations();
			    break;
	
			case 3:
				iaBasicSimulationStubsOnly();
			    break;
	
			case 4:
				verificationSimulation();
			    break;
	
			case 5:
			//    runTAASSimulations();
				iaBasicSimulationTransitsOnlyTrueCost();
			    break;
	
			case 6:
			//	iaBasicSimulation();
				iaBasicSimulationTransitsOnlyWiserCost();
			    break;
			  
			case 7:
	//			System.out.println("Number of connected components: " + numConnectedComponents() + "\n");
	//			System.out.println("Number of connected components: " + numConnectedComponents() + "\n");
			//	iaBasicSimulation();
				iaSumSimulation();
				break;
	
			default:
			    System.err.println("Invalid simulation mode!");
			    break;
			}
			out.close();
		}*/

	/**
	 * Reads input files to figure out topology. Initializes all the ASes
	 * @param args
	 */
	/*public static void main(String[] args) throws Exception {
		if( args.length != 5 ) {
			System.err.println("Usage:\n\t java Simulator <topology-file> <link-failure-file> <single-homed-parents-file> <seed-value> <mode>\n");
			System.exit(-1);
		}
		
		ArgumentParser parser = ArgumentParsers.newArgumentParser("Simulator")
				.defaultHelp(true)
				.description("Simulator to simulate integrated advertisements and passthroughs");
		parser.addArgument("ASRelationships").metavar("ASRel").type(String.class);
		parser.addArgument("ASTypesFile").metavar("ASTypes").type(String.class);
		parser.addArgument("--IntraDomainFile").metavar("IntraDomain").type(String.class);
		parser.addArgument("outFile").metavar("file to output results").type(String.class);
		parser.addArgument("--failLinksFile").metavar("FailLinks").type(String.class);
		parser.addArgument("--parentsFile").metavar("ParentsFile").type(String.class);
		parser.addArgument("--seed").required(true).metavar("seed").type(Long.class);
		parser.addArgument("--sim").required(true).metavar("sim").type(Integer.class);
		Namespace arguments = null;
		try{
//			System.out.println(parser.parseArgs(args));
			arguments = parser.parseArgs(args);				
		}
		catch(ArgumentParserException e){
			parser.handleError(e);
			System.exit(1);
		}
			
		
		
		out = new BufferedWriter(new FileWriter("output.log"));
		outFile = new BufferedWriter(new FileWriter(arguments.getString("outFile")));
		seedVal = arguments.getLong("seed");
		String topologyFile = arguments.getString("ASRelationships");
		//file for AStypes
		String typeFile = arguments.getString("ASTypesFile");
		String intraFile = arguments.getString("IntraDomainFile");
		String linkFile = arguments.getString("--failLinksFile");
		String parentsFile = arguments.getString("--parentsFile");
		simMode = arguments.getInt("sim");
		readTypes(typeFile); //reading types must go before readtopology, otherwise allnodes will be bgp
		readTopology(topologyFile);
	//	readIntraDomain(intraFile);
		//readLinks(linkFile);
		//readParents(parentsFile);
		
		r = new Random(seedVal);
		trimASMap(largestConnectedComponent()); //trims the AS map to be one connected component
		numAses = asMap.size();
		switch(simMode) {
		case 0:
	//	    runFCPSimulations();
		    break;

		case 1:
	//	    runFCPRandomSimulations();
		    break;

		case 2:
	//	    runNewRegularSimulations();
		    break;

		case 3:
			iaBasicSimulationStubsOnly();
		    break;

		case 4:
			verificationSimulation();
		    break;

		case 5:
		//    runTAASSimulations();
			iaBasicSimulationTransitsOnly();
		    break;

		case 6:
			iaBasicSimulation();
		    break;
		  
		case 7:
//			System.out.println("Number of connected components: " + numConnectedComponents() + "\n");
//			System.out.println("Number of connected components: " + numConnectedComponents() + "\n");
		//	iaBasicSimulation();
			iaSumSimulation();
			break;

		default:
		    System.err.println("Invalid simulation mode!");
		    break;
		}
		out.close();
	}*/

	/**
	 * method that figures via breadthfirst search the number of connected components in the 
	 * topology (asMap).
	 * @return returns the number of connected components in the asMap
	 */
	public static int numConnectedComponents()
	{
		int numConnectedComponents = 0;
		HashSet<Integer> verticesSeenSoFar = new HashSet<Integer>();
//		System.out.println("[DEBUG] total ASes: " + asMap.size());
		int ccSizeAggregateSum = 0;
		for(Integer asMapKey: asMap.keySet()){
			//perform breadth first search
			if(!verticesSeenSoFar.contains(asMapKey))
			{
				numConnectedComponents++;
				int ccSize = 0;
	//			System.out.println("DEBUG CCs: " + numConnectedComponents );
				ArrayDeque<Integer> searchQueue = new ArrayDeque<Integer>();
				searchQueue.add(asMapKey);
				verticesSeenSoFar.add(asMapKey);
				while(!searchQueue.isEmpty())
				{
	//				System.out.print("searchqueuesize: " + searchQueue.size() + "\r");
					Integer searchEntry = searchQueue.pop();
					AS searchAS = asMap.get(searchEntry);
//					verticesSeenSoFar.add(searchEntry);
					ccSize++;
					for(Integer customer : searchAS.customers)
					{
						if(!verticesSeenSoFar.contains(customer))
						{
							searchQueue.add(customer);
							verticesSeenSoFar.add(customer);
						}
					}
					for(Integer peer : searchAS.peers)
					{
						if(!verticesSeenSoFar.contains(peer))
						{
							searchQueue.add(peer);
							verticesSeenSoFar.add(peer);
						}
					}
					for(Integer provider : searchAS.providers)
					{
						if(!verticesSeenSoFar.contains(provider))
						{
							searchQueue.add(provider);
							verticesSeenSoFar.add(provider);
						}
					}
				}
//				System.out.println("DEBUG ccsize: " + ccSize);
				ccSizeAggregateSum += ccSize;
			}
		}
//		System.out.println("[DEBUG] ccSizeAggregateSum: " + ccSizeAggregateSum);
		return numConnectedComponents;
		
	}
	
	
	/**
	 * method that computes the largest connected component and returns the list
	 * of ASes contained within it
	 * 
	 * @return list of asnums contained in largest connected component
	 */
	public static ArrayList<Integer> largestConnectedComponent()
	{
		int numConnectedComponents = 0;
		//store the largest connected component here
		ArrayList<Integer> largestConnectedComponent = new ArrayList<Integer>();
		HashSet<Integer> verticesSeenSoFar = new HashSet<Integer>();
//		System.out.println("[DEBUG] total ASes: " + asMap.size());
		int ccSizeAggregateSum = 0;
		for(Integer asMapKey: asMap.keySet()){
			//perform breadth first search
			if(!verticesSeenSoFar.contains(asMapKey))
			{
				ArrayList<Integer> connectedComponent = new ArrayList<Integer>(); //hold this connected component
				numConnectedComponents++;
				int ccSize = 0;
	//			System.out.println("DEBUG CCs: " + numConnectedComponents );
				ArrayDeque<Integer> searchQueue = new ArrayDeque<Integer>();
				searchQueue.add(asMapKey);
				verticesSeenSoFar.add(asMapKey);
				//add the first as to the temp cc list
				connectedComponent.add(asMapKey);
				while(!searchQueue.isEmpty())
				{
	//				System.out.print("searchqueuesize: " + searchQueue.size() + "\r");
					Integer searchEntry = searchQueue.pop();
					AS searchAS = asMap.get(searchEntry);
//					verticesSeenSoFar.add(searchEntry);
					ccSize++;
					for(Integer customer : searchAS.customers)
					{
						if(!verticesSeenSoFar.contains(customer))
						{
							searchQueue.add(customer);
							verticesSeenSoFar.add(customer);
							//add to cc
							connectedComponent.add(customer);
						}
					}
					for(Integer peer : searchAS.peers)
					{
						if(!verticesSeenSoFar.contains(peer))
						{
							searchQueue.add(peer);
							verticesSeenSoFar.add(peer);
							//add to cc
							connectedComponent.add(peer);
						}
					}
					for(Integer provider : searchAS.providers)
					{
						if(!verticesSeenSoFar.contains(provider))
						{
							searchQueue.add(provider);
							verticesSeenSoFar.add(provider);
							connectedComponent.add(provider);
						}
					}
				}
				//if the connected component we are in is larger than the largest so far, clone it into largest
				if(connectedComponent.size() > largestConnectedComponent.size())
				{
					largestConnectedComponent = (ArrayList<Integer>) connectedComponent.clone();
				}
//				System.out.println("DEBUG ccsize: " + ccSize);
				ccSizeAggregateSum += ccSize;
			}
		}
//		System.out.println("[DEBUG] ccSizeAggregateSum: " + ccSizeAggregateSum);
		return largestConnectedComponent;
		
	}
	//trim the AS map (contains all ASes and relations) so that it only contains the largest connected component
	public static void trimASMap(ArrayList<Integer> largestCC){
		
		ArrayList<Integer> asesToRemove = new ArrayList<Integer>();//add ases to remove here, to avoid concurrent modification exception
		
		// for each key in the current AS map, if it is in the largestCC, its
		// good otherwise remove it. Check to see if customers, peers and
		// providers are in there too. If not, then remove them
		for(Integer asMapKey : asMap.keySet())
		{
			//if the largeest cc doesn't have this as, remove it
			if(!largestCC.contains(asMapKey))
			{
				asesToRemove.add(asMapKey); //add as to be removed
				//asMap.remove(asMapKey); //concurrent modification exceptoin			
			}
			else
			{
				//check for customers in the as
				AS checkAS = asMap.get(asMapKey);
				for(Integer customer : checkAS.customers)
				{
					//if customers not in cc, then remove them from this AS
					
					if(!largestCC.contains(customer))
						checkAS.customers.remove(checkAS.customers.indexOf(customer)); //remove that customer
				}
				
				//check peers in the as
				for(Integer peer : checkAS.peers)
				{
					//if peer not in cc remove it
					if(!largestCC.contains(peer))
						checkAS.peers.remove(checkAS.peers.indexOf(peer)); //remove the peer
				}
				
				//check providers in as
				for(Integer provider : checkAS.providers)
				{
					//if providernot present, remove it
					if(!largestCC.contains(provider))
						checkAS.providers.remove(checkAS.providers.indexOf(provider)); //remove the provider
				}
			}
		}
		
		//remove ases to be removed from asmap
		for(Integer as : asesToRemove)
		{
			asMap.remove(as);
		}
	}
	
	
	/**
	 * used to verify the simulator, works on small manual topology
	 * displays rib and true cost of paths and forwarding table
	 */
//	public static void verificationSimulation(){
//		ArrayList<Integer> announcedASes = new ArrayList<Integer>();
//		
//		for( Integer asMapKey : asTypeDef.keySet())
//		{
//			
//			if(asTypeDef.get(asMapKey) == AS.WISER){
//				asMap.get(asMapKey).announceSelf();
//				announcedASes.add(asMapKey);
////				System.out.println("[debug] num neighbors of wiser AS: " + asMap.get(asMapKey).neighborMap.size());
//			}
//		}
//		
//		instrumented = false;
//		run();
//		
//		int costSum = 0;
//		int total = asMap.size();
//		//for all ASes, see how many got the lowest path cost path to the announced ASes.
//		for(Integer as : asMap.keySet())
//		{
//			//for each announced AS, compare their lowest outgoing wiser cost with what was received
//			AS monitoredAS = asMap.get(as); //the AS we are measuring from, should eventually be all but announced
//			for(Integer announcedAS : announcedASes)
//			{
//				//make sure that the we aren't comparing the AS who announced this to itself
//				if(as == announcedAS){
//					continue;
//				}
//				AS compareAS = asMap.get(announcedAS); //the AS that announced
//				
//				//print AS number
//				System.out.println("\nInfo for AS: " + as);
//				System.out.println("forwardnigTable: ");
//				System.out.print(monitoredAS.showFwdTable());
//				System.out.println("RiB in paths for dest: " + announcedAS);
//				// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
//				//disconnection, don't include it in our percentage.
//				if (monitoredAS.ribIn.get(announcedAS) != null) {
//					for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {
//						// all paths should have wiser information in them
//						byte[] wiserBytes = path.getProtocolPathAttribute(
//								new Protocol(AS.WISER), path.getPath());
//						String wiserProps = null;
//						int wiserCost = 0;
//						int normalization = 1;
//						// if ther is wiser props
//						if (wiserBytes[0] != (byte) 0xFF) {
//							try {
//								// fill them into our variables
//								wiserProps = new String(wiserBytes, "UTF-8");
//								String[] split = wiserProps.split("\\s+");
//								wiserCost = Integer.valueOf(split[0]);
//								normalization = Integer.valueOf(split[1]);
//							} catch (UnsupportedEncodingException e) {
//								// TODO Auto-generated catch block
//								e.printStackTrace();
//							}
//						} else {
//							if(!monitoredAS.neighborMap.containsKey(announcedAS))
//								System.out.println("[DEBUG] NO WISER PROPS FOR: " + monitoredAS.asn + " "
//									+ announcedAS);
//						}
//						
//						System.out.println(path.getPath().toString() + " cost: " + path.getTrueCost());
//						costSum += path.getTrueCost();
//						System.out.println(path.toString());
//						//debug if statement
//						if(monitoredAS.neighborMap.containsKey(compareAS.asn))
//						{							
//						//	System.out.println("[DEBUG] AS " + monitoredAS.asn + " neighbor of: " + compareAS.asn);
//							//System.out.println("[DEBUG] received lowest cost: " + wiserProps);
//							//System.out.println("[DEBUG] rib of AS is : " + monitoredAS.ribIn.toString());
//						}
//						
////						System.out.println("[DEBUG] received lowest cost: " + wiserCost);
//						//this is used for percent lowest cost
//			//			if (wiserCost == lowestCost) {
//							
//		//					costSum++;
//		//					break;
//			//			}
//
//					}// endfor
//					
//				}
//				else
//				{
//					total--;
//				}
//			}
//		}
//	}
	
//	public static void iaBasicSimulationStubsOnly(){
//		
//		
//		//ases that will be used for observation
//		ArrayList<Integer> monitorASes = new ArrayList<Integer>();
//	//	tier1ASes = computeTier1();
//
//
//		// We first announce all the tier-1 ASes and save 
//		// the paths from each of our failure-provider to the tier1
//		simTime = 0;
//		upstreamASes.clear();
//		r = new Random(seedVal);
//		ArrayList<Integer> announcedASes = new ArrayList<Integer>();
//		
//		//Find AS to use as monitor
//		//monitorASes.add((Integer) asMap.keySet().toArray()[r.nextInt(asMap.size())]); //doesn't check for overlap with special ASes, fix later
//		
//		//go through and have all wiser nodes announce themselves
//		for( Integer asMapKey : asTypeDef.keySet())
//		{
//			
//			if(asTypeDef.get(asMapKey) == AS.WISER){
//				asMap.get(asMapKey).announceSelf();
//				announcedASes.add(asMapKey);
////				System.out.println("[debug] num neighbors of wiser AS: " + asMap.get(asMapKey).neighborMap.size());
//			}
//			else
//			{
//				monitorASes.add(asMapKey);
//			}
//		}
////		System.out.println("Number of announced ASes: " + announcedASes.size());
//		instrumented = false;
//		run();
//		
//		int costSum = 0;
//		int total = monitorStubASes.size();
//		//for transit ASes only, see the sum of received paths
//		for(Integer as : monitorStubASes)
//		{
//			//for each monitored AS, compare their lowest outgoing wiser cost with what was received
//			AS monitoredAS = asMap.get(as); //the AS we are measuring from (all transits eventually)
//			for(Integer announcedAS : announcedASes)
//			{
//				//make sure that the we aren't comparing the AS who announced this to itself
//				if(as == announcedAS){
//					total--; //minus the total, because we don't include this one, basically skipping over it
//					continue;
//					
//				}
//				AS compareAS = asMap.get(announcedAS); //the AS that announced
//				//what is the lowest cost outgoing link of announced Node
//				int lowestCost = Integer.MAX_VALUE;
//	//			for(Integer neighbor: compareAS.neighborLatency.keySet())
//	//			{
//	//				if(compareAS.neighborLatency.get(neighbor) < lowestCost)
//	//				{
//	//					lowestCost = compareAS.neighborLatency.get(neighbor);
//	//				}
//	//			}
//				//System.out.println("[DEBUG] lowest cost: " + lowestCost);
//				// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
//				//disconnection, don't include it in our percentage.
//				if (monitoredAS.ribIn.get(announcedAS) != null) {
//					for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {
//						// all paths should have wiser information in them
//						byte[] wiserBytes = path.getProtocolPathAttribute(
//								new Protocol(AS.WISER), path.getPath());
//						String wiserProps = null;
//						int wiserCost = 0;
//						int normalization = 1;
//						// if ther is wiser props
//						if (wiserBytes[0] != (byte) 0xFF) {
//							try {
//								// fill them into our variables
//								wiserProps = new String(wiserBytes, "UTF-8");
//								String[] split = wiserProps.split("\\s+");
//								wiserCost = Integer.valueOf(split[0]);
//								normalization = Integer.valueOf(split[1]);
//							} catch (UnsupportedEncodingException e) {
//								// TODO Auto-generated catch block
//								e.printStackTrace();
//							}
//						} else {
//							if(!monitoredAS.neighborMap.containsKey(announcedAS))
//								System.out.println("[DEBUG] NO WISER PROPS FOR: "
//									+ announcedAS);
//						}
//						
//						costSum += path.getTrueCost();
//						
//						//debug if statement
//						if(monitoredAS.neighborMap.containsKey(compareAS.asn))
//						{							
//						//	System.out.println("[DEBUG] AS " + monitoredAS.asn + " neighbor of: " + compareAS.asn);
//							//System.out.println("[DEBUG] received lowest cost: " + wiserProps);
//							//System.out.println("[DEBUG] rib of AS is : " + monitoredAS.ribIn.toString());
//						}
//						
////						System.out.println("[DEBUG] received lowest cost: " + wiserCost);
//						//this is used for percent lowest cost
//					//	if (wiserCost == lowestCost) {
//							
//				//			costSum++;
//				//			break;
//				//		}
//
//					}// endfor
//					
//				}
//				else
//				{
//					total--;
//				}
//			}
//		}
//		
//		System.out.println("Average cost sum for transit ASes: " + String.valueOf((float) costSum/total));
//	}
	
	public static final int PARTICIPATING = 0;
	public static final int ALL = 1;
	public static final int GULF = 2;
	public static final int PART_STUBS = 3;
	/**
	 * method that runs a simuation and fills (mutates) params monitorASes adn anouncedASes.  Fills them up with information
	 * @param monitorASes ASes that we will make measurements from 
	 * @param announcedASes ASes that we announced from (will be all
	 * @param monitorFrom - monitor from just participating transits, or from all ASes (including bgp ases)
	 */
	public static void runSimulation(ArrayList<Integer> monitorASes, ArrayList<Integer> announcedASes, int monitorFrom)
	{

		switch (monitorFrom)
		{
		case PARTICIPATING: 
			for( int asMapKey : asTypeDef.keySet())
			{
				monitorASes.add(asMapKey);
			}
			break;
		case ALL:
			for(int key : asMap.keySet())
			{
				monitorASes.add(key);
			}
			break;
		case GULF:
			for(int key : asMap.keySet())
			{
				if(asMap.get(key).type == AS.BGP){
					monitorASes.add(key);
				}
			}
			break;
		case PART_STUBS:
			for(int stub : computeStubs())
			{
				if(asMap.get(stub).type == AS.REPLACEMENT_AS)
				{
					monitorASes.add(stub);
				}
			}
			break;
		}
//		if(!monitorFrom)
//		{
//			for( Integer asMapKey : asTypeDef.keySet())
//			{
//					monitorASes.add(asMapKey);
//			}
//		}
//		else
//		{
//			for(Integer key : asMap.keySet())
//			{
//				monitorASes.add(key);
//			}
//		}
		
	//	System.out.println("total stubs: " + computeStubs().size());
		for(Integer key : computeStubs())
		{
			announcedASes.add(key);
		}
		
		//go through and have all wiser nodes announce themselves, only announce some constant at a time, let the sim go.
		int batchSize = BATCH_PERCENT;//(int) (asTypeDef.size() * BATCH_PERCENT);
		int counter= 0;
		int globalCounter = 0;
		for(int i = 0; i < announcedASes.size(); i++)
		{
			counter++; 
			globalCounter++;
			int announcedAS = announcedASes.get(i);
			asMap.get(announcedAS).announceSelf(); //announce an AS off our announced list			
//			if(counter == batchSize)
//			{	
				counter = 0;			    
				System.out.printf("\r%d", globalCounter);
				//			    System.out.println("iteration START");
				instrumented = false;
				boolean completed = run();
				for(int key : asMap.keySet())
				{
					asMap.get(key).clearBookKeeping();
				}
				if(!completed)
				{
					System.out.println("removing rib and fib entries for : " + announcedAS);
					IA test = null;
					for(int key : asMap.keySet())
					{						
						asMap.get(key).ribIn.remove(announcedAS);
						asMap.get(key).bestPath.remove(announcedAS);
						test = asMap.get(key).bestPath.get(announcedAS);
					}
					
				}
				HashMap<Integer, AS> clonedASMap = (HashMap<Integer, AS>) asMap.clone();
				asMap.clear();
				try {
					readTopology(topoFile, false );
					preProcessReplacement();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				for(int as : asMap.keySet())
				{
					asMap.get(as).bestPath = (HashMap<Integer, IA>) clonedASMap.get(as).bestPath.clone();
					asMap.get(as).ribIn = (HashMap<Integer, HashMap<Integer, IA>>) clonedASMap.get(as).ribIn.clone();
				}
				
				//System.out.println("iteration complete");
		//	}
		}
		
		boolean cleared = true;
		do {
			cleared = true;
			for(int announcedAS : announcedASes)
			{
				for(int monitorAS: monitorASes)
				{
					AS sanityAS = asMap.get(monitorAS);
					if(sanityAS.bestPath.get(announcedAS) != null){
						try{
							sanityAS.bestPath.get(announcedAS).getPath().size();
						}
						catch (Exception e)
						{	
							sanityAS.bestPath.remove(announcedAS);
							sanityAS.ribIn.remove(announcedAS);
							System.out.println("rerunning for: " + monitorAS + " " + announcedAS);
							asMap.get(announcedAS).announceSelf();
							run();
							cleared = false;
						}
					}
				}
			}
		} while(!cleared);
		
		System.out.println("HERE");
		do {
			cleared = true;
			for(int announcedAS : announcedASes)
			{
				for(int monitorAS: monitorASes)
				{
					AS sanityAS = asMap.get(monitorAS);
					if(sanityAS.bestPath.get(announcedAS) != null){
						try{
							sanityAS.bestPath.get(announcedAS).getPath().size();
						}
						catch (Exception e)
						{	
							sanityAS.bestPath.remove(announcedAS);
							sanityAS.ribIn.remove(announcedAS);
							System.out.println("rerunning for: " + monitorAS + " " + announcedAS);
							asMap.get(announcedAS).announceSelf();
							run();
							cleared = false;
						}
					}
				}
			}
		} while(!cleared);

		//		System.out.println("Number of announced ASes: " + announcedASes.size());
		//run for any missing ASes
		instrumented = false;
//		run();
		//		monitorASes.clear(); //REMOVE LATER, ADDING ALL MONITOR ASES
//		for(Integer key : asMap.keySet())
//		{
//			asMap.get(key).clearBookKeeping();
//			//		monitorASes.add(key); //ADDING ALL MONITOR ASES, REMOVE LATER
//		}		

	}
	
	private static final int BATCH_PERCENT = 1; //PERCENT OF ASES TO ANNOUNCE AT A TIME needs to always be 1 now
	private static final int RIB_METRIC = 0;
	private static final int FIB_METRIC = 1;
	/**
		 * runs a basic IA simulation
		 * benefit measured at transit only
		 */
	public static void iaBasicSimulationTransitsOnlyTrueCost(int monitorFrom, boolean bwTest, float forX, int metric){
			
			
			//ases that will be used for observation
			ArrayList<Integer> monitorASes = new ArrayList<Integer>();
		//	tier1ASes = computeTier1();
	
	
			// We first announce all the tier-1 ASes and save 
			// the paths from each of our failure-provider to the tier1
			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			ArrayList<Integer> announcedASes = new ArrayList<Integer>();
			
			runSimulation(monitorASes, announcedASes, monitorFrom);
			
			int costSum = 0;
			float bwSum = 0;
			int totalRIBSize = 0;
			int total = monitorASes.size();
			int totalBestPathNodes = 0;
			int bestpathTruecost = 0;
			int bestpathBWSum = 0;
			ArrayList<Integer> removedASes = new ArrayList<Integer>();
			//for transit ASes only, see the sum of received paths
			for(int as : monitorASes)
			{
				//for each monitored AS, compare their lowest outgoing wiser cost with what was received
				AS monitoredAS = asMap.get(as); //the AS we are measuring from (all transits eventually)
				for(int announcedAS : announcedASes)
				{
					//make sure that the we aren't comparing the AS who announced this to itself
					if(as == announcedAS){
						continue;
					}
					AS compareAS = asMap.get(announcedAS); //the AS that announced
					//sanity check, if any monitor as to this as causes an exception, do not add to costs
					boolean skip = false;
					for(int sanity : monitorASes)
					{
						AS sanityAS = asMap.get(sanity);
						if(sanityAS.bestPath.get(announcedAS) != null){
							try{
								monitoredAS.bestPath.get(announcedAS).getPath().size();
							}
							catch (Exception e)
							{		
								if (!removedASes.contains(announcedAS))
								{
									System.out.println("removed: " + announcedAS);
									removedASes.add(announcedAS);
								}
								skip = true;
							}
						}
					}
					if(skip)
					{
						continue;
					}
					//what is the lowest cost outgoing link of announced Node
	//				int lowestCost = Integer.MAX_VALUE;
		//			for(Integer neighbor: compareAS.neighborLatency.keySet())
		//			{
		//				if(compareAS.neighborLatency.get(neighbor) < lowestCost)
		//				{
		//					lowestCost = compareAS.neighborLatency.get(neighbor);
		//				}
		//			}
					if(monitoredAS.bestPath.get(announcedAS) != null)
					{
						try{
							IA bestToAnnounced = monitoredAS.bestPath.get(announcedAS);
							totalBestPathNodes+= monitoredAS.bestPath.get(announcedAS).getPath().size();
							bestpathTruecost += monitoredAS.bestPath.get(announcedAS).getTrueCost();	//
							bestpathBWSum += monitoredAS.bestPath.get(announcedAS).bookKeepingInfo.get(IA.BNBW_KEY);
						}
						catch(Exception e)
						{
							System.out.println("exception for <monitor, anounced>: " + monitoredAS.asn + " " + announcedAS);
							System.exit(1);
						}
					}	// 
						// 
					//System.out.println("[DEBUG] lowest cost: " + lowestCost);
					// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
					//disconnection, don't include it in our percentage.
					if (monitoredAS.ribIn.get(announcedAS) != null) {	// 
						totalRIBSize += monitoredAS.ribIn.get(announcedAS).size();	// 
						for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {	// 
							
							costSum += path.getTrueCost();
							bwSum += path.bookKeepingInfo.get(IA.BNBW_KEY);
							
							//debug if statement
							if(monitoredAS.neighborMap.containsKey(compareAS.asn))
							{							
							//	System.out.println("[DEBUG] AS " + monitoredAS.asn + " neighbor of: " + compareAS.asn);
								//System.out.println("[DEBUG] received lowest cost: " + wiserProps);
								//System.out.println("[DEBUG] rib of AS is : " + monitoredAS.ribIn.toString());
							}
	
						}// endfor
						
					}
//					else
//					{
//						total--;
//					}
				}
			}
			if(!bwTest){
				System.out.println("WISER RIB GRAPH " + forX + " " + String.valueOf(((float) costSum)/total) + " ENDGRAPH ENDRIB ENDWISER");
				System.out.println("WISER FIB GRAPH " + forX + " " + String.valueOf(((float) bestpathTruecost)/total) + " ENDGRAPH ENDFIB ENDWISER");
			}
			else
			{
				System.out.println("BW RIB GRAPH " + forX + " " + String.valueOf(((float) bwSum)/total) + " ENDGRAPH ENDRIB ENDBW");
				System.out.println("BW FIB GRAPH " + forX + " " + String.valueOf(((float) bestpathBWSum)/total) + " ENDGRAPH ENDFIB ENDBW");
			}
					//	System.out.println("GRAPH " + forX + " " + String.valueOf((float) costSum/total) + " ENDGRAPH");
			System.out.println("totalRIbsize: " + totalRIBSize);
			System.out.println("totla bestpath nodes: " + totalBestPathNodes);
			System.out.println("totla bestpath tcost: " + bestpathTruecost );
		}

	/**
		 * runs a basic IA simulation
		 * benefit measured at transit only
		 */
	public static void iaBasicSimulationReplacementNumPaths(int monitorFrom, boolean bwTest, float forX, int metric){
			
			
			//ases that will be used for observation
			ArrayList<Integer> monitorASes = new ArrayList<Integer>();
		//	tier1ASes = computeTier1();
	
	
			// We first announce all the tier-1 ASes and save 
			// the paths from each of our failure-provider to the tier1
			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			ArrayList<Integer> announcedASes = new ArrayList<Integer>();
			
			runSimulation(monitorASes, announcedASes, monitorFrom);
			
			int totalRIBPaths = 0;
			float bwSum = 0;
			int totalRIBSize = 0;
			int total = monitorASes.size();
			long totalBestPaths = 0;
			int bestpathTruecost = 0;
			int bestpathBWSum = 0;
			//for transit ASes only, see the sum of received paths
			for(int as : monitorASes)
			{
				//for each monitored AS, compare their lowest outgoing wiser cost with what was received
				AS monitoredAS = asMap.get(as); //the AS we are measuring from (all transits eventually)
				for(int announcedAS : announcedASes)
				{
					//make sure that the we aren't comparing the AS who announced this to itself
					if(as == announcedAS){
						continue;
					}
					AS compareAS = asMap.get(announcedAS); //the AS that announced
					if(monitoredAS.bestPath.get(announcedAS) != null)
					{
						IA bestPath = monitoredAS.bestPath.get(announcedAS);
						String[] replacementProps = AS.getProtoProps(bestPath, bestPath.popCosts.keySet().iterator().next(), new Protocol(AS.REPLACEMENT_AS));
						if(replacementProps == null)
						{
							totalBestPaths += 1;
						}
						else
						{
							totalBestPaths += Long.valueOf(replacementProps[0]);
						}
						bestpathTruecost += monitoredAS.bestPath.get(announcedAS).getTrueCost();	//
						bestpathBWSum += monitoredAS.bestPath.get(announcedAS).bookKeepingInfo.get(IA.BNBW_KEY);
					}	// 
						// 
					//System.out.println("[DEBUG] lowest cost: " + lowestCost);
					// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
					//disconnection, don't include it in our percentage.
					if (monitoredAS.ribIn.get(announcedAS) != null) {	// 
						totalRIBSize += monitoredAS.ribIn.get(announcedAS).size();	// 
						for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {	// 
							String[] replacementProps = AS.getProtoProps(path, path.popCosts.keySet().iterator().next(), new Protocol(AS.REPLACEMENT_AS));
							if(replacementProps == null)
							{
								totalRIBPaths += 1;
							}
							else
							{
								totalRIBPaths += Long.valueOf(replacementProps[0]);
							}
							bwSum += path.bookKeepingInfo.get(IA.BNBW_KEY);
							
							//debug if statement
							if(monitoredAS.neighborMap.containsKey(compareAS.asn))
							{							
							//	System.out.println("[DEBUG] AS " + monitoredAS.asn + " neighbor of: " + compareAS.asn);
								//System.out.println("[DEBUG] received lowest cost: " + wiserProps);
								//System.out.println("[DEBUG] rib of AS is : " + monitoredAS.ribIn.toString());
							}
							
	
						}// endfor
						
					}
//					else
//					{
//						total--;
//					}
				}
			//	System.out.println("totalbestpath: " + totalBestPaths);
		//		totalBestPaths = 0;
			}
			System.out.println("REPLACEMENT RIB GRAPH " + forX + " " + String.valueOf((float) totalRIBPaths/total) + " ENDGRAPH ENDRIB ENDREPLACEMENT");
			System.out.println("REPLACEMENT FIB GRAPH " + forX + " " + String.valueOf((float) totalBestPaths/total) + " ENDGRAPH ENDFIB ENDREPLACEMENT");
		//	System.out.println("GRAPH " + forX + " " + String.valueOf((float) costSum/total) + " ENDGRAPH");
//			System.out.println("totalRIbsize: " + totalRIBSize);
			//System.out.println("totla bestpath nodes: " + totalBestPaths);
//			System.out.println("totla bestpath tcost: " + bestpathTruecost );
		}
	
	
		public static int getIncomingCosts(int as)
		{
			int incomingCost = 0;
			AS monitorAS = asMap.get(as);
			for(int asMapKey : asMap.keySet())
			{
				if(as == asMapKey)
				{
					continue;
				}
				for(IA bestpath : asMap.get(asMapKey).bestPath.values())
				{
					LinkedList<Integer> path = bestpath.getPath();
					int prevNode = -1;
					for(int node : path)
					{
						if(node == as)
						{
							if(prevNode != -1)
							{
								PoPTuple monitorToNode = new PoPTuple(as, prevNode );
								incomingCost += monitorAS.neighborLatency.get(prevNode).get(monitorToNode).get(AS.COST_METRIC);
							}
						}
						prevNode = node;
					}
				}
			}
			return incomingCost;
		}
	
		public static void iaBasicSimulationRichWorld(int monitorFrom, boolean bwTest, float forX, int metric, int primaryType){
			
			
			//ases that will be used for observation
			ArrayList<Integer> monitorASes = new ArrayList<Integer>();
		//	tier1ASes = computeTier1();
	
	
			// We first announce all the tier-1 ASes and save 
			// the paths from each of our failure-provider to the tier1
			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			ArrayList<Integer> announcedASes = new ArrayList<Integer>();
			
//			runSimulation(monitorASes, announcedASes, monitorFrom);
			runSimulation(monitorASes, announcedASes, ALL);
			
			int incomingCost = 0;
			float receivedFIBBW = 0;
			float receivedFIBTrueBW = 0;
			float receivedFIBWiserCost = 0;
			float receivedFIBTrueCost = 0;
			int partRibCostSum = 0;
			float partRibBwSum = 0;
			float totalFibCostSum = 0;
			float totalFibBwSum = 0;
			float totalRibCostSum = 0;
			float totalRibBwSum = 0;
			float totalRIBSize = 0;
			float total = monitorASes.size();
			float wiserTotal = 0;
			float bwTotal = 0;
			float replacementTotal = 0;
			float replacementStubTotal = 0;
			float totalBestPaths = 0;
			float totalStubBestPaths = 0;
			float totalRIBPaths = 0;
			float totalStubRIBPaths = 0;
			float totalBestPathNodes = 0;
			float bestpathTruecost = 0;
			float bestpathBWSum = 0;
			ArrayList<Integer> removedASes = new ArrayList<Integer>();
			//for transit ASes only, see the sum of received paths
			for(int as : monitorASes)
			{
				//for each monitored AS, compare their lowest outgoing wiser cost with what was received
				AS monitoredAS = asMap.get(as); //the AS we are measuring from (all transits eventually)
				boolean isStub = (monitoredAS.customers.size() == 0);
				switch(monitoredAS.type)
				{
				case AS.WISER:
					wiserTotal++;
					incomingCost += getIncomingCosts(as);
					break;
				case AS.BANDWIDTH_AS:
					bwTotal++;
					break;
				case AS.REPLACEMENT_AS:
					replacementTotal++;
					if(isStub)
					{
						replacementStubTotal++;
					}
					break;
				}
				for(int announcedAS : announcedASes)
				{
					//make sure that the we aren't comparing the AS who announced this to itself
					if(as == announcedAS){
						continue;
					}
					AS compareAS = asMap.get(announcedAS); //the AS that announced
					//sanity check, if any monitor as to this as causes an exception, do not add to costs
					boolean skip = false;
					for(int sanity : monitorASes)
					{
						AS sanityAS = asMap.get(sanity);
						if(sanityAS.bestPath.get(announcedAS) != null){
							try{
								monitoredAS.bestPath.get(announcedAS).getPath().size();
							}
							catch (Exception e)
							{		
								if (!removedASes.contains(announcedAS))
								{
									System.out.println("removed: " + announcedAS);
									removedASes.add(announcedAS);
								}
								skip = true;
							}
						}
					}
					if(skip)
					{
						continue;
					}
					//what is the lowest cost outgoing link of announced Node
	//				int lowestCost = Integer.MAX_VALUE;
		//			for(Integer neighbor: compareAS.neighborLatency.keySet())
		//			{
		//				if(compareAS.neighborLatency.get(neighbor) < lowestCost)
		//				{
		//					lowestCost = compareAS.neighborLatency.get(neighbor);
		//				}
		//			}
					if(monitoredAS.bestPath.get(announcedAS) != null)
					{
						IA bestPath = monitoredAS.bestPath.get(announcedAS);
//						try{



						if(monitoredAS.type == AS.REPLACEMENT_AS){								
							String[] replacementProps = AS.getProtoProps(bestPath, bestPath.popCosts.keySet().iterator().next(), new Protocol(AS.REPLACEMENT_AS));
							if(replacementProps == null)
							{
								totalBestPaths += 1;
								if(isStub)
								{
									totalStubBestPaths += 1;
								}
							}
							else
							{
								totalBestPaths += Long.valueOf(replacementProps[0]);
								if(isStub)
								{
									totalStubBestPaths += Long.valueOf(replacementProps[0]);
								}
							}
						}
						totalBestPathNodes+= monitoredAS.bestPath.get(announcedAS).getPath().size();
						if(monitoredAS.type == AS.WISER){
							bestpathTruecost += monitoredAS.bestPath.get(announcedAS).getTrueCost();
							String wiserProps[] = AS.getWiserProps(bestPath, bestPath.popCosts.keySet().iterator().next());
							if(wiserProps != null)
							{
								float wiserVal = Float.valueOf(wiserProps[0]);
								float normalization = Float.valueOf(wiserProps[1]);
								//	System.out.println("normalization: " + normalization);
								receivedFIBWiserCost += ((float) wiserVal) / normalization;
								receivedFIBTrueCost += ((float)bestPath.getTrueCost()) / normalization;
							}
						}//
						if(monitoredAS.type == AS.BANDWIDTH_AS){
							bestpathBWSum += monitoredAS.bestPath.get(announcedAS).bookKeepingInfo.get(IA.BNBW_KEY);
							String bwProps[] = AS.getBandwidthProps(bestPath, bestPath.popCosts.keySet().iterator().next());
							if(bwProps != null){
								float bw = Float.valueOf(bwProps[0]);
								receivedFIBBW += bw;
								receivedFIBTrueBW +=  monitoredAS.bestPath.get(announcedAS).bookKeepingInfo.get(IA.BNBW_KEY);
							}

						}
						totalFibCostSum += monitoredAS.bestPath.get(announcedAS).getTrueCost();
						totalFibBwSum += monitoredAS.bestPath.get(announcedAS).bookKeepingInfo.get(IA.BNBW_KEY);

//						}
//						catch(Exception e)
//						{
//							System.out.println("exception for <monitor, anounced>: " + monitoredAS.asn + " " + announcedAS);
//							System.exit(1);
//						}
					}	// 
						// 
					//System.out.println("[DEBUG] lowest cost: " + lowestCost);
					// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
					//disconnection, don't include it in our percentage.
					if (monitoredAS.ribIn.get(announcedAS) != null) {	// 
						totalRIBSize += monitoredAS.ribIn.get(announcedAS).size();	// 
						for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {	// 
							totalRibCostSum += path.getTrueCost();
							totalRibBwSum += path.bookKeepingInfo.get(IA.BNBW_KEY);
							if(monitoredAS.type == AS.REPLACEMENT_AS){
								String[] replacementProps = AS.getProtoProps(path, path.popCosts.keySet().iterator().next(), new Protocol(AS.REPLACEMENT_AS));
								if(replacementProps == null)
								{
									totalRIBPaths += 1;
									if(isStub)
									{
										totalStubRIBPaths += 1;
									}
								}
								else
								{
									totalRIBPaths += Long.valueOf(replacementProps[0]);
									if(isStub)
									{
										totalStubRIBPaths += Long.valueOf(replacementProps[0]);
									}
								}
							}
							if(monitoredAS.type == AS.WISER){
								partRibCostSum += path.getTrueCost();
							}
							if(monitoredAS.type == AS.BANDWIDTH_AS){
								partRibBwSum += path.bookKeepingInfo.get(IA.BNBW_KEY);
							}
							
							//debug if statement
							if(monitoredAS.neighborMap.containsKey(compareAS.asn))
							{							
							//	System.out.println("[DEBUG] AS " + monitoredAS.asn + " neighbor of: " + compareAS.asn);
								//System.out.println("[DEBUG] received lowest cost: " + wiserProps);
								//System.out.println("[DEBUG] rib of AS is : " + monitoredAS.ribIn.toString());
							}
	
						}// endfor
						
					}
//					else
//					{
//						total--;
//					}
				}
			}
	//		if(!bwTest){
			if(forX != 0.0){
				System.out.println("WISER_RIB_GRAPH " + forX + " " + String.valueOf(((float) partRibCostSum)/wiserTotal) + " END");
				System.out.println("WISER_FIB_GRAPH " + forX + " " + String.valueOf(((float) bestpathTruecost)/wiserTotal) + " END");
				//		}
				//		else
				//		{
				System.out.println("BW_RIB_GRAPH " + forX + " " + String.valueOf(((float) partRibBwSum)/bwTotal) + " END");
				System.out.println("BW_FIB_GRAPH " + forX + " " + String.valueOf(((float) bestpathBWSum)/bwTotal) + " END");
			//		}
			}
			else{
				System.out.println("WISER_RIB_GRAPH " + forX + " " + String.valueOf(((float) totalRibCostSum)/total) + " END" );
				System.out.println("WISER_FIB_GRAPH " + forX + " " + String.valueOf(((float) totalFibCostSum)/total) + " END" );
				System.out.println("BW_RIB_GRAPH " + forX + " " + String.valueOf(((float) totalRibBwSum)/total) + " END" );
				System.out.println("BW_FIB_GRAPH " + forX + " " + String.valueOf(((float) totalFibBwSum)/total) + " END" );

			}
			
			System.out.println("REPLACEMENT_RIB_GRAPH " + forX + " " + String.valueOf(((float) totalRIBPaths)/replacementTotal) + " END");
			System.out.println("REPLACEMENT_FIB_GRAPH " + forX + " " + String.valueOf(((float) totalBestPaths)/replacementTotal) + " END");
			System.out.println("REPLACEMENT_STUB_RIB_GRAPH " + forX + " " + String.valueOf(((float) totalStubRIBPaths)/replacementStubTotal) + " END");
			System.out.println("REPLACEMENT_STUB_FIB_GRAPH " + forX + " " + String.valueOf(((float) totalStubBestPaths)/replacementStubTotal) + " END");
			
			System.out.println("ALLWISER_RIB_GRAPH " + forX + " " + String.valueOf(((float) totalRibCostSum)/total) + " END" );
			System.out.println("ALLWISER_FIB_GRAPH " + forX + " " + String.valueOf(((float) totalFibCostSum)/total) + " END" );
			System.out.println("ALLBW_RIB_GRAPH " + forX + " " + String.valueOf(((float) totalRibBwSum)/total) + " END" );
			System.out.println("ALLBW_FIB_GRAPH " + forX + " " + String.valueOf(((float) totalFibBwSum)/total) + " END" );
			
			System.out.println("GULF_BWREPLACEMENT_TRUECOST_RIB " + forX + " " + String.valueOf(((float) (totalRibCostSum - partRibCostSum)) / (total - wiserTotal)  ) + " END");
			System.out.println("GULF_BWREPLACEMENT_TRUECOST_FIB " + forX + " " + String.valueOf(((float) (totalFibCostSum - bestpathTruecost)) / (total - wiserTotal)  ) + " END");
			
			System.out.println("GULF_WISERREPLACEMENT_TRUECOST_RIB " + forX + " " + String.valueOf(((float) (totalRibBwSum - partRibBwSum)) / (total - bwTotal)  ) + " END");
			System.out.println("GULF_WISERREPLACEMENT_TRUECOST_FIB " + forX + " " + String.valueOf(((float) (totalFibBwSum - bestpathBWSum)) / (total - bwTotal)  ) + " END");
			
			System.out.println("incomingCosts " + forX + " " + String.valueOf(((float) incomingCost)/wiserTotal) + " END");
			System.out.println("WISER_FIB_COST_IN_ADVERT " + forX + " " + String.valueOf(((float) receivedFIBWiserCost) / wiserTotal) );
			System.out.println("bestpath_receivedcost:truecost_ratio: " + forX + " " + String.valueOf(((float) receivedFIBWiserCost) / receivedFIBTrueCost) + " END");
			System.out.println("bwratio_received:true " + forX + " " + String.valueOf(((float) receivedFIBBW) / receivedFIBTrueBW) + " END");
		}
		
	
	/**
	 * runs a basic IA simulation
	 * benefit measured at transit only
	 */
	public static void iaBasicSimulationTransitsOnlyWiserCost(int monitorFrom){
		
		
		//ases that will be used for observation
		ArrayList<Integer> monitorASes = new ArrayList<Integer>();
	//	tier1ASes = computeTier1();


		// We first announce all the tier-1 ASes and save 
		// the paths from each of our failure-provider to the tier1
		simTime = 0;
		upstreamASes.clear();
		r = new Random(seedVal);
		ArrayList<Integer> announcedASes = new ArrayList<Integer>();
		
		//Find AS to use as monitor
		//monitorASes.add((Integer) asMap.keySet().toArray()[r.nextInt(asMap.size())]); //doesn't check for overlap with special ASes, fix later
		

		runSimulation(monitorASes, announcedASes, monitorFrom);
		
		
		float costSum = 0;
		int total = monitorASes.size();
		//for transit ASes only, see the sum of received paths
		for(int as : monitorASes)
		{
			//for each monitored AS, compare their lowest outgoing wiser cost with what was received
			AS monitoredAS = asMap.get(as); //the AS we are measuring from (all transits eventually)
			for(int announcedAS : announcedASes)
			{
				//make sure that the we aren't comparing the AS who announced this to itself
				if(as == announcedAS){
					continue;
				}
				AS compareAS = asMap.get(announcedAS); //the AS that announced
				//what is the lowest cost outgoing link of announced Node
				int lowestCost = Integer.MAX_VALUE;
	//			for(Integer neighbor: compareAS.neighborLatency.keySet())
	//			{
	//				if(compareAS.neighborLatency.get(neighbor) < lowestCost)
	//				{
	//					lowestCost = compareAS.neighborLatency.get(neighbor);
	//				}
	//			}
				//System.out.println("[DEBUG] lowest cost: " + lowestCost);
				// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
				//disconnection, don't include it in our percentage.
				if (monitoredAS.ribIn.get(announcedAS) != null) {
					for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {
						// all paths should have wiser information in them
						PoPTuple tupleChosen = null;
						tupleChosen = monitoredAS.tupleChosen(path);
						String[] wiserProps = AS.getWiserProps(path, tupleChosen.reverse());
						
//						byte[] wiserBytes = path.getProtocolPathAttribute(
	//							new Protocol(AS.WISER), path.getPath());						
						int wiserCost = 0;
						int normalization = 1;
						// if ther is wiser props
						if (wiserProps != null) {
							// fill them into our variables
							//								wiserProps = new String(wiserBytes, "UTF-8");
							//						String[] split = wiserProps.split("\\s+");
							wiserCost = Integer.valueOf(wiserProps[0]);
							normalization = Integer.valueOf(wiserProps[1]);
							costSum += ((float)wiserCost)/(float)normalization;
						} else {
							if(!monitoredAS.neighborMap.containsKey(announcedAS))
								debug("[DEBUG] NO WISER PROPS FOR: "
									+ announcedAS);
//								System.out.println();
						}
						
//						costSum += (float)wiserCost/(float)normalization;
						
						//debug if statement
						if(monitoredAS.neighborMap.containsKey(compareAS.asn))
						{							
						//	System.out.println("[DEBUG] AS " + monitoredAS.asn + " neighbor of: " + compareAS.asn);
							//System.out.println("[DEBUG] received lowest cost: " + wiserProps);
							//System.out.println("[DEBUG] rib of AS is : " + monitoredAS.ribIn.toString());
						}
						
//						System.out.println("[DEBUG] received lowest cost: " + wiserCost);
						//this is used for percent lowest cost
					//	if (wiserCost == lowestCost) {
							
				//			costSum++;
				//			break;
				//		}

					}// endfor
					
				}
				else
				{
					total--;
				}
			}
		}
		
		System.out.println("Average cost sum for transit ASes: " + String.valueOf((float) costSum/total));
	}
	
	/**
	 * runs a basic IA simulation
	 * benefit measured at transit only
	 */
	public static void iaBasicSimulationTransitsOnlySBGP(int monitorFrom){
		
		
		//ases that will be used for observation
		ArrayList<Integer> monitorASes = new ArrayList<Integer>();
	//	tier1ASes = computeTier1();


		// We first announce all the tier-1 ASes and save 
		// the paths from each of our failure-provider to the tier1
		simTime = 0;
		upstreamASes.clear();
		r = new Random(seedVal);
		ArrayList<Integer> announcedASes = new ArrayList<Integer>();
		
		//Find AS to use as monitor
		//monitorASes.add((Integer) asMap.keySet().toArray()[r.nextInt(asMap.size())]); //doesn't check for overlap with special ASes, fix later
		

		runSimulation(monitorASes, announcedASes, monitorFrom);
		
		float percentSecure = 0; //transit ases add the percent of paths that are secure to announcing ASes
		int total = monitorASes.size(); 
		//for transit ASes only, see the sum of received paths
		for(int as : monitorASes)
		{
			//for each monitored AS, compare their lowest outgoing wiser cost with what was received
			AS monitoredAS = asMap.get(as); //the AS we are measuring from (all transits eventually)
			int numSecure = 0;
			int totalReceivedAnnounce = 0; //used to compute average of secure paths received. numSecure/total...
			for(int announcedAS : announcedASes)
			{
				//make sure that the we aren't comparing the AS who announced this to itself
				if(as == announcedAS){
					continue;
				}				
				AS compareAS = asMap.get(announcedAS); //the AS that announced
				//what is the lowest cost outgoing link of announced Node
				int lowestCost = Integer.MAX_VALUE;
	//			for(Integer neighbor: compareAS.neighborLatency.keySet())
	//			{
	//				if(compareAS.neighborLatency.get(neighbor) < lowestCost)
	//				{
	//					lowestCost = compareAS.neighborLatency.get(neighbor);
	//				}
	//			}
				//System.out.println("[DEBUG] lowest cost: " + lowestCost);
				// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
				//disconnection, don't include it in our percentage.
				
				if (monitoredAS.bestPath.get(announcedAS) != null) {
					totalReceivedAnnounce++;
					IA bestPath = monitoredAS.bestPath.get(announcedAS);
					if(bestPath.secure)
					{
						numSecure++;
					}
				}
			}
			//we've checked all our best paths
			percentSecure += (float)numSecure/(float)totalReceivedAnnounce;
			
		}
		
		System.out.println("Average percentage of secure paths received for transit ASes: " + String.valueOf((float) percentSecure/total));
	}
	/**
	 * runs a basic IA simulation
	 */
//	public static void iaBasicSimulation(){
//		
//		// the set of ASes whose paths are affected by this failure
//		HashSet<Integer> relevantASes = new HashSet<Integer>();
//		HashSet<Integer> validASes = new HashSet<Integer>();
//		
//		//ases that will be used for observation
//		ArrayList<Integer> monitorASes = new ArrayList<Integer>();
//	//	tier1ASes = computeTier1();
//
//	//	 Obtaining tier-1 paths 
//
//		// We first announce all the tier-1 ASes and save 
//		// the paths from each of our failure-provider to the tier1
//		simTime = 0;
//		upstreamASes.clear();
//		r = new Random(seedVal);
//		ArrayList<Integer> announcedASes = new ArrayList<Integer>();
//		
//		//Find AS to use as monitor
//		//monitorASes.add((Integer) asMap.keySet().toArray()[r.nextInt(asMap.size())]); //doesn't check for overlap with special ASes, fix later
//		
//		//go through and have all wiser nodes announce themselves
//		for( Integer asMapKey : asTypeDef.keySet())
//		{
//			
//			if(asTypeDef.get(asMapKey) == AS.WISER){
//				asMap.get(asMapKey).announceSelf();
//				announcedASes.add(asMapKey);
////				System.out.println("[debug] num neighbors of wiser AS: " + asMap.get(asMapKey).neighborMap.size());
//			}
//			
////			int rVal = r.nextInt() % 1600;
//	//		if(rVal == 0){
//	//			asMap.get(asMapKey).announceSelf();
//	//			announcedASes.add(asMapKey);
//
////			}
//		}
////		System.out.println("Number of announced ASes: " + announcedASes.size());
//		instrumented = false;
//		run();
//		
//		int costSum = 0;
//		int total = asMap.size();
//		//for all ASes, see how many got the lowest path cost path to the announced ASes.
//		for(Integer as : asMap.keySet())
//		{
//			//for each announced AS, compare their lowest outgoing wiser cost with what was received
//			AS monitoredAS = asMap.get(as); //the AS we are measuring from, should eventually be all but announced
//			for(Integer announcedAS : announcedASes)
//			{
//				//make sure that the we aren't comparing the AS who announced this to itself
//				if(as == announcedAS){
//					continue;
//				}
//				AS compareAS = asMap.get(announcedAS); //the AS that announced
//				//what is the lowest cost outgoing link of announced Node
//				int lowestCost = Integer.MAX_VALUE;
//	//			for(Integer neighbor: compareAS.neighborLatency.keySet())
//	//			{
//	//				if(compareAS.neighborLatency.get(neighbor) < lowestCost)
//	//				{
//	//					lowestCost = compareAS.neighborLatency.get(neighbor);
//	//				}
//	//			}
//				//System.out.println("[DEBUG] lowest cost: " + lowestCost);
//				// see if monitored AS has that path in the RIB_in, //if it doesn't have a path, that means policy
//				//disconnection, don't include it in our percentage.
//				if (monitoredAS.ribIn.get(announcedAS) != null) {
//					for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {
//						// all paths should have wiser information in them
//						byte[] wiserBytes = path.getProtocolPathAttribute(
//								new Protocol(AS.WISER), path.getPath());
//						String wiserProps = null;
//						int wiserCost = 0;
//						int normalization = 1;
//						// if ther is wiser props
//						if (wiserBytes[0] != (byte) 0xFF) {
//							try {
//								// fill them into our variables
//								wiserProps = new String(wiserBytes, "UTF-8");
//								String[] split = wiserProps.split("\\s+");
//								wiserCost = Integer.valueOf(split[0]);
//								normalization = Integer.valueOf(split[1]);
//							} catch (UnsupportedEncodingException e) {
//								// TODO Auto-generated catch block
//								e.printStackTrace();
//							}
//						} else {
//							if(!monitoredAS.neighborMap.containsKey(announcedAS))
//								System.out.println("[DEBUG] NO WISER PROPS FOR: " + monitoredAS.asn + " "
//									+ announcedAS);
//						}
//						
//						costSum += path.getTrueCost();
//						
//						//debug if statement
//						if(monitoredAS.neighborMap.containsKey(compareAS.asn))
//						{							
//						//	System.out.println("[DEBUG] AS " + monitoredAS.asn + " neighbor of: " + compareAS.asn);
//							//System.out.println("[DEBUG] received lowest cost: " + wiserProps);
//							//System.out.println("[DEBUG] rib of AS is : " + monitoredAS.ribIn.toString());
//						}
//						
////						System.out.println("[DEBUG] received lowest cost: " + wiserCost);
//						//this is used for percent lowest cost
//			//			if (wiserCost == lowestCost) {
//							
//		//					costSum++;
//		//					break;
//			//			}
//
//					}// endfor
//					
//				}
//				else
//				{
//					total--;
//				}
//			}
//		}
//		
//		System.out.println("Average cost sum for all ASes: " + String.valueOf((float) costSum/total));
//		//show forarding tables of monitoring ases
//	//	for(Integer as: monitorASes){
//		//	System.out.println(asMap.get(as).showFwdTable());
//	//	}
//		
//		//show forwarding tables of announced ases
////		for(Integer as : announcedASes)
//	//	{
//	///		System.out.println("num upstream ases: " + upstreamASes.get(as).size());
//	//		System.out.println(asMap.get(as).showFwdTable());
//	//	}
//		
//	//	System.out.println(disconnectedASes.size());
//	//	for(Integer asMapKey : asMap.keySet())
//	//	{
//		//	AS as = asMap.get(asMapKey);
////			System.out.println(as.showFwdTable());			
//	//	}
//		
//		
//	/*	for( Integer upstreamASKey : upstreamASes.keySet())
//		{
//			int numUpstreamAses = upstreamASes.get(upstreamASKey).size();
//			if (announcedASes.contains(upstreamASKey) && (numUpstreamAses) != numAses)
//			{
//				System.out.println("not fully connect graph for " + upstreamASKey);
//				System.out.println("num upstream ases: " + numUpstreamAses);
//				System.out.println("total num of ases: " + numAses);
//			}
//			
//			else if(numUpstreamAses != numAses)
//			{
//				System.out.println("not fully connect graph for " + upstreamASKey);
//				System.out.println("num upstream ases: " + numUpstreamAses);
//				System.out.println("total num of ases: " + numAses);
//				
//			}
//		}*/
//		//for(Iterator<Integer>it = tier1ASes.iterator(); it.hasNext();) {
////			int tier1 = it.next();
//			//asMap.get(tier1).announceSelf();
//		//}
//		
//	}
	
//	public static void iaSumSimulation() {
//
//		simTime = 0;
//		upstreamASes.clear();
//		r = new Random(seedVal);
//		ArrayList<Integer> announcedASes = new ArrayList<Integer>();
//
//		// Find AS to use as monitor
//		// monitorASes.add((Integer)
//		// asMap.keySet().toArray()[r.nextInt(asMap.size())]); //doesn't check
//		// for overlap with special ASes, fix later
//
//		// go through and have all wiser nodes announce themselves
//		for (Integer asMapKey : asTypeDef.keySet()) {
//
//			if (asTypeDef.get(asMapKey) == AS.WISER) {
//				asMap.get(asMapKey).announceSelf();
//				announcedASes.add(asMapKey);
//				// System.out.println("[debug] num neighbors of wiser AS: " +
//				// asMap.get(asMapKey).neighborMap.size());
//			}
//
//			/*
//			 * int rVal = r.nextInt() % 1600; if(rVal == 0){
//			 * asMap.get(asMapKey).announceSelf(); announcedASes.add(asMapKey);
//			 * 
//			 * }
//			 */
//		}
//		// System.out.println("Number of announced ASes: " +
//		// announcedASes.size());
//		instrumented = false;
//		run();
//
//		// int gotLowestCost = 0;
//		// int total = asMap.size();
//		// for all stub ASes, see how many got the lowest path cost path to the
//		// announced ASes.
//		for (Integer as : computeStubs()) {
//			// for each announced AS, compare their lowest outgoing wiser cost
//			// with what was received
//			AS monitoredAS = asMap.get(as); // the AS we are measuring from,
//											// should eventually be all but
//											// announced
//			for (Integer announcedAS : announcedASes) {
//				// make sure that the we aren't comparing the AS who announced
//				// this to itself
//				if (as == announcedAS) {
//					continue;
//				}
//				AS compareAS = asMap.get(announcedAS); // the AS that announced
//
//				// System.out.println("[DEBUG] lowest cost: " + lowestCost);
//				// see if monitored AS has that path in the RIB_in, //if it
//				// doesn't have a path, that means policy
//				// disconnection, don't include it in our percentage.
//				if (monitoredAS.ribIn.get(announcedAS) != null) {
//					int costSum = 0; // hold sum of advertised paths
//					for (IA path : monitoredAS.ribIn.get(announcedAS).values()) {
//						// all paths should have wiser information in them
//						byte[] wiserBytes = path.getProtocolPathAttribute(
//								new Protocol(AS.WISER), path.getPath());
//						String wiserProps = null;
//						int wiserCost = 0;
//						int normalization = 1;
//						// if ther is wiser props
//						if (wiserBytes[0] != (byte) 0xFF) {
//							try {
//								// fill them into our variables
//								wiserProps = new String(wiserBytes, "UTF-8");
//								String[] split = wiserProps.split("\\s+");
//								wiserCost = Integer.valueOf(split[0]);
//								normalization = Integer.valueOf(split[1]);
//							} catch (UnsupportedEncodingException e) {
//								// TODO Auto-generated catch block
//								e.printStackTrace();
//							}
//						} else {
//							System.out.println("[DEBUG] NO WISER PROPS FOR: "
//									+ announcedAS);
//						}
//
//						costSum += wiserCost / normalization; // add the wiser
//																// cost received
//
//					}// endfor
//					String resultLine = String.valueOf(monitoredAS.asn) + " "
//							+ String.valueOf(costSum) + " "
//							+ monitoredAS.bestPath.get(announcedAS).getPath().size() + "\n";
//					try {
//						outFile.write(resultLine);
//						outFile.flush();
//					} catch (IOException e) {
//						// TODO Auto-generated catch block
//						e.printStackTrace();
//					}
//
//				}
//			}
//		}
//	}
	
	public static void runBGPOverheadSimulations() {
		int numLinks = failureCustomer.size();
		for (int i = 0; i < numLinks; i++) {
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			if (customer != currentCustomer) {
				currentCustomer = customer;
				for (Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
					it.next().RESET();
				}
				simTime = 0;
				upstreamASes.clear();
				r = new Random(seedVal);
				asMap.get(customer).announceSelf();
				run();
			} else {
				// we are continuing with the same customer .. we need to
				// re-announce the
				// previously failed link
				simTime = 0;
				simulateAnnouncement(customer, customer, currentTarget,
						(long) 10);
				run();
			}
			// now we have to simulate the withdrawal!
			simTime = 0;
			simulateWithdrawal(customer, customer, provider, (long) 10);
			run();

			System.out.println("Failure: " + customer + " -> " + provider);
			System.out.println("ASes: " + asMap.size());
			System.out.println("" + numUpdateMessages + " "
					+ numWithdrawMessages);
			System.out.println();
		}

	}

	public static void runOverheadSimulations() {
		final int EPOCH_DURATION = 60000 * 2;
		final int WITHDRAW_TIME = 10000;
		transitASes = computeTransit();
		numTransitASes = transitASes.size();

		tier1ASes = computeTier1();

		getDistanceToTier1(transitASes);

		// System.out.println("Num ASes = " + numAses);
		int numLinks = failureCustomer.size();
		for (int i = 0; i < numLinks; i++) {
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			currentCustomer = customer;
			for (Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
				it.next().RESET();
			}
			simTime = 0;
			r = new Random(seedVal);
			numBGPEnqueued = 0;
			numUpdatesEnqueued = 0;
			numUpdateMessages = numWithdrawMessages = 0;
			eventQueue.clear();
			asMap.get(customer).announceSelf();
			run();
			int numValidASes = upstreamASes.get(customer).size();
			activeTriggers.clear();
			numFloods = 0;
			floodMap.clear();
			unfinishedThisEpoch.clear();
			// now we have to simulate the withdrawal!
			System.out.println("Failure: " + customer + " -> " + provider);
			System.out.println("Num Valid = " + numValidASes
					+ " Num Invalid = " + (numAses - numValidASes)
					+ " Num Transit = " + numTransitASes);
			// numFloodsDone = 2;
			simTime = 0;
			// the system is in a stable state .. take the snapshot
			startSnapshot((int) 243, (long) 10);
			simulateWithdrawal(customer, customer, provider,
					(long) WITHDRAW_TIME);
			for (int k = 1; k <= 60000 * 5 / EPOCH_DURATION; k++) {
				startSnapshot((int) 243, (long) (WITHDRAW_TIME + k
						* EPOCH_DURATION));
			}
			numUpdateMessages = numWithdrawMessages = 0;
			run();
			// System.out.println("\nStable at : " + lastSimTime);
			System.out.println("\nTransit at : " + lastTransitSimTime);
			System.out.println("" + numUpdateMessages + " "
					+ numWithdrawMessages);
			System.out.println();
		}

	}


	public static void runFCPRandomSimulations() {
		// the set of ASes whose paths are affected by this failure
		HashSet<Integer> relevantASes = new HashSet<Integer>();
		HashSet<Integer> validASes = new HashSet<Integer>();
		tier1ASes = computeTier1();

		/* Obtaining tier-1 paths */

		// We first announce all the tier-1 ASes and save 
		// the paths from each of our failure-provider to the tier1
		simTime = 0;
		upstreamASes.clear();
		r = new Random(seedVal);
		for(Iterator<Integer>it = tier1ASes.iterator(); it.hasNext();) {
			int tier1 = it.next();
			asMap.get(tier1).announceSelf();
		}
		run();
		// now all nodes know paths to the tier-1 ASes
		// we are interested in paths from the set of failure providers
		HashSet<Integer> failureProviderSet = new HashSet<Integer>(failureProvider);
		HashMap<Integer, HashMap<Integer,IA>> tier1Paths = new HashMap<Integer, HashMap<Integer,IA>>(failureProvider.size());  
		for(Iterator<Integer>provIt = failureProviderSet.iterator(); provIt.hasNext();) {
			int fp = provIt.next(); 
			HashMap<Integer, IA> temp = new HashMap<Integer, IA>(tier1ASes.size());
			for(Iterator<Integer>tierIt = tier1ASes.iterator(); tierIt.hasNext();) {
				int t1 = tierIt.next();
				// p is the path from fp to t1
				IA p = asMap.get(fp).bestPath.get(t1);
				temp.put(t1,p);
			}
			tier1Paths.put(fp, temp);
		}
//		System.err.println("Done with Tier-1 computations");
		// DONE with TIER-1 PATHS

		int numLinks = failureCustomer.size();
		for(int i=0; i<numLinks; i++) {
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			if(customer != currentCustomer) {
				currentCustomer = customer;
				for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
					it.next().RESET();
				}
//				System.out.println("Announced all tier-1");
				simTime = 0;
				upstreamASes.clear();
				r = new Random(seedVal);
				asMap.get(customer).announceSelf();
				run();
//				System.out.println("Announced customer");

			}
			// now we have to simulate the withdrawal followed by FCP!
			relevantASes.clear();
			relevantASes.addAll(upstreamASes.get(provider));

			// we are detouring from the provider to a tier1 node to the destination
			// for each tier1 node, print path length from prov->tier1 and tier1->cust
			System.out.println("\nFailure: " + customer + " -> " + provider);
			System.out.println("Relevant: " + relevantASes.size());
			System.out.print("Path Length: ");
			for(Iterator<Integer> it = tier1ASes.iterator(); it.hasNext();) {
				int t1 = it.next();
				IA p1 = tier1Paths.get(provider).get(t1);
				int p1length;
				if(p1 == null || p1.getPath() == null)
					p1length = -1;
				else
					p1length = p1.getPath().size();

				// now find the shortest path from t1 to dst
				int p2length = findShortestPath(asMap.get(t1).getAllPaths(customer), provider, customer, t1);
				System.out.print(t1 + ":" + p1length + ":" + p2length + " ");
			}
			System.out.println();

		}
	}	

	public static void runTAASSimulations() {
		// the set of ASes whose paths are affected by this failure
		HashSet<Integer> relevantASes = new HashSet<Integer>();
		tier1ASes = computeTier1();

		// XXX: Read from file
		// ArrayList<Short> sourceList = new ArrayList<Short>();
		// sourceList.add(Short.parseShort("2"));

		// HashSet<Short> failureProviderSet = new HashSet<Short>(sourceList);
		// HashMap<Short, HashMap<Short,Path>> tier1Paths = new HashMap<Short, HashMap<Short,Path>>(failureProvider.size());  
		// for(Iterator<Short>provIt = failureProviderSet.iterator(); provIt.hasNext();) {
		// 	short fp = provIt.next(); 
		// 	HashMap<Short, Path> temp = new HashMap<Short, Path>(tier1ASes.size());
		// 	for(Iterator<Short>tierIt = tier1ASes.iterator(); tierIt.hasNext();) {
		// 		short t1 = tierIt.next();
		// 		// p is the path from fp to t1
		// 		// System.out.println("Looking at: " + fp);
		// 		Path p = asMap.get(fp).bestPath.get(t1);
		// 		temp.put(t1,p);
		// 	}
		// 	tier1Paths.put(fp, temp);
		// }
//		System.err.println("Done with Tier-1 computations");
		// DONE with TIER-1 PATHS

		int numLinks = failureCustomer.size();
		int numASes;
		for(int i=0; i<numLinks; i++) {
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			currentCustomer = customer;
			for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
			    it.next().RESET();
			}

			// We first announce all the tier-1 ASes
			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			for(Iterator<Integer>it = tier1ASes.iterator(); it.hasNext();) {
			    int tier1 = it.next();
			    asMap.get(tier1).announceSelf();
			}
			instrumented = false;
			run();
			// now all nodes know paths to the tier-1 ASes
			// Now announce the failure customer

			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			asMap.get(customer).announceSelf();
			run();
			numASes = upstreamASes.get(customer).size();

			// now we have to simulate the withdrawal!
			simTime = 0;
			totalDownTime.clear();
			prevDisconTime.clear();
			disconnectedASes.clear();
			affectedASes.clear();
			loopAffectedASes.clear();
			allLoopAffectedASes.clear();
			loopMarkerAS.clear();
			loopStart = -1;
			loopResolved = -1;
			asLoopMap.clear();
			loopTimeMap.clear();
			longestLoop = 0;

			// System.out.println("TaaS NO WITHDRAWAL\n");
			simulateWithdrawal(customer,customer,provider,(long)10);
			// the instrumented version keeps track of availability/connectedness after each message
			// is processed
			instrumented = true;
			run(); //Instrumented();

			HashSet<Integer> finallyConnectedASes = upstreamASes.get(customer);

			HashSet<Integer> permDisconnectedASes = new HashSet<Integer>(asMap.keySet());
			permDisconnectedASes.removeAll(finallyConnectedASes);

			HashSet<Integer> loopOrDisconnectedASes = new HashSet<Integer>();
			loopOrDisconnectedASes.addAll(loopAffectedASes);
			loopOrDisconnectedASes.addAll(disconnectedASes);

			// now we have to simulate the withdrawal followed by FCP!
			// relevantASes.clear();
			// relevantASes.addAll(upstreamASes.get(provider));

			// we are detouring from the provider to a tier1 node to the destination
			// for each tier1 node, print path length from prov->tier1 and tier1->cust
			System.out.println("\nFailure: " + customer + " -> " + provider);
			// System.out.println("Number of Disconnected ASes = " + disconnectedASes.size() + " : " + disconnectedASes);
			System.out.println("Number of Disconnected ASes = " + disconnectedASes.size());
			disconnectedASes.removeAll(permDisconnectedASes);
			// System.out.println("Number of Temp Disconnected ASes = " + disconnectedASes.size() + ", " + disconnectedASes);
			System.out.println("Number of Temp Disconnected ASes = " + disconnectedASes.size());
			// System.out.println("Relevant: " + relevantASes.size());
			totalLoopDuration = loopResolved - loopStart;
			System.out.println("Longest Loop Duration = " + longestLoop);
			// System.out.println("Loop affected ASes (long duration loops)= " + loopAffectedASes.size() + ", " + loopAffectedASes);
			System.out.println("Loop affected ASes (long duration loops)= " + loopAffectedASes.size());
			// System.out.println("Loop Or Disconnected ASes = " + loopOrDisconnectedASes.size() + " : " + loopOrDisconnectedASes);
			System.out.println("Loop Or Disconnected ASes = " + loopOrDisconnectedASes.size());
			System.out.println("Loop affected ASes (all loops)" + allLoopAffectedASes.size());
			System.out.println("Num connected domains = " + (upstreamASes.get(customer).size()-1));
			System.out.println("Num valid ASes = " + numASes);

			System.out.println("Convergence time = " + lastSimTime);

			HashMap<Integer, HashMap<Integer,IA>> tier1Paths = new HashMap<Integer, HashMap<Integer,IA>>();
			for(Iterator<Integer>provIt = loopOrDisconnectedASes.iterator(); provIt.hasNext();) {
			// for(Iterator<Short>provIt = failureProviderSet.iterator(); provIt.hasNext();) {
			    int fp = provIt.next(); 

			    // Compute path lengths from source -> t1 and from t1 -> dest
			    System.out.print("Tier1 detour from AS " + fp + " : ");
			    for(Iterator<Integer> it = tier1ASes.iterator(); it.hasNext();) {
				int t1 = it.next();

				if(!tier1Paths.containsKey(fp) || !tier1Paths.get(fp).containsKey(t1)) {
				    // Cache path from this AS to Tier 1
				    // p is the path from fp to t1
				    IA p = asMap.get(fp).bestPath.get(t1);
				    if(!tier1Paths.containsKey(fp)) {
					tier1Paths.put(fp, new HashMap<Integer, IA>());
				    }
				    tier1Paths.get(fp).put(t1, p);
				}
				IA p1 = tier1Paths.get(fp).get(t1);
				int p1length;
				if(fp == t1)
				    p1length = 0;
				else if(p1 == null || p1.getPath() == null)
				    p1length = -1;
				else
				    p1length = p1.getPath().size();

				// now find the shortest path from t1 to dst
				int p2length = findShortestPath(asMap.get(t1).getAllPaths(customer), provider, customer, t1);

				// Was this particular Tier 1 also affected by the outage?
				String disc = disconnectedASes.contains(t1) ? "T" : "N";
				if(permDisconnectedASes.contains(t1)) {
				    disc = "P";
				}

				System.out.print(t1 + ":" + disc + ":" + p1length + ":" + p2length + " ");
			    }
			    System.out.println();
			}

		}
	}	

	public static void runTAASASSimulations() {
		// the set of ASes whose paths are affected by this failure
		HashSet<Integer> relevantASes = new HashSet<Integer>();
		tier1ASes = computeTier1();

		// XXX: Read from file
		// ArrayList<Short> sourceList = new ArrayList<Short>();
		// sourceList.add(Short.parseShort("2"));

		// HashSet<Short> failureProviderSet = new HashSet<Short>(sourceList);
		// HashMap<Short, HashMap<Short,Path>> tier1Paths = new HashMap<Short, HashMap<Short,Path>>(failureProvider.size());  
		// for(Iterator<Short>provIt = failureProviderSet.iterator(); provIt.hasNext();) {
		// 	short fp = provIt.next(); 
		// 	HashMap<Short, Path> temp = new HashMap<Short, Path>(tier1ASes.size());
		// 	for(Iterator<Short>tierIt = tier1ASes.iterator(); tierIt.hasNext();) {
		// 		short t1 = tierIt.next();
		// 		// p is the path from fp to t1
		// 		// System.out.println("Looking at: " + fp);
		// 		Path p = asMap.get(fp).bestPath.get(t1);
		// 		temp.put(t1,p);
		// 	}
		// 	tier1Paths.put(fp, temp);
		// }
//		System.err.println("Done with Tier-1 computations");
		// DONE with TIER-1 PATHS

		int numLinks = failureCustomer.size();
		int numASes;
		for(int i=0; i<numLinks; i++) {
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			currentCustomer = customer;
			for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
			    it.next().RESET();
			}

			// We first announce all the tier-1 ASes
			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			for(Iterator<Integer>it = tier1ASes.iterator(); it.hasNext();) {
			    int tier1 = it.next();
			    asMap.get(tier1).announceSelf();
			}
			instrumented = false;
			run();
			// now all nodes know paths to the tier-1 ASes
			// Now announce the failure customer

			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			asMap.get(customer).announceSelf();
			run();
			numASes = upstreamASes.get(customer).size();

			// now we have to simulate the withdrawal!
			simTime = 0;
			totalDownTime.clear();
			prevDisconTime.clear();
			disconnectedASes.clear();
			affectedASes.clear();
			loopAffectedASes.clear();
			allLoopAffectedASes.clear();
			loopMarkerAS.clear();
			loopStart = -1;
			loopResolved = -1;
			asLoopMap.clear();
			loopTimeMap.clear();
			longestLoop = 0;

			// System.out.println("TaaS NO WITHDRAWAL\n");
			simulateWithdrawal(customer,customer,provider,(long)10);
			// the instrumented version keeps track of availability/connectedness after each message
			// is processed
			instrumented = true;
			run(); //Instrumented();

			HashSet<Integer> finallyConnectedASes = upstreamASes.get(customer);

			HashSet<Integer> permDisconnectedASes = new HashSet<Integer>(asMap.keySet());
			permDisconnectedASes.removeAll(finallyConnectedASes);

			HashSet<Integer> loopOrDisconnectedASes = new HashSet<Integer>();
			loopOrDisconnectedASes.addAll(loopAffectedASes);
			loopOrDisconnectedASes.addAll(disconnectedASes);

			// now we have to simulate the withdrawal followed by FCP!
			// relevantASes.clear();
			// relevantASes.addAll(upstreamASes.get(provider));

			// we are detouring from the provider to a tier1 node to the destination
			// for each tier1 node, print path length from prov->tier1 and tier1->cust
			System.out.println("\nFailure: " + customer + " -> " + provider);
			// System.out.println("Number of Disconnected ASes = " + disconnectedASes.size() + " : " + disconnectedASes);
			System.out.println("Number of Disconnected ASes = " + disconnectedASes.size());
			disconnectedASes.removeAll(permDisconnectedASes);
			// System.out.println("Number of Temp Disconnected ASes = " + disconnectedASes.size() + ", " + disconnectedASes);
			System.out.println("Number of Temp Disconnected ASes = " + disconnectedASes.size());
			// System.out.println("Relevant: " + relevantASes.size());
			totalLoopDuration = loopResolved - loopStart;
			System.out.println("Longest Loop Duration = " + longestLoop);
			// System.out.println("Loop affected ASes (long duration loops)= " + loopAffectedASes.size() + ", " + loopAffectedASes);
			System.out.println("Loop affected ASes (long duration loops)= " + loopAffectedASes.size());
			// System.out.println("Loop Or Disconnected ASes = " + loopOrDisconnectedASes.size() + " : " + loopOrDisconnectedASes);
			System.out.println("Loop Or Disconnected ASes = " + loopOrDisconnectedASes.size());
			System.out.println("Loop affected ASes (all loops)" + allLoopAffectedASes.size());
			System.out.println("Num connected domains = " + (upstreamASes.get(customer).size()-1));
			System.out.println("Num valid ASes = " + numASes);

			System.out.println("Convergence time = " + lastSimTime);

			HashMap<Integer, HashMap<Integer,IA>> tier1Paths = new HashMap<Integer, HashMap<Integer,IA>>();
			for(Iterator<Integer>provIt = loopOrDisconnectedASes.iterator(); provIt.hasNext();) {
			// for(Iterator<Short>provIt = failureProviderSet.iterator(); provIt.hasNext();) {
			    int fp = provIt.next(); 

			    // Compute path lengths from source -> t1 and from t1 -> dest
			    System.out.print("Tier1 detour from AS " + fp + " : ");
			    for(Iterator<Integer> it = tier1ASes.iterator(); it.hasNext();) {
				int t1 = it.next();

				if(!tier1Paths.containsKey(fp) || !tier1Paths.get(fp).containsKey(t1)) {
				    // Cache path from this AS to Tier 1
				    // p is the path from fp to t1
				    IA p = asMap.get(fp).bestPath.get(t1);
				    if(!tier1Paths.containsKey(fp)) {
					tier1Paths.put(fp, new HashMap<Integer, IA>());
				    }
				    tier1Paths.get(fp).put(t1, p);
				}
				IA p1 = tier1Paths.get(fp).get(t1);
				int p1length;
				if(fp == t1)
				    p1length = 0;
				else if(p1 == null || p1.getPath() == null)
				    p1length = -1;
				else
				    p1length = p1.getPath().size();

				// now find the shortest path from t1 to dst
				int p2length = findShortestPath(asMap.get(t1).getAllPaths(customer), provider, customer, t1);

				// Was this particular Tier 1 also affected by the outage?
				String disc = disconnectedASes.contains(t1) ? "T" : "N";
				if(permDisconnectedASes.contains(t1)) {
				    disc = "P";
				}

				System.out.print(t1 + ":" + disc + ":" + p1length + ":" + p2length + " ");
			    }
			    System.out.println();
			}

		}
	}	

	public static void runFCPSimulations() {
		// the set of ASes whose paths are affected by this failure
		HashSet<Integer> relevantASes = new HashSet<Integer>();
		HashSet<Integer> seenASes = new HashSet<Integer>();
		HashSet<Integer> noPathASes = new HashSet<Integer>();

		// this is used to store the number of ASes for each path inflation (+1, +2, etc)
		HashMap<Integer,Integer> inflationMap = new HashMap<Integer,Integer>();
		HashMap<Integer,Integer> backtrackMap = new HashMap<Integer,Integer>();

		int numLinks = failureCustomer.size();
		int numASes;
		for(int i=0; i<numLinks; i++) {
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			if(customer != currentCustomer) {
				currentCustomer = customer;
				for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
					it.next().RESET();
				}
				simTime = 0;
				upstreamASes.clear();
				r = new Random(seedVal);
//				System.out.println("Customer = " + customer);
				asMap.get(customer).announceSelf();
				run();
			}
//			checkForRoutingProblems(customer);
			// now we have to simulate the withdrawal followed by FCP!
			relevantASes.clear();
			seenASes.clear();
			noPathASes.clear();
			inflationMap.clear();
			backtrackMap.clear();

			relevantASes.addAll(upstreamASes.get(provider));
//			System.out.println("Upstream of provider = " + relevantASes);
			// first check if the provider has an alternate path .. if so everyone will use that!
			Collection<IA> allPaths = asMap.get(provider).getAllPaths(customer);
			// any path that doesn't traverse the customer-provider link is valid
			// pick the shortest among the valid paths.
			int pathLength = findShortestPath(allPaths, provider, customer, provider);
			if(pathLength != -1) { // alternate path to destination exists
				System.out.println("\nFailure: " + customer + " -> " + provider);
				System.out.println("Relevant: " + relevantASes.size());
				System.out.println("Disconnected: 0");
				System.out.println("Inflation: " + (pathLength-1) + ":" + relevantASes.size());
				System.out.println("Backtrack: 0:" + relevantASes.size());
				continue;
			}
			noPathASes.add(provider);

			for(Iterator<Integer> it = relevantASes.iterator(); it.hasNext(); ) {
				int as = it.next();
				if(seenASes.contains(as) || noPathASes.contains(as)) // already accounted for this
					continue;
				IA p = asMap.get(as).bestPath.get(customer);
//				System.out.println(p.path);
				// we know that p is a path which has the last link as provider-customer
				for(int j=p.getPath().size()-2; j>=-1; j--) {
					int current = as;
					if(j>=0)
						current = p.getPath().get(j);
					if(noPathASes.contains(current) || seenASes.contains(current)) {
						continue;
					}

					allPaths = asMap.get(current).getAllPaths(customer);
					pathLength = findShortestPath(allPaths, provider, customer, current);
					if(pathLength == -1) {
						// no path from current
						noPathASes.add(current);
						continue;
					}
					else {
						// alternate path found! inflation = pathLength -1 + (size-2)-j
						int backtrack = (p.getPath().size()-2) - j;
						int inflation = pathLength-1 + backtrack;
						int value = 0;
						if(inflationMap.containsKey(inflation))
							value = inflationMap.get(inflation);
						value += upstreamASes.get(current).size();
						inflationMap.put(inflation, value);

						seenASes.addAll(upstreamASes.get(current));

						value = 0;
						if(backtrackMap.containsKey(backtrack))
							value = backtrackMap.get(backtrack);
						value += upstreamASes.get(current).size();
						backtrackMap.put(backtrack, value);

						break;
					}
				}
				// walk along the path from this AS to the destination
			}

			// for FCP-backtrack1: if(provider) knows alternate path (best path from neighbor), then
			// #unsuccessful = 0 : inflation = length(P,D)-1 for all upstream nodes
			numASes = upstreamASes.get(customer).size();
			// the only ASes that would use this failed link are the ones that
			// use the 'upstream' node
			System.out.println("\nFailure: " + customer + " -> " + provider);
			System.out.println("Relevant: " + relevantASes.size());
			System.out.println("Disconnected: " + noPathASes.size());
			System.out.print("Inflation: " ); //+ (pathLength-1) + " " + relevantASes.size());
			for(Iterator<Integer> it = inflationMap.keySet().iterator(); it.hasNext();) {
				int key = it.next();
				System.out.print(key + ":" + inflationMap.get(key) + " ");
			}
			System.out.println();

			System.out.print("Backtrack: " ); //+ (pathLength-1) + " " + relevantASes.size());
			for(Iterator<Integer> it = backtrackMap.keySet().iterator(); it.hasNext();) {
				int key = it.next();
				System.out.print(key + ":" + backtrackMap.get(key) + " ");
			}
			System.out.println();

		}
	}

	/**
	 * This function returns the path length of the shortest path to the destination that doesn't go
	 * through the link upstream-downstream.
	 * 
	 * @param paths The set of paths we're interested in
	 * @param upstream The node upstream of the failed link
	 * @param downstream The node downstream of the failed link
	 * @param self The AS from where the path is being sought
	 * 
	 * @return The length of the shortest valid path
	 * 		   or -1 if there is none
	 * 
	 */
	public static int findShortestPath(Collection<IA> paths, int upstream, int downstream, int self) {
		if(paths == null)
			return -1;
		int pathLength = -1;
		for(Iterator<IA> it = paths.iterator(); it.hasNext();) {
			IA p = it.next();
			if(p==null || p.getPath() == null || p.getPath().size() == 0)
				continue;
			if(self == upstream && p.getPath().get(0) == downstream) // if the path is upstream-downstream
				continue;
			int index = p.getPath().indexOf(upstream);
			if( index == -1 || p.getPath().get(index+1) != downstream) { 
				// either upstream doesn't occur or if it does, then the link up-down doesn't appear
				if(pathLength == -1 || pathLength > p.getPath().size()) {
					pathLength = p.getPath().size();
				}
			}
		}
		return pathLength;
	}

	public static void runNewRegularSimulations() {
		int numLinks = failureCustomer.size();
		int numASes;
		for(int i=0; i<numLinks; i++) {
			instrumented = false;
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			currentCustomer = customer;
			for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
				it.next().RESET();
			}
			simTime = 0;
			upstreamASes.clear();
			r = new Random(seedVal);
			asMap.get(customer).announceSelf();
			run();
			numASes = upstreamASes.get(customer).size();
			// now all ASes have paths to the customer, and the set of upstream ASes
			// is fixed correctly

			// now we have to simulate the withdrawal!
			simTime = 0;
			totalDownTime.clear();
			prevDisconTime.clear();
			disconnectedASes.clear();
			affectedASes.clear();
			loopAffectedASes.clear();
			allLoopAffectedASes.clear();
			loopMarkerAS.clear();
			loopStart = -1;
			loopResolved = -1;
			asLoopMap.clear();
			loopTimeMap.clear();
			longestLoop = 0;
			
			// System.out.println("NO WITHDRAWAL!");
			simulateWithdrawal(customer,customer,provider,(long)10);
			// the instrumented version keeps track of availability/connectedness after each message
			// is processed
			instrumented = true;
			run(); //Instrumented();

			HashSet<Integer> finallyConnectedASes = upstreamASes.get(customer);
			
			HashSet<Integer> permDisconnectedASes = new HashSet<Integer>(asMap.keySet());
			permDisconnectedASes.removeAll(finallyConnectedASes);

			HashSet<Integer> loopOrDisconnectedASes = new HashSet<Integer>();
			loopOrDisconnectedASes.addAll(loopAffectedASes);
			loopOrDisconnectedASes.addAll(disconnectedASes);
			
			
			System.out.println("Failure: " + customer + " -> " + provider);
			System.out.println("Number of Disconnected ASes = " + disconnectedASes.size());
//			System.out.println("Number of Affected ASes = " + affectedASes.size());
			disconnectedASes.removeAll(permDisconnectedASes);
			System.out.println("Number of Temp Disconnected ASes = " + disconnectedASes.size());
			
			totalLoopDuration = loopResolved - loopStart;
			System.out.println("Longest Loop Duration = " + longestLoop);
			System.out.println("Loop affected ASes (long duration loops)= " + loopAffectedASes.size());
			System.out.println("Loop Or Disconnected ASes = " + loopOrDisconnectedASes.size());
			System.out.println("Loop affected ASes (all loops)" + allLoopAffectedASes.size());
			System.out.println("Num connected domains = " + (upstreamASes.get(customer).size()-1));
			System.out.println("Num valid ASes = " + numASes);

			System.out.println("Convergence time = " + lastSimTime);
			int downtime = 0;
			for(Iterator<Integer> it = totalDownTime.keySet().iterator(); it.hasNext();) {
				int tempAS = it.next();
				downtime += totalDownTime.get(tempAS);
//				System.out.println(tempAS + ": " + totalDownTime.get(tempAS) );
			}
			int avgDowntime;
			if(totalDownTime.size() == 0)
				avgDowntime = 0;
			else
				avgDowntime = downtime/totalDownTime.size();
			
			System.out.println("Avg Downtime = " + avgDowntime + " for #DownASes = " + totalDownTime.size() );
			System.out.println("Num Updates = " + numUpdateMessages);
			System.out.println("Num Withdrawals = " + numWithdrawMessages);
			System.out.println();
		}

	}

	public static void runRegularSimulations() {
		HashSet<Integer> loopOrDisconASes = new HashSet<Integer>();
		int numLinks = failureCustomer.size();
		int numASes;
		for(int i=0; i<numLinks; i++) {
			int customer = failureCustomer.get(i);
			int provider = failureProvider.get(i);
			if(customer != currentCustomer) {
				currentCustomer = customer;
				for(Iterator<AS> it = asMap.values().iterator(); it.hasNext();) {
					it.next().RESET();
				}
				simTime = 0;
				upstreamASes.clear();
				r = new Random(seedVal);
				asMap.get(customer).announceSelf();
				run();
				numASes = upstreamASes.get(customer).size();
			}
			else {
				// we are continuing with the same customer .. we need to re-announce the 
				// previously failed link
				simTime = 0;
				simulateAnnouncement(customer,customer,currentTarget,(long)10);
				run();
				numASes = upstreamASes.get(customer).size();
			}
			// now we have to simulate the withdrawal!
			simTime = 0;
			disconnectedASes.clear();
			affectedASes.clear();
			loopAffectedASes.clear();
			loopOrDisconASes.clear();
			loopStart = -1;
			loopResolved = -1;
			simulateWithdrawal(customer,customer,provider,(long)10);
			run();
			loopOrDisconASes.addAll(loopAffectedASes);
			loopOrDisconASes.addAll(disconnectedASes);

			System.out.println("Failure: " + customer + " -> " + provider);
			System.out.println("Number of Disconnected ASes = " + disconnectedASes.size());
			System.out.println("Number of Affected ASes = " + affectedASes.size());

			totalLoopDuration = loopResolved - loopStart;
			System.out.println("Total Loop Duration = " + totalLoopDuration);
			System.out.println("Loop affected ASes = " + loopAffectedASes.size());
			System.out.println("Loop-Disconnected ASes = " + loopOrDisconASes.size());
			System.out.println("Num connected domains = " + (upstreamASes.get(customer).size()-1));
			System.out.println("Num valid ASes = " + numASes);
			int numTransient = affectedASes.size() - (numASes - upstreamASes.get(customer).size());
			System.out.println("Number of transient disconnections = " + numTransient);
			System.out.println();
		}

	}

	/**
	 * This function reads in the file containing summary information about single homed ASes
	 * The file is in the format <asn> <num_single_homed_children>. This information is useful
	 * in computing availability and disconnectivity. If an AS is disconnected, we should also
	 * add all its single homed children, because they are not present in the reduced graph.
	 * 
	 * @param parentsFile The file containing the info
	 * @throws Exception blah
	 */
	private static void readParents(String parentsFile) throws Exception{
		BufferedReader br = new BufferedReader(new FileReader(parentsFile));
		while(br.ready()) {
			String[] token = br.readLine().split("\\s+");
			numSingleChildren.put(Integer.parseInt(token[0]), Integer.parseInt(token[1]));
		}
	}

	private static void readLinks(String linkFile) throws Exception{
		failureCustomer = new ArrayList<Integer>();
		failureProvider = new ArrayList<Integer>();

		BufferedReader br = new BufferedReader(new FileReader(linkFile));
		while(br.ready()) {
			String[] token = br.readLine().split("\\s+");
			if(Integer.parseInt(token[2])!=-1) // we are failing only customer-provider links
				continue;
			failureCustomer.add(Integer.parseInt(token[0]));
			failureProvider.add(Integer.parseInt(token[1]));
		}
	}

	static ArrayList<Integer> monitorStubASes = new ArrayList<Integer>();
	//method that reads in the types file, puts them in a special types list
	//this keeps in mind that the default type is BGP.
	private static int readTypes(String typesFile) throws Exception{
		int primaryType = 0;
		boolean firstType = true;
		BufferedReader br = new BufferedReader(new FileReader(typesFile));
		while(br.ready()){
			String[] token = br.readLine().split("\\s+");
			int as = Integer.parseInt(token[0]);
			int type = Integer.parseInt(token[1]);
			if(Array.getLength(token) == 2) //HACKISH FOR THE TRANSIT MEASUREMENT ONLY
			{
				monitorStubASes.add(as);
			}
			if(firstType)
			{
				primaryType = type;
				firstType = false;
			}
			asTypeDef.put(as, type);
		}
		br.close();
		return primaryType;
	}
	
	/**
	 * 
	 * method that reads intradomain latencies, should be ran after readtopology
	 * @param intraFile the file containing intradomain info
	 * @throws Exception
	 */
	private static void readIntraDomain(String intraFile) throws Exception{
		BufferedReader br = new BufferedReader(new FileReader(intraFile));
		while(br.ready())
		{
			//split so asnum is in first position			
			String[] token = br.readLine().split("\\|"); 			
			int as = Integer.parseInt(token[0]);
			//split into pop pairs
			String[] popPair = token[1].split(":");
			//for each paair, split, add to adjacency list of as
			for(String pair : popPair)
			{
				String[] pairToken = pair.split("\\s+");
				int pop1 = Integer.valueOf(pairToken[0]);
				int pop2 = Integer.valueOf(pairToken[1]);
				int latency = Integer.valueOf(pairToken[2]);
				if(asMap.containsKey(as))
					asMap.get(as).addIntraDomainLatency(pop1, pop2, latency);
			}
		}
		
	}
	
	private static int convertCost(int bw, int min, int max, int steps)
	{
		float chunk =  (max - min) / ((float)steps);
		float progress = min;
		for(int i = 1; i <= steps; i++)
		{
			progress += chunk;
			if(bw < progress)
			{
				return i;
			}
		}
		return 5;
	}
	
	private static void readTopology(String topologyFile, boolean useBandwidth) throws Exception {
		// remember to initialize seedVal before calling this function.

		BufferedReader br = new BufferedReader(new FileReader(topologyFile));
		while(br.ready()) {
			String[] token = br.readLine().split("\\s+");
			int as1 = Integer.parseInt(token[0]);
			int as2 = Integer.parseInt(token[1]);
			int relation = Integer.parseInt(token[2]);
			int linkMetric = 0; 
//			int cost =  Math.round((1/Float.parseFloat(token[3])) * 100000); 
		//	int cost =  (int) Math.round(1/Math.log10(Float.parseFloat(token[3])) * 10000); //log! 
			float bw = Math.round(Float.parseFloat(token[3]));
			float cost = bw;
			if(cost > 5)
			{
				cost = 5;
			}
			if(specialR.nextDouble() < .5) //for thelove of god, delete this later
			{
				cost = 5;
			}
			//int cost =convertCost(bw, 10, 1024, 5);
		//	System.out.println("cost: " + cost);
			//decide whether to use bandwidth or latency
//			if(!useBandwidth){
//				linkMetric = Math.round((1/Float.parseFloat(token[3])) * 100000); //working with bandwidth maybe temporary, so parse float Integer.parseInt(token[3]);
//			}
//			else
//			{
//				linkMetric = Math.round(Float.parseFloat(token[3]));
//			}
			int pop1 = Integer.parseInt(token[4]);
			int pop2 = Integer.parseInt(token[5]);
//			int as1Type = Integer.parseInt(token[4]);
//			int as2Type = Integer.parseInt(token[5]);
			if(relation == AS.SIBLING) // we don't deal with this now
				continue;
			AS temp1 = null, temp2 = null;
			if(!asMap.containsKey(as1)) {
				int mraiVal = (int)(Math.round((r.nextFloat()*0.25 + 0.75)*MRAI_TIMER_VALUE/1000)*1000);
//				System.err.println("AS" + as1 + " MRAI: " + mraiVal);
				//if there is a special as type defined, then use that
				if(asTypeDef.containsKey(as1)){					
					if(asTypeDef.get(as1) == AS.TRANSIT)
						temp1 = new Wiser_AS(as1, mraiVal, false);
					else if(asTypeDef.get(as1) == AS.WISER)
						temp1 = new Wiser_AS(as1, mraiVal, false);
					else if (asTypeDef.get(as1) == AS.SBGP_TRANSIT || asTypeDef.get(as1) == AS.SBGP)
					{
						temp1 = new SBGP_AS(as1, mraiVal);
					}
					else if (asTypeDef.get(as1) == AS.BANDWIDTH_AS || asTypeDef.get(as1) == AS.BANDWIDTH_TRANSIT)
					{
						temp1 = new Bandwidth_AS(as1, mraiVal, false);
					}
					else if(asTypeDef.get(as1) == AS.REPLACEMENT_AS)
					{
						temp1 = new Replacement_AS(as1, mraiVal);
					}
				}
					//temp1 = new BGP_AS(as1, mraiVal); //
				//else just use efault bgp
				else
				{
					temp1 = new BGP_AS(as1, mraiVal);
				}
	//			temp1.protocol = as1Type;
				asMap.put(as1, temp1);
			}
			temp1 = asMap.get(as1);

			if(!asMap.containsKey(as2)) {
				int mraiVal = (int)(Math.round((r.nextFloat()*0.25 + 0.75)*MRAI_TIMER_VALUE/1000)*1000);
//				System.err.println("AS" + as2 + " MRAI: " + mraiVal);
				if(asTypeDef.containsKey(as2)){
					if(asTypeDef.get(as2) == AS.TRANSIT)
						temp2 = new Wiser_AS(as2, mraiVal, false);
					else if(asTypeDef.get(as2) == AS.WISER)
						temp2 = new Wiser_AS(as2, mraiVal, false);
					else if (asTypeDef.get(as2) == AS.SBGP_TRANSIT || asTypeDef.get(as2) == AS.SBGP)
					{
						temp2 = new SBGP_AS(as2, mraiVal);
					}
					else if (asTypeDef.get(as2) == AS.BANDWIDTH_AS || asTypeDef.get(as2) == AS.BANDWIDTH_TRANSIT)
					{
						temp2 = new Bandwidth_AS(as2, mraiVal, false);
					}
					else if (asTypeDef.get(as2) == AS.REPLACEMENT_AS)
					{
						temp2 = new Replacement_AS(as2, mraiVal);
					}
				}
				else
				{
					temp2 = new BGP_AS(as2, mraiVal);
				}
//				temp2.protocol = as2Type;
				asMap.put(as2, temp2);
			}
			temp2 = asMap.get(as2);

			// AS1 AS2 CUSTOMER (-1) => AS1 is a customer of AS2
			// AS1 AS2 PROVIDER (1)  => AS1 is a provider of AS2
			if(relation == AS.CUSTOMER) {
				temp1.addProvider(as2);
				temp2.addCustomer(as1);
			}
			else if(relation == AS.PROVIDER) {
				temp1.addCustomer(as2);
				temp2.addProvider(as1);
			}
			else if(relation == AS.PEER){
				temp1.addPeer(as2);
				temp2.addPeer(as1);
			}
			
			temp1.addLinkMetric(temp2.asn, new AS.PoPTuple(pop1, pop2), AS.COST_METRIC, cost);
			temp1.addLinkMetric(temp2.asn, new AS.PoPTuple(pop1, pop2), AS.BW_METRIC, bw);
			temp2.addLinkMetric(temp1.asn, new AS.PoPTuple(pop2, pop1) , AS.BW_METRIC, bw);
			temp2.addLinkMetric(temp1.asn, new AS.PoPTuple(pop2, pop1) , AS.COST_METRIC, cost);
//			else { // sibling?
//			temp1.addCustomer(as2);
//			temp2.addCustomer(as1);
//			}
		}
		br.close();
	}
	
	/**
	 * helper method for the metheod below
	 * @param searchQueue search queue in bfs
	 * @param predecessorList the predecessor list we are creating
	 * @param verticesSeenSoFar see verticies seen so far in the belwo method
	 * @param neighborSet the neighbors that we are considering
	 * @param searchAS 
	 */
	private static void updatePredecessorAndVerticiesSeen(ArrayDeque<Integer> searchQueue, HashMap<Integer, ArrayList<Integer>> predecessorList, HashSet<Integer> verticesSeenSoFar, ArrayList<Integer> neighborSet, int searchAS)
	{
		//for each neighbor
		for(Integer neighbor : neighborSet)
		{
			//if this is not a replacdement as, then skip
			if(asMap.get(neighbor).type == AS.REPLACEMENT_AS){
				if(!predecessorList.containsKey(neighbor))
				{
					predecessorList.put(neighbor, new ArrayList<Integer>());
				}
				ArrayList<Integer> predecessors = predecessorList.get(neighbor);
				if(predecessors != null){
					if(!predecessors.contains(searchAS))
					{
						predecessors.add(searchAS);
					}
				}				
				if(!verticesSeenSoFar.contains(neighbor))
				{
					searchQueue.add(neighbor);
					verticesSeenSoFar.add(neighbor);
				}
			}
		}
	}
	
	private static HashMap<Integer, ArrayList<Integer>> getPredecessorList(int asNum)
	{	
		HashSet<Integer> verticesSeenSoFar = new HashSet<Integer>();
		HashMap<Integer, ArrayList<Integer>> predecessorList = new HashMap<Integer, ArrayList<Integer>>();
		//		System.out.println("[DEBUG] total ASes: " + asMap.size());
		//perform breadth first search
		//			System.out.println("DEBUG CCs: " + numConnectedComponents );
		ArrayDeque<Integer> searchQueue = new ArrayDeque<Integer>();
		searchQueue.add(asNum);
		verticesSeenSoFar.add(asNum);
		predecessorList.put(asNum, null);
		while(!searchQueue.isEmpty())
		{
			//				System.out.print("searchqueuesize: " + searchQueue.size() + "\r");
			Integer searchEntry = searchQueue.pop();
			AS searchAS = asMap.get(searchEntry);
			//					verticesSeenSoFar.add(searchEntry);
			
			updatePredecessorAndVerticiesSeen(searchQueue,predecessorList, verticesSeenSoFar, searchAS.customers, searchAS.asn);	
			updatePredecessorAndVerticiesSeen(searchQueue,predecessorList, verticesSeenSoFar, searchAS.peers, searchAS.asn);	
			updatePredecessorAndVerticiesSeen(searchQueue,predecessorList, verticesSeenSoFar, searchAS.providers, searchAS.asn);	
//			for(Integer customer : searchAS.customers)
//			{
//				if(!predecessorList.containsKey(customer))
//				{
//					predecessorList.put(customer, new ArrayList<Integer>());
//				}
//				ArrayList<Integer> predecessors = predecessorList.get(customer);
//				if(!predecessors.contains(searchAS.asn))
//				{
//					predecessors.add(searchAS.asn);
//				}
//				if(!verticesSeenSoFar.contains(customer))
//				{
//					searchQueue.add(customer);
//					verticesSeenSoFar.add(customer);
//				}
//			}
//			for(Integer peer : searchAS.peers)
//			{
//				if(!verticesSeenSoFar.contains(peer))
//				{
//					searchQueue.add(peer);
//					verticesSeenSoFar.add(peer);
//				}
//			}
//			for(Integer provider : searchAS.providers)
//			{
//				if(!verticesSeenSoFar.contains(provider))
//				{
//					searchQueue.add(provider);
//					verticesSeenSoFar.add(provider);
//				}
			}
			//				System.out.println("DEBUG ccsize: " + ccSize);;

		
//		System.out.println("[DEBUG] ccSizeAggregateSum: " + ccSizeAggregateSum);
		return predecessorList;
	}
	
	private static long findNumPaths(int root, HashMap<Integer, ArrayList<Integer>> predecessorList, int asn)
	{		
		ArrayDeque<LinkedList<Integer>> queue = new ArrayDeque<LinkedList<Integer>>();		
		ArrayList<LinkedList<Integer>> paths = new ArrayList<LinkedList<Integer>>();
		int count = 0;
		if(asn == root)
		{
			return 0;
		}
		LinkedList<Integer> temp = new LinkedList<Integer>();
		temp.add(asn);
		queue.addFirst(temp);
//		if(predecessors == null)
//		{
//			return 0;
//		}
//		for(int element : predecessors)
//		{
//			queue.addLast(element);
//		}
		while(!queue.isEmpty())
		{
			count = queue.size();
			if(count > NUM_PATH_CAP)
			{
				return count;
			}
			LinkedList<Integer> path = queue.removeFirst();
			if(path.getLast() != root)
			{
				ArrayList<Integer> predecessors = predecessorList.get(path.getLast());
				for(int predecessor : predecessors)
				{
					if(!path.contains(predecessor)){
						LinkedList<Integer> newPath = (LinkedList<Integer>) path.clone();
						newPath.addLast(predecessor);
						queue.addFirst(newPath);
					}
				}
			}
			else
			{
				paths.add(path);
				if(paths.size() > NUM_PATH_CAP)
				{
					count = paths.size();
					break;
				}
			}
		}
		

		return count;
	}
	
	private static void fillASNumPaths(HashMap<Integer, ArrayList<Integer>> predecessorList, int asn)
	{
		HashSet<Integer> stubs = computeStubs();
		AS theAS = asMap.get(asn);
		if(theAS.type == AS.REPLACEMENT_AS)
		{
			for(int aAS : asMap.keySet())
			{
				if(predecessorList.containsKey(aAS))
				{
					long numPaths = findNumPaths(theAS.asn, predecessorList, aAS);
//					System.out.println("numpaths: " + numPaths);
					((Replacement_AS) theAS).numPathsToDest.put(aAS, numPaths);
					((Replacement_AS) theAS).islandBuddies.add(aAS);
				}
				else
				{
					((Replacement_AS) theAS).numPathsToDest.put(aAS, (long) 1);
				}
			}
		}
	}
	
	private static void preProcessReplacement()
	{
		for(AS aAS : asMap.values())
		{
			if(aAS.type == AS.REPLACEMENT_AS)
			{
				//System.out.println("preprocessingreplacement: " + aAS.asn);
				HashMap<Integer, ArrayList<Integer>> predecessorList = getPredecessorList(aAS.asn);
				fillASNumPaths(predecessorList, aAS.asn);
	//			System.out.println("AS done: " + aAS.asn );
			}
		}
	}

	public static void debug(String str){
	    if(true) return;
	    try {
		out.write(str);
		out.newLine();
		out.flush();
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}

	/**
	 * This function is used by the simulator to check if there are any
	 * disconnections or loops at the current instant in the network
	 * for the given destination
	 * @param dst The destination to whom paths are being tested
	 * @return True, if there are problems
	 * 		   False, if there are no loops/disconnections
	 */
	private static boolean checkForRoutingProblems(int dst) {
		// For each AS, find the forwarding path to the destination, and check if there
		// are loops/disconnects
		HashSet<Integer> safeASes = new HashSet<Integer>();
		HashSet<Integer> loopyASes = new HashSet<Integer>();
		HashSet<Integer> disconnectedASes = new HashSet<Integer>();

		for(Iterator<Integer> it = asMap.keySet().iterator(); it.hasNext(); ) {
			// for each AS other than dst check the forwarding path
			int currentAS = it.next();
			if(currentAS == dst)
				continue;
			ArrayList<Integer> currentPath = new ArrayList<Integer>();
			currentPath.add(currentAS);
			IA p = asMap.get(currentAS).bestPath.get(dst);
			while(true) {
				if(p==null || p.getPath() == null || disconnectedASes.contains(currentAS)) {
					// currentAS has no path to the destination
					disconnectedASes.addAll(currentPath);
					break;
				} else {
					currentAS = p.getFirstHop();
					if(currentAS == dst || safeASes.contains(currentAS)) {
						// valid path .. so done
						safeASes.addAll(currentPath);
						break;
					} else if(currentPath.contains(currentAS) || loopyASes.contains(currentAS)) {
						loopyASes.addAll(currentPath);
						break;
					} else { // currentAS unexplored .. so continue exploring
						currentPath.add(currentAS);
						p = asMap.get(currentAS).bestPath.get(dst);
					}
				}
			} // end while loop .. end of current path
		}
		if(loopyASes.isEmpty() && disconnectedASes.isEmpty())
			return false;
		else {
			System.out.println(simTime + ": Loopy = " + loopyASes + " Disconnected = " + disconnectedASes);
			return true;
		}
	}
	
	public static boolean pathExists(int src, int dst) {
		HashSet<Integer> seenHops = new HashSet<Integer>();
		seenHops.add(src);
		int nextHop = src;
		while(true) {
			if(nextHop == dst)
				return true;
			nextHop = asMap.get(nextHop).getNextHop(dst);
			if(seenHops.contains(nextHop)) {
				return false;
			}
			seenHops.add(nextHop);
			if(nextHop == -1) {
				return false;
			}
		}
	}
	
	public static String printPath(int src, int dst) {
		String path = "" + src;
		HashSet<Integer> seenHops = new HashSet<Integer>();
		seenHops.add(src);
		int nextHop = src;
		while(true) {
			if(nextHop == dst)
				break;
			nextHop = asMap.get(nextHop).getNextHop(dst);
			path += " " + nextHop;
			if(seenHops.contains(nextHop)) {
				break;
			}
			if(nextHop == -1) {
				break;
			}
			seenHops.add(nextHop);
		}
		
		return path;
	}
	
	public static HashSet<Integer> getConnectedASes(int dst) {
		HashSet<Integer> connectedSet = new HashSet<Integer>();
		HashSet<Integer> disconnectedSet = new HashSet<Integer>();
		connectedSet.add(dst);
		HashSet<Integer> seenSoFar = new HashSet<Integer>();
		for(Iterator<Integer> it = asMap.keySet().iterator(); it.hasNext();) {
			int src = it.next();	
			seenSoFar.clear();
			
			int nextHop = src;
			seenSoFar.add(src);
			while(true) {
				if(connectedSet.contains(nextHop)) {
					connectedSet.addAll(seenSoFar);
					break;
				}
				
				nextHop = asMap.get(nextHop).getNextHop(dst);
				if(seenSoFar.contains(nextHop)) { // loop!
					disconnectedSet.addAll(seenSoFar);
					break;
				}
				if(nextHop == -1) {
					disconnectedSet.addAll(seenSoFar);
					break;
				}
				seenSoFar.add(nextHop);
			}
		}
		connectedSet.remove(dst);
		return connectedSet;
	}

	/**
	 * This function is used to simulate the withdrawal of a path by an AS
	 * 
	 * @param byAS The AS which is sending out the withdrawal
	 * @param forAS The destination prefix being withdrawn
	 * @param toAS The AS to whom the announcement is sent
	 * @param simTime The time at which the announcement is sent to the target
	 */
	private static void simulateWithdrawal(int byAS, int forAS, int toAS, long simTime) {
		currentTarget = toAS;
		Message controlMsg = new ControlMessage(byAS, ControlMessage.WITHDRAW, toAS, forAS);
		addEvent( new Event(simTime, byAS, controlMsg));
	}

	/**
	 * This function is used to simulate the announcing of a path by an AS
	 * 
	 * @param byAS The AS which is sending out the announcement
	 * @param forAS The destination prefix being announced
	 * @param toAS The AS to whom the announcement is sent
	 * @param simTime The time at which the announcement is sent to the target
	 */
	private static void simulateAnnouncement(int byAS, int forAS, int toAS, long simTime) {
		Message controlMsg = new ControlMessage(byAS, ControlMessage.ANNOUNCE, toAS, forAS);
		addEvent( new Event(simTime, byAS, controlMsg));
	}

	private static void startSnapshot(int startAS, long simTime) {
		SnapshotMessage msg = new SnapshotMessage(startAS);
		addEvent(new Event(simTime, startAS, msg));
	}

} // end class Simulator
