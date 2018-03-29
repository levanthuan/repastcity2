package repastcity3.main;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Iterator;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;

import repast.simphony.context.Context;
import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.context.space.graph.NetworkBuilder;
import repast.simphony.dataLoader.ContextBuilder;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;
import repast.simphony.parameter.Parameters;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.SimpleAdder;
import repast.simphony.space.graph.Network;
import repastcity3.agent.AgentFactory;
import repastcity3.agent.IAgent;
import repastcity3.agent.ThreadedAgentScheduler;
import repastcity3.environment.Building;
import repastcity3.environment.GISFunctions;
import repastcity3.environment.Junction;
import repastcity3.environment.NetworkEdgeCreator;
import repastcity3.environment.Road;
import repastcity3.environment.SpatialIndexManager;
import repastcity3.environment.contexts.AgentContext;
import repastcity3.environment.contexts.BuildingContext;
import repastcity3.environment.contexts.JunctionContext;
import repastcity3.environment.contexts.RoadContext;
import repastcity3.exceptions.AgentCreationException;
import repastcity3.exceptions.EnvironmentError;
import repastcity3.exceptions.ParameterNotFoundException;

public class ContextManager implements ContextBuilder<Object> {

	private static Logger LOGGER = Logger.getLogger(ContextManager.class.getName());

	// Optionally force agent threading off (good for debugging)
	private static final boolean TURN_OFF_THREADING = false;

	private static Properties properties;

	public static Context<Building> buildingContext;
	public static Geography<Building> buildingProjection;

	public static Context<Road> roadContext;
	public static Geography<Road> roadProjection;

	public static Context<Junction> junctionContext;
	public static Geography<Junction> junctionGeography;
	public static Network<Junction> roadNetwork;

	private static Context<IAgent> agentContext;
	private static Geography<IAgent> agentGeography;

	@Override
	public Context<Object> build(Context<Object> mainContext) {

		RepastCityLogging.init();
		mainContext.setId(GlobalVars.CONTEXT_NAMES.MAIN_CONTEXT);
		
		try {
			readProperties(); 													// đọc file model tính chất
		} catch (IOException ex) {
			throw new RuntimeException("Could not read model properties,  reason: " + ex.toString(), ex);
		}
		String gisDataDir = ContextManager.getProperty(GlobalVars.GISDataDirectory);//gisDataDir = ./data/gis_data/toy_city/		
		LOGGER.log(Level.FINE, "Configuring the environment with data from " + gisDataDir);

		try {
			buildingContext = new BuildingContext();
			buildingProjection = GeographyFactoryFinder.createGeographyFactory(null).createGeography(
													GlobalVars.CONTEXT_NAMES.BUILDING_GEOGRAPHY, 
													buildingContext,
													new GeographyParameters<Building>(new SimpleAdder<Building>()));			
			String buildingFile = gisDataDir + getProperty(GlobalVars.BuildingShapefile);//buildingFile = ./data/gis_data/toy_city/buildings.shp			
			GISFunctions.readShapefile(Building.class, buildingFile, buildingProjection, buildingContext);			
			mainContext.addSubContext(buildingContext);
			SpatialIndexManager.createIndex(buildingProjection, Building.class);
			
			
			roadContext = new RoadContext();
			roadProjection = GeographyFactoryFinder.createGeographyFactory(null).createGeography(
													GlobalVars.CONTEXT_NAMES.ROAD_GEOGRAPHY, 
													roadContext,
													new GeographyParameters<Road>(new SimpleAdder<Road>()));			
			String roadFile = gisDataDir + getProperty(GlobalVars.RoadShapefile);			//roadFile = ./data/gis_data/toy_city/roads.shp			
			GISFunctions.readShapefile(Road.class, roadFile, roadProjection, roadContext);			
			mainContext.addSubContext(roadContext);
			SpatialIndexManager.createIndex(roadProjection, Road.class);

			// Tạo mạng lưới đường
				// 1.junctionContext and junctionGeography
			
				junctionContext = new JunctionContext();
				mainContext.addSubContext(junctionContext);
				junctionGeography = GeographyFactoryFinder.createGeographyFactory(null).createGeography(
														GlobalVars.CONTEXT_NAMES.JUNCTION_GEOGRAPHY, 
														junctionContext,
														new GeographyParameters<Junction>(new SimpleAdder<Junction>()));
				// 2. roadNetwork
				NetworkBuilder<Junction> builder = new NetworkBuilder<Junction>(GlobalVars.CONTEXT_NAMES.ROAD_NETWORK, junctionContext, false);
				builder.setEdgeCreator(new NetworkEdgeCreator<Junction>());			//Tạo các cạnh cho mạng được tạo ra
				roadNetwork = builder.buildNetwork();
				GISFunctions.buildGISRoadNetwork(roadProjection, junctionContext, junctionGeography, roadNetwork);
			
		} catch (MalformedURLException e) {
			LOGGER.log(Level.SEVERE, "", e);
			return null;
		} catch (FileNotFoundException e) {
			LOGGER.log(Level.SEVERE, "Could not find an input shapefile to read objects from.", e);
			return null;
		}

		try {
			agentContext = new AgentContext();			
			agentGeography = GeographyFactoryFinder.createGeographyFactory(null).createGeography(
													GlobalVars.CONTEXT_NAMES.AGENT_GEOGRAPHY, 
													agentContext,
													new GeographyParameters<IAgent>(new SimpleAdder<IAgent>()));
			mainContext.addSubContext(agentContext);
			String agentDefn = ContextManager.getParameter(MODEL_PARAMETERS.AGENT_DEFINITION.toString());
																	//agentDefn = point:people.shp$repastcity3.agent.DefaultAgent
			@SuppressWarnings("unused")
			AgentFactory agentFactory = new AgentFactory(agentDefn);

		} catch (ParameterNotFoundException e) {
			LOGGER.log(Level.SEVERE, "Could not find the parameter which defines how agents should be "
										+ "created. The parameter is called " + MODEL_PARAMETERS.AGENT_DEFINITION
										+ " and should be added to the parameters.xml file.", e);
			return null;
		} 
		catch (AgentCreationException e) {
			LOGGER.log(Level.SEVERE, "", e);
			return null;
		}

		createSchedule();
		return mainContext;
	}


