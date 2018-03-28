/*©Copyright 2012 Nick Malleson
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
along with RepastCity.  If not, see <http://www.gnu.org/licenses/>.*/

package repastcity3.agent;

import java.util.Iterator;
import java.util.logging.Logger;

import com.vividsolutions.jts.geom.Geometry;

import repast.simphony.context.Context;
import repastcity3.environment.Building;
import repastcity3.environment.GISFunctions;
import repastcity3.environment.SpatialIndexManager;
import repastcity3.exceptions.AgentCreationException;
import repastcity3.main.ContextManager;
import repastcity3.main.GlobalVars;

public class AgentFactory {

	private static Logger LOGGER = Logger.getLogger(AgentFactory.class.getName());

	/** Phương pháp sử dụng khi tạo các tác nhân (xác định trong constructor). */
	private AGENT_FACTORY_METHODS methodToUse;

	/** Định nghĩa của các tác tử - cụ thể đối với phương pháp đang được sử dụng */
	private String definition;

	/**
	 * Create a new agent factory from the given definition.
	 * @param agentDefinition
	 */
	public AgentFactory(String agentDefinition) throws AgentCreationException {

		// First try to parse the definition - Phân tích định nghĩa: agentDefinition = point:people.shp$repastcity3.agent.DefaultAgent
		
		String[] split = agentDefinition.split(":");
		if (split.length != 2) {
			throw new AgentCreationException("Problem parsin the definition string '" + agentDefinition
					+ "': it split into " + split.length + " parts but should split into 2.");
		}
		String method = split[0]; // phương thức tạo agent: point
		String defn = split[1]; // Thông tin về các agent: people.shp$repastcity3.agent.DefaultAgent
		
		if (method.equals(AGENT_FACTORY_METHODS.RANDOM.toString())) { //random
			this.methodToUse = AGENT_FACTORY_METHODS.RANDOM;

		} else if (method.equals(AGENT_FACTORY_METHODS.POINT_FILE.toString())) {//point
			this.methodToUse = AGENT_FACTORY_METHODS.POINT_FILE; // = point
		}

		else if (method.equals(AGENT_FACTORY_METHODS.AREA_FILE.toString())) {//area
			this.methodToUse = AGENT_FACTORY_METHODS.AREA_FILE;
		}

		else {
			throw new AgentCreationException("Unrecognised method of creating agents: '" + method
					+ "'. Method must be one of " + AGENT_FACTORY_METHODS.RANDOM.toString() + ", "
					+ AGENT_FACTORY_METHODS.POINT_FILE.toString() + " or " + AGENT_FACTORY_METHODS.AREA_FILE.toString());
		}

		this.definition = defn; //= people.shp$repastcity3.agent.DefaultAgent		
		this.methodToUse.createAgMeth().createagents(true, this);
	}
	
	private enum AGENT_FACTORY_METHODS {
		RANDOM("random", new CreateAgentMethod() {
			@Override
			public void createagents(boolean b, AgentFactory af) throws AgentCreationException {
				af.createRandomAgents(b);
			}
		}),
		POINT_FILE("point", new CreateAgentMethod() {
			@Override
			public void createagents(boolean b, AgentFactory af) throws AgentCreationException {
				af.createPointAgents(b);
			}
		}),
		AREA_FILE("area", new CreateAgentMethod() {
			@Override
			public void createagents(boolean b, AgentFactory af) throws AgentCreationException {
				af.createAreaAgents(b);
			}
		});

		String stringVal;
		CreateAgentMethod meth;
		
		AGENT_FACTORY_METHODS(String val, CreateAgentMethod f) {
			this.stringVal = val;
			this.meth = f;
		}

		public String toString() {
			return this.stringVal;
		}

		public CreateAgentMethod createAgMeth() {
			return this.meth;
		}
	}
	
	interface CreateAgentMethod {
		void createagents(boolean dummy, AgentFactory af) throws AgentCreationException;//khai báo hàm
	}

	private void createRandomAgents(boolean dummy) throws AgentCreationException {
		
		int numAgents = 20;
		int agentsCreated = 0;
		while (agentsCreated < numAgents) {
			Iterator<Building> i = ContextManager.buildingContext.getRandomObjects(Building.class, numAgents).iterator();
			while (i.hasNext() && agentsCreated < numAgents) {
				Building b = i.next(); 					// Find a building
				IAgent a = new DefaultAgent(); 			// Create a new agent
				a.setHome(b); 							// Tell the agent where it lives
				b.addAgent(a); 							// Tell the building that the agent lives there
				ContextManager.addAgentToContext(a); 	// Add the agent to the context
														// Finally move the agent to the place where it lives.
				ContextManager.moveAgent(a, ContextManager.buildingProjection.getGeometry(b).getCentroid());
				agentsCreated++;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void createPointAgents(boolean dummy) throws AgentCreationException {

		// See if there is a single type of agent to create or should read a colum in shapefile
		boolean singleType = this.definition.contains("$"); // people.shp$repastcity3.agent.DefaultAgent có chứa $ => singleType = true

		String fileName;
		String className;
		Class<IAgent> clazz;
		if (singleType) {
			String[] split = this.definition.split("\\$");
			fileName = ContextManager.getProperty(GlobalVars.GISDataDirectory)+split[0];// ./data/gis_data/toy_city/people.shp
			className = split[1];														// repastcity3.agent.DefaultAgent
			// Try to create a class from the given name.
			try {
				clazz = (Class<IAgent>) Class.forName(className);//DefaultAgent
				GISFunctions.readAgentShapefile(clazz, fileName, ContextManager.getAgentGeography(), ContextManager.getAgentContext());
			} catch (Exception e) {
				throw new AgentCreationException(e);
			}
		} else {
			throw new AgentCreationException("Have not implemented the method of reading agent classes from a "+ "shapefile yet.");
		}

		// Assign agents to houses - Chỉ định nhà cho các agent
		int numAgents = 0;
		for (IAgent a : ContextManager.getAllAgents()) {
			numAgents++;
			Geometry g = ContextManager.getAgentGeometry(a);
			for (Building b : SpatialIndexManager.search(ContextManager.buildingProjection, g)) {
				if (ContextManager.buildingProjection.getGeometry(b).contains(g)) {
					b.addAgent(a);
					a.setHome(b);
				}
			}
		}
		if (singleType) {
			LOGGER.info("Have created " + numAgents + " of type " + clazz.getName().toString() + " from file "
					+ fileName);
		} else {
			// (NOTE: at the moment this will never happen because not implemented yet.)
			LOGGER.info("Have created " + numAgents + " of different types from file " + fileName);
		}
	}

	private void createAreaAgents(boolean dummy) throws AgentCreationException {
		throw new AgentCreationException("Have not implemented the createAreaAgents method yet.");
	}

}
