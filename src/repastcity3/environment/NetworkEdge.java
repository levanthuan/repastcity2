/*
©Copyright 2012 Nick Malleson
This file is part of RepastCity.

RepastCity is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

RepastCity is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with RepastCity.  If not, see <http://www.gnu.org/licenses/>.
 */

package repastcity3.environment;

import java.util.ArrayList;
import java.util.List;

import repast.simphony.space.graph.RepastEdge;

/**
 * Class used to provide extra functionality to the normal RepastEdge class. Stores a list of the different ways in
 * which this edge can be traversed, e.g. a list with "walk", "car" and "train" indicates that this edge can be
 * traversed by agents who are walking, driving or on a train. The getWeight() function will return a weight appropriate
 * for the agent, for example if the edge can only be traversed by car it will return an infinite weight for agents who
 * are walking.
 * 
 * @author Nick Malleson
 * @param <T>
 */
public class NetworkEdge<T> extends RepastEdge<T> {

	// private static Logger LOGGER = Logger.getLogger(NetworkEdge.class.getName());

	private List<String> access = new ArrayList<String>(); // The access methods agents can use to travel along this
															// edge
	private boolean majorRoad = false; // If edge represents a major road car drivers can travel very fast
	private Road road; // The Road object which this Edge is used to represent

	/**
	 * Create a new network edge (same as RepastEdge constructor) but also define how the road can be accessed
	 * (initially at least, as the transport network is built up edges will have different ways they can be accessed,
	 * e.g. buy bus as well).
	 * 
	 * @param source
	 * @param target
	 * @param directed
	 * @param weight
	 * @param initialAccess
	 *            e.g. a list containing strings ("walk", "bus" or "car" etc). Can be null if different road
	 *            accessibility / transport networks are not being used (e.g. in Grid environment).
	 */
	public NetworkEdge(T source, T target, boolean directed, double weight, List<String> initialAccess) {
		super(source, target, directed, weight);
		if (initialAccess != null) {
			this.access.addAll(initialAccess);
		}
	}

	/**
	 * Get the weight of this edge, relative to the Burglar GlobalVars.TRANSPORT_PARAMS.currentBurglar. The weight will
	 * be divided by the speed (see getSpeed()).
	 */
	@Override
	public double getWeight() {
		return super.getWeight() / this.getSpeed();
	}

	/**
	 * The speed with which the Burglar GlobalVars.TRANSPORT_PARAMS.currentBurglar can travel across this edge. Speed
	 * depends on the methods that can be used to travel along this edge and the transport methods available to the
	 * burglar (e.g. if burglar can take a bus and this edge forms a bus route then speed > 1 (quicker than walking)).
	 * Will return the quickest speed possible.
	 * 
	 * @return A speed multiplier if the agent can travel across this edge (i.e. x times quicker than walking) or
	 *         Double.MIN_VALUE if the agent doesn't have the appropriate transport to get across this edge).
	 */
	public double getSpeed() {
		return 1;

	}

	public List<String> getTypes() {
		return this.access;
	}

	/**
	 * Adds a type to this NetworkEdge, indicating that it forms more than just a road network.
	 * 
	 * @param type
	 * @return
	 */
	public void addType(String type) {
		this.access.add(type);
	}

	/**
	 * Set whether or not this edge represents a major road (defult is false). If true then car drivers are able to
	 * travel faster along this road than they are others.
	 * 
	 * @param majorRoad
	 *            True if this edge represents a major road, false otherwise.
	 */
	public void setMajorRoad(boolean majorRoad) {
		this.majorRoad = majorRoad;
	}

	/**
	 * Get the Road that this NetworkEdge is used to represent.
	 * 
	 * @return the road
	 */
	public Road getRoad() {
		return road;
	}

	/**
	 * @param road
	 *            the road to set
	 */
	public void setRoad(Road road) {
		this.road = road;
	}

	@Override
	public String toString() {
		return "Edge between " + this.getSource() + "->" + this.getTarget() + " accessible by "
				+ this.access.toString() + (this.majorRoad ? " (is major road)" : "");
	}

	/**
	 * Determines equality by comparing the source and destination.
	 */
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof NetworkEdge))
			return false;
		NetworkEdge<?> e = (NetworkEdge<?>) obj;
		return e.source.equals(this.source) && e.target.equals(this.target);
	}

	@Override
	public int hashCode() {
		return this.road.hashCode(); // Road should be unique for this edge.
	}

}