	private void createSchedule() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();

		// Schedule something that outputs ticks every 1000 iterations.Lên lịch một cái gì đó xuất ra mỗi lần lặp lại 1000 lần lặp
		schedule.schedule(ScheduleParameters.createRepeating(1, 1000, ScheduleParameters.LAST_PRIORITY), this, "printTicks");

		boolean isThreadable = true;
		for (IAgent a : agentContext.getObjects(IAgent.class)) {
			if (!a.isThreadable()) {
				isThreadable = false;
				break;
			}
		}

		if (ContextManager.TURN_OFF_THREADING) { // = false, Overide threading?
			isThreadable = false;
		}
		if (isThreadable && (Runtime.getRuntime().availableProcessors() > 1)) {
			/*
			 * Agents can be threaded so the step scheduling not actually done by repast scheduler, a method in
			 * ThreadedAgentScheduler is called which manually steps each agent.
			 */
			LOGGER.log(Level.FINE, "The multi-threaded scheduler will be used.");
			ThreadedAgentScheduler s = new ThreadedAgentScheduler();
			ScheduleParameters agentStepParams = ScheduleParameters.createRepeating(1, 1, 0);
			schedule.schedule(agentStepParams, s, "agentStep");
		} else { // Agents will execute in serial, use the repast scheduler.
			LOGGER.log(Level.FINE, "The single-threaded scheduler will be used.");
			ScheduleParameters agentStepParams = ScheduleParameters.createRepeating(1, 1, 0);
			// Schedule the agents' step methods.
			for (IAgent a : agentContext.getObjects(IAgent.class)) {
				schedule.schedule(agentStepParams, a, "step");
			}
		}
	}

	private static long speedTimer = -1; // For recording time per N iterations 
	public void printTicks() {
		LOGGER.info("Iterations: " + RunEnvironment.getInstance().getCurrentSchedule().getTickCount()+
				". Speed: "+((double)(System.currentTimeMillis()-ContextManager.speedTimer)/1000.0)+
				"sec/ticks.");
		ContextManager.speedTimer = System.currentTimeMillis();
	}

	/**
	 * Convenience function to get a Simphony parameter
	 * 
	 * @param <T>
	 *            The type of the parameter
	 * @param paramName
	 *            The name of the parameter
	 * @return The parameter.
	 * @throws ParameterNotFoundException
	 *             If the parameter could not be found.
	 */
	public static <V> V getParameter(String paramName) throws ParameterNotFoundException {
		Parameters p = RunEnvironment.getInstance().getParameters();
		Object val = p.getValue(paramName);

		if (val == null) {
			throw new ParameterNotFoundException(paramName);
		}

		// Try to cast the value and return it
		@SuppressWarnings("unchecked")
		V value = (V) val;
		return value;
	}

	/**
	 * Get the value of a property in the properties file. If the input is empty or null or if there is no property with
	 * a matching name, throw a RuntimeException.
	 * 
	 * @param property The property to look for.
	 * @return A value for the property with the given name.
	 */
	public static String getProperty(String property) {
		if (property == null || property.equals("")) {
			throw new RuntimeException("getProperty() error, input parameter (" + property + ") is "
					+ (property == null ? "null" : "empty"));
		} else {
			String val = ContextManager.properties.getProperty(property);
			if (val == null || val.equals("")) { // No value exists in the
													// properties file
				throw new RuntimeException("checkProperty() error, the required property (" + property + ") is "
						+ (property == null ? "null" : "empty"));
			}
			return val;
		}
	}

	/**
	 * Đọc tệp properties (thuộc tính) và thêm thuộc tính.
	 */
	private void readProperties() throws FileNotFoundException, IOException {
		
		File propFile = new File("./repastcity.properties");
		if (!propFile.exists()) {
			throw new FileNotFoundException("Could not find properties file in the default location: "+ propFile.getAbsolutePath());
		}		
		LOGGER.log(Level.FINE, "Initialising properties from file " + propFile.toString());
		
		ContextManager.properties = new Properties();		
		
		FileInputStream in = new FileInputStream(propFile.getAbsolutePath());//propFile.getAbsolutePath(): lấy đường dẫn tuyệt đối của file 		
		ContextManager.properties.load(in);
		in.close();
		return;
	}

	public static int sizeOfIterable(Iterable<?> i) {
		int size = 0;
		Iterator<?> it = i.iterator();
		while (it.hasNext()) {
			size++;
			it.next();
		}
		return size;
	}

	/**
	 * Kiểm tra xem Contexts đã cho có nhiều đối tượng trong đó không
	 */
	public void checkSize(Context<?>... contexts) throws EnvironmentError {
		for (Context<?> c : contexts) {
			int numObjs = sizeOfIterable(c.getObjects(Object.class));
			if (numObjs == 0) {
				throw new EnvironmentError("There are no objects in the context: " + c.getId().toString());
			}
		}
	}

	public static void stopSim(Exception ex, Class<?> clazz) {
		ISchedule sched = RunEnvironment.getInstance().getCurrentSchedule();
		sched.setFinishing(true);
		sched.executeEndActions();
		LOGGER.log(Level.SEVERE, "ContextManager has been told to stop by " + clazz.getName(), ex);
	}

	/**
	 * Di chuyển các đối tượng quy định khoảng cách xác định theo góc cụ thể.
	 * 
	 * @param agent The agent to move.
	 * @param distToTravel Khoảng cách mà họ sẽ travel
	 * @param angle Góc để travel.
	 * @see Geography
	 * @return vị trí hình học mà đối tượng đã được chuyển đến
	 */
	public static synchronized void moveAgentByVector(IAgent agent, double distToTravel, double angle) {
		ContextManager.agentGeography.moveByVector(agent, distToTravel, angle);
	}

	/**
	 * Di chuyển một agent. 
	 * 
	 * @param agent Agent cần di chuyển
	 * @param point Điểm đến
	 */
	public static synchronized void moveAgent(IAgent agent, Point point) {
		ContextManager.agentGeography.move(agent, point);
	}

	/**
	 *	Add agent vào trong agentContext
	 */
	public static synchronized void addAgentToContext(IAgent agent) {
		ContextManager.agentContext.add(agent);
	}

	/**
	 * Trả về tất cả các agent trong agentContext.
	 */
	public static synchronized Iterable<IAgent> getAllAgents() {
		return ContextManager.agentContext.getRandomObjects(IAgent.class, ContextManager.agentContext.size());
	}

	/**
	 * Lấy vị trí hình học của đối tượng được chỉ định
	 */
	public static synchronized Geometry getAgentGeometry(IAgent agent) {
		return ContextManager.agentGeography.getGeometry(agent);
	}

	/**
	 * Get a pointer to the agent context. Trả về con trỏ trỏ tới agentContext
	 */
	public static Context<IAgent> getAgentContext() {
		return ContextManager.agentContext;
	}

	/**
	 * Get a pointer to the agent geography. Trả về con trỏ trỏ tới agentGeography
	 */
	public static Geography<IAgent> getAgentGeography() {
		return ContextManager.agentGeography;
	}
}