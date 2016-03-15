package simulator;
/**
 * file: REPLACEMENT_AS.java
 * @author John
 *
 */

import integratedAdvertisement.IA;
import integratedAdvertisement.IAInfo;
import integratedAdvertisement.Protocol;
import integratedAdvertisement.RootCause;
import integratedAdvertisement.Values;

import java.io.UnsupportedEncodingException;
import java.util.*;

/**
 * This class defines and Autonomous System or AS. Each AS has a unique
 * AS number, which is its sole identifier. The REPLACEMENT_AS class is responsible
 * for handling events which are addressed to it. The AS also contains
 * the RIB-in and RIB-out and the Forwarding Table.
 * 
 * When an update is received, it runs the BGP decision algorithm, and
 * picks the best new best path. If it is different from the previous
 * one, it generates updates to be sent to each of its peers.
 */
public class Replacement_AS extends AS {

	private static final int MAX_PATHS_TO_PROPAGATE = Simulator.NUM_PATH_CAP; //max paths to propagate so as to be more realistic
																			  //could use the simulator value directly
	
	private static final int LINK_DELAY = 100; // static link delay of 10 ms

	//how many paths does this node have to a destination
	//<destination, number of paths to destination>
	public HashMap<Integer, Long> numPathsToDest = new HashMap<Integer, Long>();
	
	//set of replacement ASes in island.
	public HashSet<Integer> islandBuddies = new HashSet<Integer>();
	
	/** The current epoch */
	private int currentEpoch;

	/** The current update number root-caused by me */
	public int currentUpdate;

	/** The MRAI timer value */
	int mraiValue;

	/** Old/current Stable Forwarding Table */
	HashMap<Integer,IA> SFT = new HashMap<Integer, IA>();

	/** New Stable Forwarding Table SFT' */
	HashMap<Integer,IA> SFTp = new HashMap<Integer, IA>();

	/** 
	 * Stores the set of pending updates for a peer.
	 * Hashed on peer first and then destination <neighbor, prefix>. 
	 * This is because when the MRAI timer expires for a peer, 
	 * you want to get all the messages for that peer
	 */
	HashMap<Integer, HashMap<Integer, IA>> pendingUpdates = new HashMap<Integer, HashMap<Integer, IA>>();

	/**
	 * Stores the RIBHist for each destination. The RIBHist contains the history of
	 * the chosen paths for each destination.
	 */
	HashMap<Integer, RIBHist> dstRIBHistMap = new HashMap<Integer, RIBHist>();

	/** Stores whether the MRAI timer is running for this peer */
	HashMap<Integer, Boolean> mraiRunning = new HashMap<Integer, Boolean>();

	/** Stores whether we are recording the channel for this peer */
	HashMap<Integer, Boolean> recordingPeer = new HashMap<Integer, Boolean>();

	/** Keeps track of the number of pending responses for Snapshot Message
	 * This value should be equal to the number of peers whose channel we are
	 * recording
	 */
	private int pendingResponses = 0;

	// TODO Need to finalize notation once conditional updates come into picture
	/** This is the set of updates I know to be locally incomplete
	 * This is valid only for the current epoch
	 */
	HashSet<RootCause> updatesInTransit = new HashSet<RootCause>();

	/** The set of updates which I received this epoch, and the ones which were tagged
	 * incomplete from the previous epochs.
	 * This is used to determine the root-cause of a path I send out
	 */
	HashSet<RootCause> nonFinishedUpdates = new HashSet<RootCause>();

	/** The set of ASes whose flood packets I've seen */
	HashSet<Integer> floodsSeen = new HashSet<Integer>();

	/** This stores the set of incomplete updates I've seen from all my floods this epoch */
	HashSet<RootCause> floodsIncomplete = new HashSet<RootCause>();

	/** This stores the information on conditionally incomplete updates from all my floods this epoch */ 
	HashSet<UpdateDependency> floodsConditional = new HashSet<UpdateDependency>();

	/**
	 * The constructor for an REPLACEMENT_AS
	 * 
	 * @param asnum The AS number of this REPLACEMENT_AS
	 */
	public Replacement_AS(int asnum, int mrai) {
		asn = asnum;
		mraiValue = mrai;
		super.type = AS.REPLACEMENT_AS;

//		// initialize all MRAI timers to false
//		// set the neighbor type
//		// announce self to all neighbors
//		for(int i=0; i<customers.size(); i++) {
//		mraiRunning.put(customers.get(i), false);
//		neighborMap.put(customers[1], CUSTOMER);
//		}
//		for(int i=0; i<providers.size(); i++) {
//		mraiRunning.put(providers.get(i), false);
//		neighborMap.put(providers.get(i), PROVIDER);
//		}
//		for(int i=0; i<peers.size(); i++) {
//		mraiRunning.put(peers.get(i), false);
//		neighborMap.put(peers.get(i), PEER);
//		}
	}

	public void addCustomer(int asnum) {
		if(neighborMap.containsKey(asnum))
			return;
		customers.add(asnum);
		neighborMap.put(asnum, CUSTOMER);
		mraiRunning.put(asnum, false);
	}

	public void addProvider(int asnum) {
		if(neighborMap.containsKey(asnum))
			return;
		providers.add(asnum);
		neighborMap.put(asnum, PROVIDER);
		mraiRunning.put(asnum, false);
	}

	public void addPeer(int asnum) {
		if(neighborMap.containsKey(asnum))
			return;
		peers.add(asnum);
		neighborMap.put(asnum, PEER);
		mraiRunning.put(asnum, false);
	}

	
	/**
	 * This function is used to reset the state of the REPLACEMENT_AS
	 *
	 */
	public void RESET() {
		int peer;
		for(int i =0; i<customers.size(); i++) {
			peer = customers.get(i);
			mraiRunning.put(peer, false);
		}
		for(int i =0; i<providers.size(); i++) {
			peer = providers.get(i);
			mraiRunning.put(peer, false);
		}
		for(int i =0; i<peers.size(); i++) {
			peer = peers.get(i);
			mraiRunning.put(peer, false);
		}
		ribIn.clear();
		bestPath.clear();
		pendingUpdates.clear();
		dstRIBHistMap.clear();
		recordingPeer.clear();
		nonFinishedUpdates.clear();
		updatesInTransit.clear();
		pendingResponses = 0;
		
	}
	
