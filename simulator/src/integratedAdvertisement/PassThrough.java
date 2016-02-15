/**
 * 
 */
package integratedAdvertisement;

import java.util.HashMap;
import java.util.LinkedList;

import simulator.AS.PoPTuple;

/**
 * Class that holds passthrough information and attaches passthrough information
 * to advertisements
 * 
 * @author David
 *
 */
public class PassThrough {
	
	private static final boolean USE_PASSTHROUGH = true; //used for contiguous deployment experiments

	// keyed on pathTokey. links to aggregated values that were received. IA
	// value contains info for a single path, for each poitn of presence that we are connected to 
	//on the next hop of that path
	HashMap<String, HashMap<PoPTuple, IAInfo>> passThroughDatabase = new HashMap<String, HashMap<PoPTuple, IAInfo>>();

	public PassThrough() {

	}
	
	public void clear(){
		passThroughDatabase.clear();
	}

	// attach passthrough information based on an advertisement that is about to
	// go out
	/**
	 * method that attaches passthrough information based on an advertisment
	 * that is about to out that is, that is, the path is not going to change
	 * 
	 * @param advertisement
	 *            the advertisement to attach passthrough information to (this
	 *            object is mutated)
	 * @return advertisement with passthrough information attached, this is
	 *         redundant given how java does references
	 */
	public IA attachPassthrough(IA advertisement, PoPTuple chosenTuple) {
		if(!USE_PASSTHROUGH)
		{
			return advertisement; //don't use passthrough, return unmodified advert (this is redundent)
		}
		// for each path, attach values from passthroughdatabase
		for (String pathKey : advertisement.getPathKeys()) {
			// grab the path, and attach passthrough informatin based on next
			// hop. this should only be called when
			// you have a fully formed path ready to be advertised
			LinkedList<Integer> path = (LinkedList<Integer>) advertisement
					.getPath(pathKey).clone();
			path.remove(); //remove prepended self so we can index into db on the path received
			String passThroughPathKey = IA.pathToKey(path);
			// merge pasthrough information into advertisement if there is
			// somethign in database
			// only does it for path attributes, can be extended to do edge and
			// as descriptors
			if (passThroughDatabase.containsKey(passThroughPathKey)) {
				IAInfo passThroughInfo = passThroughDatabase
						.get(passThroughPathKey).get(chosenTuple);
				//the same passthrough info is going out to all usTOThem PoPs		
				for(PoPTuple usToThemAdvert : advertisement.popCosts.keySet()){
					Values val1 = advertisement.getPathAttributes(usToThemAdvert, pathKey);
					if (val1 == null) {
						val1 = new Values();
					}
					Values val2 = passThroughInfo
							.getPathAttributes(passThroughPathKey);
					Values mergedVal = mergeValues(val1, val2); //val1 contains updated info, so merge val2 into it as to not ruin it
					advertisement.setPathAttributes(usToThemAdvert, mergedVal,
							advertisement.getPath(pathKey));
				}

			}
		}
		return advertisement; // REDUNDANT, advertisement is changed directly,
								// may change later
	}

	/**
	 * method that adds a received advertisement to the passthroughdatabase
	 * merges the information in this advertisement with that already contained
	 * in the database (if there is one there) we overwrite exhisting values in
	 * the database from this advertisement if there is some
	 * 
	 * @param receivedAdvert
	 *            the advertismeent recieved from a neighbor
	 */
	public void addToDatabase(IA receivedAdvert) {
		//for each path
		for (String key : receivedAdvert.getPathKeys()) {
			//entry to add to database
			HashMap<PoPTuple, IAInfo> toDatabase = passThroughDatabase.containsKey(key) ? passThroughDatabase
					.get(key) : new HashMap<PoPTuple, IAInfo>(); // if the passthrough db contains information about 
																//that path then get it, otherwise it doesn't exist, then create something blank
					//for each poptuple in the advertisement
					for(PoPTuple themToUs : receivedAdvert.popCosts.keySet()){
						PoPTuple usToThem = new PoPTuple(themToUs.pop2, themToUs.pop1); //reverse it so that it is an "us to them" poptuple
						//if we don't have a tuple for this, insert a blank one
						if(!toDatabase.containsKey(usToThem)) //if there is no such info in the database, add it with blank info
						{
							toDatabase.put(usToThem, new IAInfo());
						}
						
						Values val1 = toDatabase.get(usToThem).getPathAttributes(key); //grab the info that is to be passedthrough (will be null if none exists)				
						Values val2 = receivedAdvert.getPathAttributes(themToUs, key); //grab the advert info received from the point of PoP under examination
						// if either val1 or val2 is null, give it a fresh blank vlaues.
						val1 = val1 == null ? new Values() : val1;
						val2 = val2 == null ? new Values() : val2;
						toDatabase.get(usToThem).pathValues.put(IA.pathToKey(receivedAdvert.getPath()), mergeValues(val2, val1)); // merge val2 first because we want
						// to overwrite old received values
						// in advert alrady in database
					}
					passThroughDatabase.put(key, toDatabase);
		}

	}

	// removes path from database. Used with path is withdrawn by peer. should
	// use key formed by pathToKey
	/**
	 * method that removes a path and associated passthrough information (based
	 * on path key generaated by IA.pathToKey(path)) from passthroug database
	 * 
	 * @param string
	 *            key of path to be removed
	 */
	public void removeFromDatabase(String string) {
		passThroughDatabase.remove(string);
	}

	// merges two Values together. val1 is considered the base (i.e. the fields
	// set in there will not be changed in merge.)
	// only protocol information not contained in val1 will be merged from val2.
	// Val1 has precedence
	/**
	 * helper method that merges to sets of values together, the preemininte
	 * value is val1. val1 cannot be null, otherwise will crash
	 * 
	 * @param val1
	 *            the values in this won't be overwritten, only new values from
	 *            val2 will be put in with it, cannot be null
	 * @param val2
	 *            the values to merge with val1, will not overwrite existing
	 *            values in val1
	 * @return returns a set of merged values, redundant because of the way that
	 *         java handles references
	 */
	private Values mergeValues(Values val1, Values val2) {
		// Values mergedValue = new Values();
		for (Long protocol : val2.getKeySet()) {
			if (!val1.getKeySet().contains(protocol)) {
				val1.putValue(new Protocol(protocol),
						val2.getValue(new Protocol(protocol)));
			}
		}
		return val1; // again, know this return is redundant as val1 reference
						// is directly changed.
	}

}
