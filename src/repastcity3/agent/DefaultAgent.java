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

You should have received a copy of  the GNU General Public License
along with RepastCity.  If not, see <http://www.gnu.org/licenses/>.
*/

package repastcity3.agent;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import repastcity3.environment.Building;
import repastcity3.environment.Route;
import repastcity3.main.ContextManager;

public class DefaultAgent implements IAgent {

	private static Logger LOGGER = Logger.getLogger(DefaultAgent.class.getName());

	private Building home;
	private Route route;

	private boolean goingHome = false; // Whether the agent is going to or from their home

	private static int uniqueID = 0;
	private int id;

	public DefaultAgent() {
		this.id = uniqueID++;
	}

	@Override
	public void step() throws Exception {
		LOGGER.log(Level.FINE, "Agent " + this.id + " is stepping.");
		if (this.route == null) {		// Khởi tạo đầu chương trình
			this.goingHome = false; 	// Phải ra khỏi nhà									
			Building b = ContextManager.buildingContext.getRandomObject();	// Chọn random một tòa nhà mới để đi đến			
			this.route = new Route(this, b.getCoords(), b);
		}		
		
		if (!this.route.atDestination()) {// Chưa đến đích
			
			this.route.travel();
			
		} else {						// Đã đến đích, bây giờ hoặc về nhà hoặc lên tòa nhà khác
			if (this.goingHome) {
				this.goingHome = false;				
				Building b = ContextManager.buildingContext.getRandomObject();				
				this.route = new Route(this, b.getCoords(), b);
			} else {
				this.goingHome = true;
				this.route = new Route(this, this.home.getCoords(), this.home);
			}
		}
	}

	/**
	 * There will be no inter-agent communication so these agents can be executed simulataneously in separate threads.
	 */
	@Override
	public final boolean isThreadable() {
		return true;
	}

	@Override
	public void setHome(Building home) {
		this.home = home;
	}

	@Override
	public Building getHome() {
		return this.home;
	}

	@Override
	public <T> void addToMemory(List<T> objects, Class<T> clazz) {
	}

	@Override
	public List<String> getTransportAvailable() {
		return null;
	}

	@Override
	public String toString() {
		return "Agent " + this.id;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof DefaultAgent))
			return false;
		DefaultAgent b = (DefaultAgent) obj;
		return this.id == b.id;
	}

	@Override
	public int hashCode() {
		return this.id;
	}

}