	/**
	 * This function is called when an REPLACEMENT_AS is brought up, to announce its prefix to
	 * all its neighbors.
	 *
	 */
	public void announceSelf() {
		addPathToUpdates(new IA(new RootCause(asn, currentUpdate++, asn)), Simulator.otherTimers);
		this.announced = true;
	}

	/**
	 * Adds a path to the RIB-In of this REPLACEMENT_AS
	 * @param p The path to be added
	 */
	public void addPathToRib(IA p) {
		int dst = p.getDest();
		int nextHop = p.getFirstHop();
		HashMap<Integer, IA> temp;
		if(!ribIn.containsKey(dst)) {
			temp = new HashMap<Integer, IA>();
			ribIn.put(dst, temp);
		}
		temp = ribIn.get(dst);
		temp.put(nextHop, p);
		passThrough.addToDatabase(p); //add path and information to passthrough database
	}
	

	/**
	 * find the edge of an island so you can fill the replacement information
	 * If the destination is part of the island, it will be returned. keep that in mind
	 * @param path - the path being checked (expects unprepended path)
	 * @return the asnum of the incomding edge (that is, downstream entrance to island), -1 if you the edge of the island (an island of 1)
	 */
	/**
	 * @param path
	 * @return
	 */
	private int findIslandEdge(LinkedList<Integer> path)
	{
		//LinkedList<Integer> path = (LinkedList<Integer>) advert.getPath().clone();
		//path.removeFirst(); // remove our prepend
		if(path.isEmpty())
		{
			return -1;
		}
		int beforeNode = path.getFirst();
		for(int node : path)
		{
			if(islandBuddies.contains(node)){
				beforeNode = node; //we now this node is in the island, the next node might not be.  If the 
								//next node isn't, then this node is the edge.  keep track of it
			}
			else
			{
				break;
			}
		}
		if(!islandBuddies.contains(beforeNode)){ //means that it is this AS, this as wouldn't be in island buddies
			return -1;
		}
		return beforeNode;
	}
	
	
	/**
	 * method that fills the pop information with info (that is, what information is being advertised across differnet pops)
	 * also attaches the passthrough
	 * @param advert the advertisement we need to fill with info
	 * @param advertisedToAS the as we are advertising to
	 * @param tupleChosen the pop tuple we chose for the downstream as
	 * 
	 */
	public void fillAdvertisementPoP(IA advert, Integer advertisedToAS, PoPTuple tupleChosen)
	{
//		if(tupleChosen.pop1 == -1)
//		{
//			System.out.println("[debug] intradomaintruecost: shouldn't be here (maybe(");		
//		}
//		else{
		updateBookKeepingOutward(advert, advertisedToAS);	
		if(tupleChosen != null){
			for(AS.PoPTuple poptuple : neighborMetric.get(advertisedToAS).keySet())
			{
				int intraDomainCost = getIntraDomainCost(tupleChosen.pop1, poptuple.pop1, advertisedToAS);
				advert.truePoPCosts.put(poptuple, intraDomainCost);
			}
		}
				
		advert.popCosts.clear();
		//add how many paths there are into IA
		IAInfo replacementInfo = new IAInfo();
		long transitPaths = 1;
		//if the destination is NOT part of our island, then we have some transit paths to account for
		if(!islandBuddies.contains(advertisedToAS))
		{
			LinkedList<Integer> path = (LinkedList<Integer>) advert.getPath().clone();
			path.removeFirst(); //remove our prepend
			int islandEntrance = findIslandEdge(path); //find where this advertisement is entering the island
			if(islandEntrance != -1){ //at that spot, get how many paths we have to it
				transitPaths = numPathsToDest.get(islandEntrance);
			}
	//		System.out.println(transitPaths);
		}
		
		long pathsToDest = numPathsToDest.get(advert.getDest()); //how many paths do we have to the destination natively (if it is part of a 
																//scion island, then it will be more than 1, otherwise it will be 1
		boolean directMultiPath = pathsToDest > 1 ? true : false; //if we are directly connected and have multiple paths to destination set to true
		long inAdvertNumPaths = 1; //number of paths in advertisement
		String[] inAdvertPathsToDest = AS.getProtoProps(advert, tupleChosen, new Protocol(AS.REPLACEMENT_AS));
		if(inAdvertPathsToDest != null)
		{
			inAdvertNumPaths = Long.valueOf(inAdvertPathsToDest[0]);
		}
		if(!directMultiPath){ //untested changes
			pathsToDest = transitPaths * inAdvertNumPaths;
		}
		//minor bug detected, wasn't taking into account the inadvert paths.  Makes our results more pessimistic than reality. 
		//contiguous deployment scenario unaffected, affects the noncontiguous scenario because transit islands weren't able to add
		//their paths in. untested changes
		//pathsToDest *= transitPaths; original caught bug	
	//	if(pathsToDest > inAdvertNumPaths) commented out see bug description above
	//	{
			
		if(pathsToDest > MAX_PATHS_TO_PROPAGATE)
		{
			pathsToDest = MAX_PATHS_TO_PROPAGATE;
		}
		String pathAttribute = String.valueOf(pathsToDest);
		try {
			replacementInfo.setProtocolPathAttribute(pathAttribute.getBytes("UTF-8"), new Protocol(AS.REPLACEMENT_AS), advert.getPath());
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	//	}

		for(AS.PoPTuple popTuple : neighborMetric.get(advertisedToAS).keySet())
		{

			advert.popCosts.put(popTuple, replacementInfo);
		}

		passThrough.attachPassthrough(advert, tupleChosen);
//		}
		
	}

	
//	@Override 
//	protected void updateBookKeeping(IA advert, PoPTuple chosenTuple)
//	{
//		if(neighborLatency.containsKey(advert.getFirstHop()))
//		{
//			advert.setTrueCost(advert.getTrueCost() + neighborLatency.get(advert.getFirstHop()).get(chosenTuple));
//		}
//		else
//		{
//			System.out.println("bgp_as, can't update costs");
//		}
//	}
	
	void readyForPeer(int pseudoMraiValue, IA newPath, IA oldPath, int forAS, boolean simulateTimers, PoPTuple tupleChosen)
	{
		IA overWritePath = new IA(newPath);
		fillAdvertisementPoP(overWritePath, forAS, tupleChosen);
		addPathToPendingUpdatesForPeer(overWritePath, forAS);
		if(simulateTimers) {
			if(!mraiRunning.get(forAS)) {
				mraiRunning.put(forAS, true);
				Simulator.addEvent(new Event(Simulator.getTime() + pseudoMraiValue,
						asn, forAS));
			}
		}
		sendUpdatesToPeer(forAS);
	}					

	
	
	/**
	 * This function adds a path to the set of path
	 * updates. Uses outbound filtering to decide which
	 * peers should be advertised this path
	 * 
	 * If there is already a pending update for this peer
	 * wrt to this prefix, the pending updates is marked
	 * as conditionally incomplete depending on this update
	 * and is removed from RIB-Hist (it cannot be chosen
	 * since it wasn't propagated to all neighbors)
	 * 
	 * @param p The path which is to be advertised
	 * @param simulateTimers This variable specifies if we have to model
	 * the behaviour of other destinations. Since timers are per-peer
	 */
	public void addPathToUpdates(IA p, boolean simulateTimers) {
		// TODO: might have to change the RootCause
		IA newPath = new IA(p); //new IA(p.getPath(), p.getRootCause());
		newPath.prepend(asn);
		int nhType = CUSTOMER; // paths to self should be announced to all
		int nh = -1;
		// shadow the global mrai value
		int pseudoMraiValue = Math.round(this.mraiValue*Simulator.r.nextFloat()/1000)*1000;
//		int pseudoMraiValue = this.mraiValue;
//		int pseudoMraiValue = Math.round(this.mraiValue*Simulator.r.nextFloat());
		PoPTuple tupleChosen = null;
		if(p.getPath().size() > 0) {
			nh = p.getFirstHop(); // this is the AS that advertised the path to us
			nhType = neighborMap.get(nh);
			
			tupleChosen = tupleChosen(p);
			if(p.popCosts.size() > 0)
			{
	//			updateBookKeeping(p, tupleChosen); //update the bookkeeping on p, (getfirsthop is used in method so we use p instead of newpath)
			}
			else{
				System.out.println("REPLACEMENT_AS, AS advertising?: " + asn);
			}
			newPath.truePoPCosts.clear();
			newPath.setTrueCost(p.getTrueCost()); //this is redundant, could delete.
		}

		newPath.secure = false;
		if(nhType == PROVIDER || nhType == PEER) { // announce it only to customers .. and to nextHop in the path 
			for(int i=0; i<customers.size(); i++) {
				readyForPeer(pseudoMraiValue, newPath, p, customers.get(i), simulateTimers, tupleChosen);
			}
			
			readyForPeer(pseudoMraiValue, newPath, p, nh, simulateTimers, tupleChosen);
		}
		else { // customer path, so announce to all
			for(int i=0; i<customers.size(); i++) {
				readyForPeer(pseudoMraiValue, newPath, p, customers.get(i), simulateTimers, tupleChosen);
			}
			for(int i=0; i<providers.size(); i++) {
				readyForPeer(pseudoMraiValue, newPath, p, providers.get(i), simulateTimers, tupleChosen);
			}
			for(int i=0; i<peers.size(); i++) {
				readyForPeer(pseudoMraiValue, newPath, p, peers.get(i), simulateTimers, tupleChosen);
			}
		}
	}

	/**
	 * Convenience function: adds a path to the set of pending updates for a peer
	 * @param p The path that is being added.
	 * @param peer The peer for whom this update is queued
	 */
	private void addPathToPendingUpdatesForPeer(IA p, int peer) {	
		HashMap<Integer,IA> dstPathMap = new HashMap<Integer,IA>();
		if(!pendingUpdates.containsKey(peer)) {
			pendingUpdates.put(peer, dstPathMap);
		}
		dstPathMap = pendingUpdates.get(peer);
		int dst = p.getDest();
		if(dstPathMap.containsKey(dst)) { // we are replacing a pending update with another
			IA replaced = dstPathMap.get(dst);
			removeFromRIBHistAndMakeConditional(replaced, p);
		}
		dstPathMap.put(dst, p);
	}

	/**
	 * This function removes a particular update from RIB-Hist, and makes it a conditionally incomplete
	 * update dependent on the new replacement 
	 * @param replaced
	 * @param newPath
	 */
	private void removeFromRIBHistAndMakeConditional(IA replaced, IA newPath) {
		Integer dest = replaced.getDest();
		RIBHist temp = dstRIBHistMap.get(dest);
		temp.removePath(replaced);
		if(temp.isHotNbr(replaced.getFirstHop())) {
			temp.addCondIncomplete(replaced.getRootCause(), newPath.getRootCause());
		}
	}

	/**
	 * Removes the path to 'dst' previously announced by 'nextHop' from
	 * the RIB-in
	 * 
	 * @param dst The destination prefix that was withdrawn
	 * @param nextHop The neighbor that announced the withdrawal
	 */
	public boolean removePathFromRIBIn(Integer dst, Integer nextHop) {
		HashMap<Integer, IA> temp = ribIn.get(dst);
		// if there is no path, ignore
		if(temp == null) {
			return false;
		}
		IA path = temp.get(nextHop);
		passThrough.removeFromDatabase(IA.pathToKey(path.getPath())); //[COMMENT] added, if removing path from RIB, remove it from passthrough database
		temp.remove(nextHop);		
		return true;
	}

	/** 
	 * This method is responsible for receiving and handling events
	 * 
	 * @param e The event that is to be handled
	 */
	public void handleEvent(Event e) {
		assert(e.eventFor == asn);
		if(e.eventType == Event.MRAI_EVENT) {
			int peer = e.timerExpiredForPeer;
			mraiRunning.put(peer, false);
			sendUpdatesToPeer(peer);
		} 
		else if(e.eventType == Event.MSG_EVENT) {
			// we need to handle the update
			processMessage(e.msg);
		}
	}

	private void processMessage(Message msg) {
		// TODO Auto-generated method stub
		if(msg.messageType == Message.UPDATE_MSG || msg.messageType == Message.WITHDRAW_MSG) {
			processUpdate((UWMessage)msg);
		}
//		else if(msg.messageType == Message.WITHDRAW_MSG) {
//		processWithdrawal((WithdrawMessage)msg);
//		}
		else if(msg.messageType == Message.SNAPSHOT_MSG) {
			processSnapshotMsg((SnapshotMessage)msg);
		}
		else if(msg.messageType == Message.FLOOD_MSG) {
			processFloodMsg((FloodMessage)msg);
		}
		else if(msg.messageType == Message.CONTROL_MSG) {
			processControlMsg((ControlMessage)msg);
		}
	}

	/**
	 * This function is called when a control message is received. It instructs
	 * the REPLACEMENT_AS to send out an update or a withdrawal for some destination to a
	 * particular peer.
	 * 
	 * these are broken, passthrough information nnot pasthed through, we don't use that for our experiments it doesn't matter
	 * just know this
	 * @param message The control message
	 */
	private void processControlMsg(ControlMessage m) {
		int type = m.controlType;
		int dst = m.dest;
		int peer = m.announceTo;
		
		ArrayList<Integer> prefix = new ArrayList<Integer>();
		prefix.add(dst);
		
		UWMessage uwMsg;

		// if no path, ignore -- since we store no path to self, need to check
		IA p = bestPath.get(dst);
		if(dst == asn) { // if announcement/withdrawal of self
			p = new IA(new RootCause(asn, currentUpdate++, asn));
		}
		if(p == null || p.getPath() == null) {
			return;
		}
		
		if(type == ControlMessage.ANNOUNCE) { // need to announce current best path
			IA copy = new IA(p);//new IA(p.getPath(), p.getRootCause());
			// need to always send a copy!
			copy.prepend(asn);
	//		passThrough.attachPassthrough(copy); //[ADDED]
			uwMsg = new UpdateMessage(asn, prefix, copy); // TODO: Do we need to change root cause?
		}
		else { // WITHDRAW
			uwMsg = new WithdrawMessage(asn, prefix, new RootCause(asn, currentUpdate++, dst)); //[COMMENT] need to change?
		}
		
		Simulator.addEvent( new Event(Simulator.getTime() + LINK_DELAY,
										peer, uwMsg));
		
	}

	/**
	 * This function is called when a flood packet is received. A flood
	 * packet contains the incomplete update information for a particular
	 * REPLACEMENT_AS. We add the information to our set, and forward the packet to
	 * all the neighbors except the one I received it from.
	 * 
	 * We also need to keep track of the flood history so that we don't
	 * send the same information more than once.
	 * 
	 * @param msg The flood packet containing 'info abt incomplete updates'
	 */
	private void processFloodMsg(FloodMessage msg) {
		if(floodsSeen.contains(msg.asn)) {
			return;
		}

		floodsSeen.add(msg.asn);
		floodsIncomplete.addAll(msg.incompleteUpdates);
		floodsConditional.addAll(msg.condIncompleteUpdates);
		int peerAsn;
		for(int i=0; i<customers.size(); i++) {
			peerAsn = customers.get(i);
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					peerAsn, 
					msg));
		}
		for(int i=0; i<providers.size(); i++) {
			peerAsn = providers.get(i);
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					peerAsn, 
					msg));
		}
		for(int i=0; i<peers.size(); i++) {
			peerAsn = peers.get(i);
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					peerAsn, 
					msg));
		}


	}

	// For now, we use the generic message since the snapshot
	// message requires no additional information
	private void processSnapshotMsg(SnapshotMessage msg) {
		Integer peer = msg.asn;
		// if we are not waiting for any pendingResponses, it probably means that
		// we are yet to start the snapshot
		// The first time we get a snapshot msg, we save the local state
		// and start recording all channels and send messages to our neighbors
		if(pendingResponses == 0) { // new snapshot
			// 1. Save the state
			updatesInTransit = new HashSet<RootCause>(getUpdatesInTransit());
			// 2. Start recording all channels(except where marker came from) and send marker to all neighbors
			floodMarkerAndStartRecording(peer);
		}
		else { // snapshot running; incoming marker from nbr
			// 1. Stop recording that channel
			recordingPeer.put(peer, false);
			pendingResponses--;
		}
		// if pendingResponses == 0, snapshot done for me
		// TODO -- once snapshot is completed, do the cleanup? and send out flood packet
		if(pendingResponses == 0) {
			// the set of incomplete updates are those that were in transit on the channel
			// and those that are waiting for MRAI timers
			ArrayList<RootCause> inTransit = new ArrayList<RootCause>(updatesInTransit); // on the channel
//			inTransit.addAll(getUpdatesInTransit()); // waiting for MRAI

			ArrayList<UpdateDependency> condIncomplete = new ArrayList<UpdateDependency>();
			// now we need all the conditionally complete updates, and we are ready to send the flood packet
			for(Iterator<RIBHist> it = dstRIBHistMap.values().iterator(); it.hasNext(); ) {
				condIncomplete.addAll(it.next().condInUpdates);
			}
			// send out the flood message ... 
//			processFloodMsg(new FloodMessage(asn, inTransit, condIncomplete));
			Simulator.debug("REPLACEMENT_AS" + asn + ": nonFinished = " + nonFinishedUpdates );
			Simulator.recordFlood(asn, new FloodMessage(asn, inTransit, condIncomplete), nonFinishedUpdates);
//			HashMap<Short,ArrayList<RootCause>> updateSequence = new HashMap<Short,ArrayList<RootCause>>();
//			for(Iterator<RIBHist> it = dstRIBHistMap.values().iterator(); it.hasNext(); ) {
//			RIBHist rh = it.next();
//			updateSequence.put(rh.destAS, rh.updateSequence);
//			}
		}

	}

	private void floodMarkerAndStartRecording(Integer peer) {
		int peerAsn;
		
		for(int i=0; i<customers.size(); i++) {
			peerAsn = customers.get(i);
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					peerAsn, 
					new SnapshotMessage(asn)));
			if(peerAsn != peer) { // start recording this channel
				recordingPeer.put(peerAsn, true);
				pendingResponses++;
			}
		}
		for(int i=0; i<providers.size(); i++) {
			peerAsn = providers.get(i);
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					peerAsn, 
					new SnapshotMessage(asn)));
			if(peerAsn != peer) { // start recording this channel
				recordingPeer.put(peerAsn, true);
				pendingResponses++;
			}
		}
		for(int i=0; i<peers.size(); i++) {
			peerAsn = peers.get(i);
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					peerAsn, 
					new SnapshotMessage(asn)));
			if(peerAsn != peer) { // start recording this channel
				recordingPeer.put(peerAsn, true);
				pendingResponses++;
			}
		}
	}

	/**
	 * This method is used for sending updates to a peer. It is called
	 * when the MRAI timer has expired. It sends the updates only if the
	 * MRAI timer has indeed expired.
	 * @param peer
	 */
	private void sendUpdatesToPeer(int peer) {
		// the set of prefixes with the same AS Path
		// right now, we have just the dest AS as the prefix
		ArrayList<Integer> prefixList; 
		if(mraiRunning.get(peer))
			return;

		if(!pendingUpdates.containsKey(peer)) {
			// there are no updates for this peer
			return;
		}
		HashMap<Integer, IA> dstPathMap = pendingUpdates.get(peer);
		List<IA> updates = new ArrayList<IA>(dstPathMap.values());

		for(Iterator<IA> it = updates.iterator(); it.hasNext(); ) {
			IA p = it.next();

			prefixList = new ArrayList<Integer>();
			prefixList.add(p.getDest());

			Event e = new Event(Simulator.getTime() + LINK_DELAY,
					peer,
					new UpdateMessage(asn, prefixList, p));
			Simulator.addEvent(e);
		}

		// since all the updates have been processed, clear the list
		pendingUpdates.remove(peer);

		// start the next round of the MRAI timer
		Simulator.addEvent(new Event(Simulator.getTime() + mraiValue,
				asn, peer));
		mraiRunning.put(peer, true);
	}

	public Collection<IA> getAllPaths(int dst) {
		if(!ribIn.containsKey(dst)) {
			System.err.println("No path from " + asn + " to " + dst);
			return null;
		}
		return (ribIn.get(dst).values());
	}
	
	
	/**
	 * Updates number of paths associated with advertisement before
	 * placing it in FIB.
	 *  This is a method that requires 1 pop per AS
	 * Therefore, should be done before putting it in bestpath datastructure
	 * @param newBestPath - the path received that might need to be updated
	 */
	private IA postProcessBestpath(IA newBestPath)
	{
		if (customers.size() != 0)
		{
			return newBestPath;
		}
		
		LinkedList<Integer> path = (LinkedList<Integer>) newBestPath.getPath().clone(); //cloning not likely necessary because
		//it is no longer mutated in findislandedge
		int islandEdge = findIslandEdge(path);
		long transitPaths = 1;
		if(islandEdge != -1)
		{
			transitPaths = numPathsToDest.get(islandEdge);
		}
		long pathsToDest = numPathsToDest.get(newBestPath.getDest());
		boolean directMultiPath = pathsToDest > 1 ? true : false; //if we are directly connected and have multiple paths to destination set to true
		long inAdvertNumPaths = 1; //number of paths in advertisement
		PoPTuple firstTuple = neighborMetric.get(newBestPath.getFirstHop()).keySet().iterator().next(); //does not work with multipop!!!!
		String[] inAdvertPathsToDest = AS.getProtoProps(newBestPath, firstTuple, new Protocol(AS.REPLACEMENT_AS));
		if(inAdvertPathsToDest != null)
		{
			inAdvertNumPaths = Long.valueOf(inAdvertPathsToDest[0]);
		}
		if(!directMultiPath){ //untested changes
			pathsToDest = transitPaths * inAdvertNumPaths;
		}
		String pathAttribute = String.valueOf(pathsToDest);
		for(IAInfo bpInfo : newBestPath.popCosts.values())
		{
			try {
				bpInfo.setProtocolPathAttribute(pathAttribute.getBytes("UTF-8"), new Protocol(AS.REPLACEMENT_AS), newBestPath.getPath());
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	return newBestPath;
		
	}
	
	/**
	 * This method is called when an update is received. The function looks
	 * at the new path, and adds it to the RIB-In (replacing the old path
	 * received from this peer). If this is better than the current best path
	 * to the destination, it is marked as the bestPath, and propagated
	 * to neighbors. If this path replaces the best path, then a new best path
	 * is found, and propagated to neighbors. Withdrawals may have to be sent
	 * if the new best path isn't to be advertised to all the neighbors the old
	 * path was advertised to. Also the pending updates for the old path should
	 * be flushed
	 * 
	 * Whichever path is being propagated to the neighbors is added to my RIB-Hist
	 * @param m
	 */
	private void processUpdate(UWMessage m) {
		
		if(m.asPath == null) { // invalid message!
			return;
		}
		// The AS we received the message from
		int nextHop = m.asn; 
		IA p = m.asPath;
		int dst = p.getRootCause().getDest();

		if(p.contains(asn)) { // path has loop, consider it a withdrawal from that neighbor!
//			removePathFromRIBIn(dst, nextHop);
//			return;
			p.setPath_Legacy(null);
		}

		// if withdrawal not for a path rcvd previously, ignore!
		if(p.getPath()==null) {
			// if i have no path to dst or no path through next hop, ignore this
			if(!ribIn.containsKey(dst) || !ribIn.get(dst).containsKey(nextHop)) {
				return;
			}
		}
		if(recordingPeer.containsKey(nextHop) && recordingPeer.get(nextHop)) {
			// since we are recording this peer, all updates are considered incomplete
			// maybe we can optimize and consider only those updates which aren't loops :)
			updatesInTransit.add(m.asPath.getRootCause());
//			Simulator.debug("BGP_AS" + asn + ": Recorded in transit " + m.asPath.rc);
		}
		
		IA bp = bestPath.get(dst);
		if(p.getPath()!=null) { // advertisement
			addPathToRib(p);
		} else { // withdrawal
			removePathFromRIBIn(dst, nextHop);
		}
		if(!dstRIBHistMap.containsKey(dst)) {
			dstRIBHistMap.put(dst, new RIBHist(dst));
		}
		// TOM
		// Add the update to the sequence of unfinished updates for this dest
		dstRIBHistMap.get(dst).addToSequence(p.getRootCause());
		nonFinishedUpdates.add(p.getRootCause());
		// Simulator.debug("REPLACEMENT_AS" + asn + ": Adding to non-finished " + p.rc);

		// check if the path is better than the current best path
		if( bp==null || isBetter(p, bp, false) ) {
			// we need to install this as our best path and send an update
			// to all our peers
		    Simulator.debug("REPLACEMENT_AS" + asn + ": Added best path to dst AS" + dst + ": " + p.getPath());
		    p = postProcessBestpath(p);
			bestPath.put(dst, p);

			addPathToUpdates(p, Simulator.otherTimers);

			dstRIBHistMap.get(dst).addUpdateToHistory(p, nextHop);
			sendWithdrawalsIfNecessary(bp, p);
			Simulator.changedPath(asn, dst, bp, p);
		}
		else if(bp.getFirstHop() == nextHop) { // the current best path has been replaced by this one
			// this could also be a withdrawal of the current best path
			// we need to find the new best path
			if(p.getPath()==null) { // this is a withdrawal of our active path .. so we are temporarily disconnected
				Simulator.addAffected(asn);
				passThrough.removeFromDatabase(IA.pathToKey(bp.getPath())); //[COMMENT] added if our best path is being withdrawn, remove it from passthroughdatabase
			}
			
			ArrayList<IA> allPathsToDst = new ArrayList<IA>(ribIn.get(dst).values());
			IA newBestPath = findBestPath(allPathsToDst);
			// what if there is no path to destination? newBestPath.asPath = null
			if(newBestPath == null || newBestPath.getPath() == null) {
				newBestPath = p; // this ensures that we forward 'this' withdrawal and not re-root it
			}
			p = postProcessBestpath(p);
			bestPath.put(dst, newBestPath);
			Simulator.changedPath(asn, dst, bp, newBestPath);
			Simulator.debug("REPLACEMENT_AS" + asn + ": new Path = " + newBestPath.getPath());
			
			// if newBestPath is completed earlier, then re-root the update
			if(!nonFinishedUpdates.contains(newBestPath.getRootCause())) {
				RootCause newRC = new RootCause(asn, currentUpdate++, dst);
				nonFinishedUpdates.add(newRC);
				// Simulator.debug("BGP_AS" + asn + ": Adding to non-finished " + newRC);
				newBestPath.setRootCause(newRC);
			}
			RIBHist temp = dstRIBHistMap.get(dst);
			if(temp.hotNeighbors.contains(nextHop)) {
				temp.addCondIncomplete(p.getRootCause(), newBestPath.getRootCause()); // if bp is complete, this can be ignored?
			}
			
			// if newBestPath is conditionally incomplete, then remove it from that set
			// since it is now actively forwarded.
			dstRIBHistMap.get(dst).removeConditional(newBestPath.getRootCause());
			dstRIBHistMap.get(dst).addUpdateToHistory(newBestPath, nextHop);

			if(newBestPath.getPath() != null) {
//				newBestPath = passThrough.attachPassthrough(newBestPath);
				addPathToUpdates(newBestPath, Simulator.otherTimers);
				sendWithdrawalsIfNecessary(bp, newBestPath);
				dstRIBHistMap.get(dst).addToSequence(newBestPath.getRootCause());
				
			}
			else { // need to send withdrawals
				sendWithdrawals(bp, p.getRootCause());
			}
		}
		else { // this doesn't affect the current best path, so ignore
			// however, if this update is from a 'hot' neighbor, mark it as conditionally incomplete
			RIBHist temp = dstRIBHistMap.get(dst);
			if(temp.hotNeighbors.contains(nextHop)) {
				temp.addCondIncomplete(p.getRootCause(), bp.getRootCause()); // if bp is complete, this can be ignored?
			}
		}
	}
	
	public int getNextHop(int dst) {
		if(bestPath.containsKey(dst) && bestPath.get(dst).getPath() != null) {
			return bestPath.get(dst).getFirstHop();
		}
		return -1;
	}
//	// need to change withdrawals so that they can be processed 
//	// just like regular updates
//	// *** DO NOT USE THIS FUNCTION ***** 
//	private void processWithdrawal(WithdrawMessage m) {
//		//System.err.println("Processing withdrawal!!");
//		short nextHop = m.asn;
//		for(int i=0; i<m.prefixes.length; i++) {
//			short dst = m.prefixes[i];
//			removePathFromRIBIn(dst, nextHop);
////			dstRIBHistMap.get(dst).addToSequence(m.asPath.rc);
//
//			Path oldBestPath = bestPath.get(dst);
//			// check if the best path was withdrawn : otherwise ignore
//			if( oldBestPath.getFirstHop() == nextHop ) {
//				// find the next best path
//				ArrayList<Path> allPathsToDst = new ArrayList<Path>(ribIn.get(dst).values());
//				Path newBestPath = findBestPath(allPathsToDst);
//				if(newBestPath!=null) {
//					bestPath.put(dst, newBestPath);
//					addPathToUpdates(newBestPath, true);
//				}
//				else { // there is no path to the destination, so send out a withdrawal to all peers to whom it was advertised
//					sendWithdrawals(oldBestPath, m.asPath.rc);
//				}
//			}
//			else { // the best path is still intact, so nothing to be done
//
//			}
//		}
//	}

	/**
	 * This function is called when a new path has been chosen. If the new 
	 * path is being advertised to only a subset of the neighbors to whom the old
	 * path was announced, then the rest of them need to be sent withdrawals 
	 * sourced by me. If I'm sending a withdrawal to a neighbor, I should remove any pending
	 * updates to that neighbor
	 * 
	 * @param oldPath The old path which is getting replaced
	 * @param newPath The new best path
	 */
	private void sendWithdrawalsIfNecessary(IA oldPath, IA newPath) {
		if(oldPath == null || oldPath.getPath() == null) { // didn't have a path before
			return; // no need to send withdrawals
		}

		if(newPath.getPath() == null) {
			return;
		}

		// System.out.println("REPLACEMENT_AS" + asn + " might need to send: " + newPath.path);

		int oldType = neighborMap.get(oldPath.getFirstHop());
		int newType = neighborMap.get(newPath.getFirstHop());
		int nextHop = newPath.getFirstHop();
		
		// i have to send withdrawals only if my previous path was a customer
		// path and the current isn't. if i have arbitrary policy, then
		// i will have to store the set of nieghbors to whom i advertised each
		// path.
		if(oldType == CUSTOMER && newType != CUSTOMER) {
		    //		    Simulator.addDiscon(asn);
		    // System.out.println("need to send");
			// the old path was announced to providers and peers, but the new
			// one will be announced only to customers, so send withdrawal to 
			// providers and peers -- except nextHop. We will announce the path
			// to the next hop ... might be useful in later schemes
			ArrayList<Integer> wp = new ArrayList<Integer>();
			wp.add(oldPath.getDest());
			WithdrawMessage withdrawalMsg = new WithdrawMessage(asn, wp, 
					new RootCause(asn, currentUpdate++, oldPath.getDest()));
			for(int i=0; i<providers.size(); i++) {
				if(nextHop == providers.get(i))
					continue;
				Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
						providers.get(i),
						withdrawalMsg));
				removePendingUpdateToPeerForDst(providers.get(i), oldPath.getDest());
			}
			for(int i=0; i<peers.size(); i++) {
				if(nextHop == peers.get(i))
					continue;
				Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
						peers.get(i),
						withdrawalMsg));
				removePendingUpdateToPeerForDst(peers.get(i), oldPath.getDest());
			}
		}
	}

	/**
	 * If I am sending a withdrawal to a peer for a destination, then I should also 
	 * remove any updates (pending, due to timer) to that peer for that destination
	 * @param peer The peer to whom I am sending a withdrawal
	 * @param dest The destination being withdrawn
	 */
	private void removePendingUpdateToPeerForDst(Integer peer, int dest) {
		// TODO Auto-generated method stub
		if(!pendingUpdates.containsKey(peer)) { // no pending updates, so return
			return;
		}
		pendingUpdates.get(peer).remove(dest); // remove update for that destination
	}

	// this is useful for forwarding withdrawals
	// in order to originate withdrawals, use the function withdrawSelf
	private void sendWithdrawals(IA oldBestPath, RootCause cause) {
		
		int nh = oldBestPath.getFirstHop();
		int	nhType = neighborMap.get(nh);
		ArrayList<Integer> wp = new ArrayList<Integer>();
		int dest = oldBestPath.getDest();
		wp.add(dest);
		Simulator.addDiscon(asn);

		Message withdrawalMsg = new WithdrawMessage(asn, wp, cause );

		if(nhType == PROVIDER || nhType == PEER) { // path was announced only to customers and to nextHop
			for(int i=0; i<customers.size(); i++) {
				Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
						customers.get(i),
						withdrawalMsg));
				removePendingUpdateToPeerForDst(customers.get(i), dest);
			}
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					nh,
					withdrawalMsg));
			removePendingUpdateToPeerForDst(nh, dest);
		}
		else { // customer path, so announce to all
			for(int i=0; i<customers.size(); i++) {
				Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
						customers.get(i),
						withdrawalMsg));
				removePendingUpdateToPeerForDst(customers.get(i), dest);
			}
			for(int i=0; i<providers.size(); i++) {
				Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
						providers.get(i),
						withdrawalMsg));
				removePendingUpdateToPeerForDst(providers.get(i), dest);
			}
			for(int i=0; i<peers.size(); i++) {
				Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
						peers.get(i),
						withdrawalMsg));
				removePendingUpdateToPeerForDst(peers.get(i), dest);
			}
		}

	}

	// Withdraw self from all neighbors. Used to simulate link failures?
	private void withdrawSelf() {
		ArrayList<Integer> wp = new ArrayList<Integer>();
		wp.add(asn);

		Message withdrawalMsg = new WithdrawMessage(asn, wp, new RootCause(asn, currentUpdate++, asn) );

		for(int i=0; i<customers.size(); i++) {
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					customers.get(i),
					withdrawalMsg));
		}
		for(int i=0; i<providers.size(); i++) {
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					providers.get(i),
					withdrawalMsg));
		}
		for(int i=0; i<peers.size(); i++) {
			Simulator.addEvent(new Event(Simulator.getTime() + LINK_DELAY,
					peers.get(i),
					withdrawalMsg));
		}

	}

	private IA findBestPath(ArrayList<IA> allPathsToDst) {

		if(allPathsToDst == null || allPathsToDst.size() == 0) { // no path to dst
			return null;
		}

		IA best = allPathsToDst.get(0);
		for(int i=0; i<allPathsToDst.size(); i++) {
			if( isBetter(allPathsToDst.get(i), best, false) ) {
				best = allPathsToDst.get(i);
			}
		}
		return best;
	}

	/**
	 * This function determines if the first path is better than the second
	 * @param p1 The first path
	 * @param p2 The second path
	 * 
	 * @return true 	if p1 is better than p2
	 * 		   false 	otherwise 
	 */
	public boolean isBetter(IA p1, IA p2, boolean dampenBookKeep) {
		// this is where we can apply the policy
		// for now, we just follow customer > peer > provider
		// and in case of a tie, shortest path length
		// and then break tie by lowest AS number for next hop

		if(p2 == null || p2.getPath() == null) 
			return true;
		if(p1 == null || p1.getPath() == null)
			return false;
		
		if(dampenBookKeep)
		{
			int dampen = dampening(p1, p2);
			if(dampen > -1)
			{
				return dampen == 1;
			}
		}
		
		
		int p1nh = p1.getFirstHop();
		int p2nh = p2.getFirstHop();
		
		int p1nhType = neighborMap.get(p1nh);
		int p2nhType = neighborMap.get(p2nh);

		if( p1nhType < p2nhType ) { //
			return true;
		}
		else if(p1nhType > p2nhType) {
			return false;
		}
		else { // both are similar, so look at path length
			if(p1.getPath().size() < p2.getPath().size()) {
				return true;
			}
			else if( p1.getPath().size() > p2.getPath().size() ) {
				return false;
			}
			// else .. break tie using AS number
			else if (p1.getFirstHop() < p2.getFirstHop())
			{
				return true;
			}
				
			
		}
		return false;
	}

	/**
	 * This function returns the set of pending updates.
	 * For each neighbor, it gets the set of pending updates waiting for the MRAI timer
	 * and adds them to the set
	 * 
	 * @return A Set containing the incomplete updates for me
	 */
	private Set<RootCause> getUpdatesInTransit() {
		HashSet<RootCause> incompleteUpdates = new HashSet<RootCause>();

		for(int i=0; i<customers.size(); i++) {
			incompleteUpdates.addAll(getPendingUpdatesForPeer(customers.get(i)));
		}
		for(int i=0; i<providers.size(); i++) {
			incompleteUpdates.addAll(getPendingUpdatesForPeer(providers.get(i)));
		}
		for(int i=0; i<peers.size(); i++) {
			incompleteUpdates.addAll(getPendingUpdatesForPeer(peers.get(i)));
		}
		return incompleteUpdates;
	}

	/**
	 * Convenience function: This function returns the set of incomplete updates (identified by their RC)
	 * for this particular peer
	 * @param peer The peer whose incomplete updates we want
	 * @return The set of RCs for incomplete updates
	 */
	private Collection<RootCause> getPendingUpdatesForPeer(Integer peer) {

		HashSet<RootCause> updatesRC = new HashSet<RootCause>();
		if(!pendingUpdates.containsKey(peer))
			return updatesRC;

		Collection<IA> dstPaths = pendingUpdates.get(peer).values();
		for(Iterator<IA> it = dstPaths.iterator(); it.hasNext();) {
			updatesRC.add(it.next().getRootCause());
		}
		return updatesRC;
	}

	/**
	 * This function is called when a new epoch begins. It prunes the nonFinishedUpdates to
	 * remove all updates that are deemed 'complete'
	 * @param incompleteUpdates The set of incomplete updates computed after the flooding is done
	 */
	private void retainIncompleteUpdates(Set<RootCause> incompleteUpdates) {
		nonFinishedUpdates.retainAll(incompleteUpdates);
	}



	/**
	 * This function takes in SFT, the RIBHist and set of incomplete updates to produce SFT'
	 * @param oldTable
	 * @param incompleteUpdates
	 * @return The new stable forwarding table SFT'
	 */
	private HashMap<Integer, IA> computeNewForwardingTable(HashMap<Integer, IA> oldTable, Set<RootCause> incompleteUpdates ) {
		HashMap<Integer, IA> newTable = new HashMap<Integer, IA>(oldTable);
		// the set of destinations that have pending updates
		Set<Integer> updatedDests = new HashSet<Integer>(dstRIBHistMap.keySet());
		// for each destination, pick the last update that is not incomplete
		// discard all updates prior to that one, and remove them from your set
		// of incomplete updates.
		for(Iterator<Integer> it = updatedDests.iterator(); it.hasNext();) {
			int dst = it.next();
			IA p = pickLastCompleteUpdate(dst, incompleteUpdates);
			if(p!=null) { // we found an update that completed
				newTable.put(dst, p);
			}
		}
		return newTable;
	}

	/**
	 * This function picks the last complete update from your RIBHist for a particular
	 * destination. All previous updates are discarded 
	 * 
	 * @param dst The destination
	 * @param incompleteUpdates The set of incomplete updates (identified by RootCause)
	 * @return The latest stable path
	 * 		   null if none of the updates completed
	 */
	private IA pickLastCompleteUpdate(int dst, Set<RootCause> incompleteUpdates) {
		RIBHist temp = dstRIBHistMap.get(dst);
		ArrayList<IA> history = temp.history;
		int size = history.size();
		int latest = -1;
		for(int i=size-1; i>=0; i--) {
			RootCause rc = history.get(i).getRootCause();
			if(!incompleteUpdates.contains(rc)) { // this means rc is complete and can be applied :)
				latest = i;
				break;
			}
		}
		// we need to remove all updates before 'i' 
		// and mark them complete (since i don't care anymore) ???? IGNORE
		// i can mark them complete only if i don't have them in my RIB-In
		// removing them is only a space efficiency, since I remove them
		// only if i can no longer choose them. still is correct!
		for(int i=0; i<latest; i++) {
			// get the first element from history, and remove it
			// nonFinishedUpdates.remove(history.get(0).rc);
			history.remove(0);
		}
		if(latest != -1)
			return history.get(latest);

		return null;
	}

	/**
	 * This function is called once I have completed the flooding phase.
	 * At this stage, everyone has the same information as me. I need to perform
	 * the switch to the new routing table and also perform some cleanup
	 * 
	 * The list of things to be done include:
	 * 1. Compute the closure of all incomplete updates (this will be used to decide which ones to apply)
	 * 2. Remove all completed updates from the set of non-finished updates
	 * 3. Remove the label from a conditionally incomplete update if the update it depended on completed
	 * 4. Clean up updatesInTransit
	 * 5. We need to carry forward the set of conditionally incomplete and nonFinished into next epoch
	 * 6. Compute new forwarding table
	 *
	 */
	public void floodCompleted(Set<RootCause> allIncomplete) {
		// Step 1
//		Set<RootCause> allIncomplete = computeAllIncompleteUpdates(floodsIncomplete, floodsConditional);

		// Step 2
		// this prunes out the nonFinishedUpdates to remove all those that finished
		retainIncompleteUpdates(allIncomplete);

		// Step 3
		cleanUpConditionallyIncompleteSet(allIncomplete);

		// Step 4
		updatesInTransit.clear();
		floodsSeen.clear();
		floodsIncomplete.clear();
		floodsConditional.clear();
		currentEpoch++;

		// Step 5
		// Step 2 and Step 3 take care of this

		// Step 6 
		//TODO
//		SFT = SFTp;
//		SFTp = computeNewForwardingTable(SFT, allIncomplete);
	}

	/**
	 * This function goes through all the UpdateDependencies in all the RIBHists to remove those where the
	 * cause completed.
	 * @param allIncomplete The global set of all incomplete updates
	 */
	private void cleanUpConditionallyIncompleteSet(Set<RootCause> allIncomplete) {
		// TODO might need to debug: check for removal of elements from arraylist while iterating through it
		for(Iterator<RIBHist> it = dstRIBHistMap.values().iterator(); it.hasNext(); ) {
			ArrayList<UpdateDependency> temp = it.next().condInUpdates;
			for(int i=0; i<temp.size(); i++) {
				if(!allIncomplete.contains(temp.get(i).dependsOn)) { // the cause completed, so remove dependency
					temp.remove(i);
					i--;
				}
			}
		}
	}

	/*public String showNeighbors() {
		String nbrs = "Neighbors of REPLACEMENT_AS" + asn + " Prov: " + providers + " Cust: " + customers + " Peer: " + peers;
		return nbrs;
	}*/

	public String showFwdTable() {
		String table = "FWD_TABLE : REPLACEMENT_AS" + asn + " #paths = " + bestPath.size() + "\n";
		for(Iterator<IA> it = bestPath.values().iterator(); it.hasNext();) {
			IA bestPath = it.next();
			table += bestPath.getPath() +  " cost: " + bestPath.getTrueCost() + "\n";
		/*	byte[] wiserValues = bestPath.getProtocolPathAttribute(new Protocol(AS.WISER), bestPath.getPath());
			if(wiserValues[0] != (byte) 0xFF)
			{
				try {
					table += new String(wiserValues, "UTF-8") + '\n';
				} catch (UnsupportedEncodingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			*/
		}
		return table;
	}
	
	public void clearBookKeeping(){
	//	pendingUpdates.clear();
	//	dstRIBHistMap.clear();
	//	mraiRunning.clear();
	//	ribIn.clear();
	//	super.passThrough.clear();
	}

	@Override
	public PoPTuple tupleChosen(IA advert) {
		//choose a tuple based on lowest MED
		int nh = advert.getFirstHop();
		float lowestMED = Float.MAX_VALUE;			
		long trueCostInc = 0;
		PoPTuple chosenTuple = null;
		for(PoPTuple tuple : neighborMetric.get(nh).keySet()){
			float latency = neighborMetric.get(nh).get(tuple).get(AS.COST_METRIC);
			if(latency < lowestMED)
			{
				//			trueCostInc = latency;
				chosenTuple = tuple;
				lowestMED = latency;
			}
		}
		return chosenTuple;
		
	}


}
