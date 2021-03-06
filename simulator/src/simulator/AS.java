/**
 * 
 */
package simulator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

import integratedAdvertisement.IA;
import integratedAdvertisement.PassThrough;
import integratedAdvertisement.RootCause;

/**
 * 
 * @author David
 *
 */
public abstract class AS {

	static final int PROVIDER = 1;
	static final int PEER = 0;
	static final int CUSTOMER = -1;
	static final int SIBLING = 2;
	
	static final int BGP = 500;
	static final int WISER = 501;
	static final int TRANSIT = 502;
	
		/** Set of neighbors that are customers */
	ArrayList<Integer> customers = new ArrayList<Integer>();

	/** Set of neighbors that are providers */
	ArrayList<Integer> providers = new ArrayList<Integer>();

	/** Set of neighbors that are peers */
	ArrayList<Integer> peers = new ArrayList<Integer>();
	
	HashMap<Integer, Integer> neighborLatency = new HashMap<Integer, Integer>();
	
	PassThrough passThrough = new PassThrough(); //enable passthroughfunctionality for AS
	
	/** Mapping of neighbor to relationship */
	HashMap<Integer, Integer> neighborMap = new HashMap<Integer, Integer>();

	// we also need to store all the paths received from neighbors for each
	// destination. this would be our rib-in. the rib-in is implemented as
	// a pair of nested hash tables: hashed on <prefix, neighbor>
	HashMap<Integer, HashMap<Integer,IA>> ribIn = new HashMap<Integer, HashMap<Integer, IA>>();

	
	public Integer asn;
	
	public Integer protocol;
	
	HashMap<Integer,IA> bestPath = new HashMap<Integer, IA>();

	public abstract boolean isBetter(IA p1, IA p2);

	public abstract int getNextHop(int dst);

	public abstract void floodCompleted(Set<RootCause> allIncomplete);

	public abstract void handleEvent(Event e);

	public abstract void announceSelf();

	public abstract void addCustomer(int as1);

	public abstract void addPeer(int as2);

	public abstract void addProvider(int as2);
	
	//adds latency between yourself and a neighboring as
	public abstract void addLatency(int as, int latency);

	//gets all the paths for a particular destination (in the ribIn)
	public abstract Collection<IA> getAllPaths(int dst);

	//resets the as
	public abstract void RESET();
	
	public String showNeighbors() {
		String nbrs = "Neighbors of BGP_AS" + asn + " Prov: " + providers + " Cust: " + customers + " Peer: " + peers;
		return nbrs;
	}
	
	public abstract String showFwdTable();


}
