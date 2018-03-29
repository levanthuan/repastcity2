package repastcity3.agent;

import java.util.Iterator;
import java.util.logging.Logger;

import com.vividsolutions.jts.geom.Geometry;

import repastcity3.environment.Building;
import repastcity3.environment.GISFunctions;
import repastcity3.environment.SpatialIndexManager;
import repastcity3.exceptions.AgentCreationException;
import repastcity3.main.ContextManager;
import repastcity3.main.GlobalVars;

public class AgentFactory {

	private static Logger LOGGER = Logger.getLogger(AgentFactory.class.getName());

	private AGENT_FACTORY_METHODS methodToUse;//phương pháp tạo agent(random, point, area)
	private String definition;//Định nghĩa agent

	/**
	 * Tạo 1 factory agent với các định nghĩa.
	 * @param agentDefinition
	 */
	public AgentFactory(String agentDefinition) throws AgentCreationException {
		
		String[] split = agentDefinition.split(":");
		if (split.length != 2) {
			throw new AgentCreationException("Problem parsin the definition string '" + agentDefinition
					+ "': it split into " + split.length + " parts but should split into 2.");
		}
		String method = split[0]; 	// random
		String defn = split[1]; 	// people.shp$repastcity3.agent.DefaultAgent
		
		if (method.equals(AGENT_FACTORY_METHODS.RANDOM.toString())) {
			this.methodToUse = AGENT_FACTORY_METHODS.RANDOM;
			
		} else if (method.equals(AGENT_FACTORY_METHODS.POINT_FILE.toString())) {
			this.methodToUse = AGENT_FACTORY_METHODS.POINT_FILE;
			
		} else if (method.equals(AGENT_FACTORY_METHODS.AREA_FILE.toString())) {
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
		void createagents(boolean dummy, AgentFactory af) throws AgentCreationException;
	}

	private void createRandomAgents(boolean dummy) throws AgentCreationException {
		
		int numAgents = 30;
		int agentsCreated = 0;
		Iterator<Building> randBuildings = ContextManager.buildingContext.getRandomObjects(Building.class, numAgents).iterator();
		while (randBuildings.hasNext() && agentsCreated < numAgents) {
			Building randBuilding = randBuildings.next();
			IAgent agent = new DefaultAgent();
			
			agent.setHome(randBuilding);
			randBuilding.addAgent(agent);
			
			ContextManager.addAgentToContext(agent);
			ContextManager.moveAgent(agent, ContextManager.buildingProjection.getGeometry(randBuilding).getCentroid());
						
			agentsCreated++;
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
