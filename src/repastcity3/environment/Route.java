package repastcity3.environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Vector;

//import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.geotools.referencing.GeodeticCalculator;

import cern.colt.Arrays;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.operation.distance.DistanceOp;

import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.RepastEdge;
import repast.simphony.space.graph.ShortestPath;
import repastcity3.agent.IAgent;
import repastcity3.exceptions.RoutingException;
import repastcity3.main.ContextManager;
import repastcity3.main.GlobalVars;

public class Route implements Cacheable {

	private static Logger LOGGER = Logger.getLogger(Route.class.getName());
	private IAgent agent;			     	// tác tử
	private Coordinate destination;			// tọa độ đến
	private Building destinationBuilding;	// tòa nhà đến

	private int currentPosition; //vị trí hiện tại
	private List<Coordinate> routeX;
	private List<Double> routeSpeedsX;

	private List<Road> roadsX;

	private static volatile Map<Coordinate, List<Road>> coordCache;

	private static volatile NearestRoadCoordCache nearestRoadCoordCache;
	
	public Route(IAgent agent, Coordinate destination, Building destinationBuilding) {
		this.destination = destination;
		this.agent = agent;
		this.destinationBuilding = destinationBuilding;
	}

	protected void setRoute() throws Exception {
		this.routeX = new Vector<Coordinate>();
		this.roadsX = new Vector<Road>();
		this.routeSpeedsX = new Vector<Double>();

		Coordinate currentCoord = ContextManager.getAgentGeometry(this.agent).getCoordinate();	//tọa độ hiện tại
		Coordinate destCoord = this.destination;												// tọa độ nơi đến		

//		double x = currentCoord.x;
//		double y = currentCoord.y;
//		System.out.println("curent-X = "+x+" curent-Y = "+y+" dest-X: "+ destCoord.x +"dest-Y: "+ destCoord.y);
		
		try {
			boolean destinationOnRoad = true; 		//true: tọa độ điểm cuối có nằm trên roads, false: tọa độ ko nằm trên roads
			Coordinate finalDestination = null;		//Tọa độ điểm đến cuối
			
			if (!coordOnRoad(currentCoord)) {		//Vị trí hiện tại không nằm trên roads -> lấy vị trí gần roads nhất
				currentCoord = getNearestRoadCoord(currentCoord);
				addToRoute(currentCoord, Road.nullRoad, 1);
			}
			if (!coordOnRoad(destCoord)) {   		// Vị trí của đích đến không trùng với roads -> Lấy vị trí đích đến gần home nhất
				destinationOnRoad = false;
				finalDestination = destCoord; 		// Added to route at end of alg.
				destCoord = getNearestRoadCoord(destCoord);
			}

			Road currentRoad = Route.findNearestObject(currentCoord, ContextManager.roadProjection, null, 
																	GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LONG);
			List<Junction> currentJunctions = currentRoad.getJunctions();//2 điểm gần điểm hiện tại nhất(về 2 phía đường)
			
			Road destRoad = Route.findNearestObject(destCoord, ContextManager.roadProjection, null,
																	GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LONG);			
			List<Junction> destJunctions = destRoad.getJunctions();		//2 điểm gần điểm đích nhất(về 2 phía đường)	
			
			Junction[] routeEndpoints = new Junction[2];
			List<RepastEdge<Junction>> shortestPath = getShortestRoute(currentJunctions, destJunctions, routeEndpoints);
			Junction currentJunction = routeEndpoints[0];
			Junction destJunction = routeEndpoints[1];			
			
			List<Coordinate> tempCoordList = new Vector<Coordinate>();/* Thêm tọa độ mô tả cách đi đến ngã ba gần nhất */						
			this.getCoordsAlongRoad(currentCoord, currentJunction.getCoords(), currentRoad, true, tempCoordList);
			addToRoute(tempCoordList, currentRoad, 1);			
						 		
			this.getRouteBetweenJunctions(shortestPath, currentJunction);/* Cách di chuyển đoạn đường giữa */		
			
			tempCoordList.clear();
			this.getCoordsAlongRoad(ContextManager.junctionGeography.getGeometry(destJunction).getCoordinate(),
									destCoord, 
									destRoad, 
									false, 
									tempCoordList);
			addToRoute(tempCoordList, destRoad, 1);
			
			
			if (!destinationOnRoad) {
				addToRoute(finalDestination, Road.nullRoad, 1);
			}
		} catch (RoutingException e) {
			throw e;
		}
	}

	/**
	 * Chức năng tiện lợi có thể được sử dụng để thêm chi tiết cho tuyến đường. 
	 * Điều này nên được sử dụng thay vì cập nhật các danh sách cá nhân bởi vì nó đảm bảo rằng tất cả các danh sách vẫn đồng bộ
	 * 
	 * @param coord
	 *            Coordinate được thêm vào route
	 * @param road
	 *            Road mà Coordinate là 1 phần
	 * @param speed
	 *            Tốc độ mà con đường có thể đi dọc theo
	 */
	private void addToRoute(Coordinate coord, Road road, double speed) {
		this.routeX.add(coord);
		this.roadsX.add(road);
		this.routeSpeedsX.add(speed);
	}

	private void addToRoute(List<Coordinate> coords, Road road, double speed) {
		for (Coordinate c : coords) {
			this.routeX.add(c);
			this.roadsX.add(road);
			this.routeSpeedsX.add(speed);
		}
	}

	public void travel() throws Exception {
		if (this.routeX == null) {
			this.setRoute();
		}
		try {
			double distTravelled = 0; 				// Khoảng cách đi được cho đến nay
			Coordinate currentCoord = null; 		// Vị trí hiện tại
			Coordinate target = null; 				// Nhắm mục tiêu mà chúng ta đang hướng tới (trong danh sách tuyến đường)
			double speed; 							// Tốc độ để đi tới coord tiếp theo
			GeometryFactory geomFac = new GeometryFactory();
			currentCoord = ContextManager.getAgentGeometry(this.agent).getCoordinate();

			while (!this.atDestination()) {
				target = this.routeX.get(this.currentPosition);
				speed = this.routeSpeedsX.get(this.currentPosition); 		// speed = 1.0
				
				double[] distAndAngle = new double[2];
				Route.distance(currentCoord, target, distAndAngle);			// chia cho tốc độ vì khoảng cách có thể ngắn hơn
				
				double distToTarget = distAndAngle[0] / speed;
									// Nếu chúng ta có thể nhận được tất cả các cách để các coords tiếp theo trên tuyến đường sau đó chỉ cần đi có
				if (distTravelled + distToTarget < GlobalVars.GEOGRAPHY_PARAMS.TRAVEL_PER_TURN) {
					distTravelled += distToTarget;
					currentCoord = target;
					
					if (this.currentPosition == (this.routeX.size() - 1)) { // Xem đại lý đã đến cuối tuyến đường.
						ContextManager.moveAgent(this.agent, geomFac.createPoint(currentCoord));
						break;
					}
					this.currentPosition++;			// Chưa kết thúc tuyến đường, tăng bộ đếm
				} else if (distTravelled + distToTarget == GlobalVars.GEOGRAPHY_PARAMS.TRAVEL_PER_TURN) {
					ContextManager.moveAgent(agent, geomFac.createPoint(target));
					this.currentPosition++;
					LOGGER.log(Level.WARNING, "Travel(): UNUSUAL CONDITION HAS OCCURED!");
					break;
					
				} else {
					double distToTravel = (GlobalVars.GEOGRAPHY_PARAMS.TRAVEL_PER_TURN - distTravelled) * speed;
					ContextManager.moveAgentByVector(this.agent, distToTravel, distAndAngle[1]);
					break;
				}
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Route.trave(): Caught error travelling for " + this.agent.toString()
					+ " going to " + "destination "
					+ (this.destinationBuilding == null ? "" : this.destinationBuilding.toString() + ")"));
			throw e;
		}
	}
	
	/**
	 * Tìm tọa độ gần nhất là một phần của Con đường. Trả về tọa độ thực sự gần nhất với coord nhất định, không chỉ là góc của đoạn gần nhất. 
	 * Sử dụng lớp DistanceOp tìm điểm gần nhất giữa hai hình học.
	 * Khi được gọi lần đầu tiên, chức năng sẽ nạp 'nearRoadCoordCache' để tính toán vị trí đường gần nhất với mỗi tòa nhà. 
	 * Các đại lý thường sẽ bắt đầu hành trình từ trong tòa nhà vì vậy sẽ nâng cao hiệu quả.
	 */
	private synchronized Coordinate getNearestRoadCoord(Coordinate inCoord) throws Exception {
		if (nearestRoadCoordCache == null) {
			LOGGER.log(Level.FINE, "Route.getNearestRoadCoord called for first time, "
					+ "creating cache of all roads and the buildings which are on them ...");
			// Create a new cache object, this will be read from disk if possible 
			// (which is why the getInstance() method is used instead of the constructor.
			String gisDir = ContextManager.getProperty(GlobalVars.GISDataDirectory);// = ./data/gis_data/toy_city/
			File buildingsFile = new File(gisDir + ContextManager.getProperty(GlobalVars.BuildingShapefile));
			File roadsFile = new File(gisDir + ContextManager.getProperty(GlobalVars.RoadShapefile));
			File serialisedLoc = new File(gisDir + ContextManager.getProperty(GlobalVars.BuildingsRoadsCoordsCache));

			nearestRoadCoordCache = NearestRoadCoordCache.getInstance(ContextManager.buildingProjection,
					buildingsFile, ContextManager.roadProjection, roadsFile, serialisedLoc, new GeometryFactory());
		}
		return nearestRoadCoordCache.get(inCoord);
	}

	/**
	 * Tìm tuyến đường ngắn nhất giữa nhiều điểm đến và điểm đến. 
	 * Sẽ trả về con đường ngắn nhất và cũng có thể, thông qua hai tham số, có thể trả về điểm nút gốc và điểm đến tạo nên tuyến đường ngắn nhất.
	 * 
	 * @param currentJunctions
	 *            An array of origin junctions
	 * @param destJunctions
	 *            An array of destination junctions
	 * @param routeEndpoints
	 *            An array of size 2 which can be used to store the origin (index 0) and destination (index 1) Junctions
	 *            which form the endpoints of the shortest route.
	 * @return the shortest route between the origin and destination junctions
	 * @throws Exception
	 */
	private List<RepastEdge<Junction>> getShortestRoute(List<Junction> currentJunctions, List<Junction> destJunctions,
			Junction[] routeEndpoints) throws Exception {
		
		synchronized (GlobalVars.TRANSPORT_PARAMS.currentBurglarLock) {
			int num = 0; 
			double shortestPathLength = 0;
			double pathLength = 0;
			ShortestPath<Junction> p;
			List<RepastEdge<Junction>> shortestPath = null;
			for (Junction o : currentJunctions) {
				for (Junction d : destJunctions) {
					num++;
					if (o == null || d == null) {
						LOGGER.log(Level.WARNING, "Route.getShortestRoute() error: either the destination or origin "
												+ "junction is null. This can be caused by disconnected roads. It's probably OK"
												+ "to ignore this as a route should still be created anyway.");
					} else {						
						p = new ShortestPath<Junction>(ContextManager.roadNetwork);						
						pathLength = p.getPathLength(o,d);					//chiều dài đường dẫn từ nút nguồn đến nút đích.
						
						if (num == 1) {
							shortestPathLength = pathLength;
						}
						if (pathLength <= shortestPathLength) {
							shortestPathLength = pathLength;
							shortestPath = p.getPath(o,d);		//Trả về một danh sách RepastEdges trong đường dẫn ngắn nhất từ nguồn đến đích
							routeEndpoints[0] = o;
							routeEndpoints[1] = d;
						}
						p.finalize();
						p = null;
					}
				}
			}
			if (shortestPath == null) {
				String debugString = "Route.getShortestRoute() could not find a route. Looking for the shortest route between :\n";
				for (Junction j : currentJunctions)
					debugString += "\t" + j.toString() + ", roads: " + j.getRoads().toString() + "\n";
				for (Junction j : destJunctions)
					debugString += "\t" + j.toString() + ", roads: " + j.getRoads().toString() + "\n";
				throw new RoutingException(debugString);
			}
			return shortestPath;
		}
	}

	/**
	 * Tính tọa độ cần thiết để di chuyển một tác nhân từ vị trí hiện tại của chúng đến điểm đến dọc theo một con đường nhất định. 
	 * Các thuật toán để làm điều này là như sau:
	 * <ol>
	 * <li>Bắt đầu từ phối hợp điểm đến, ghi lại mỗi đỉnh và kiểm tra bên trong ranh giới của mỗi đoạn đường cho đến khi tìm thấy điểm đích.</li>
	 * <li>Trả lại tất cả trừ đỉnh cuối cùng, đây là tuyến đường đến đích.</li>
	 * </ol>
	 * Boolean cho phép hai trường hợp: hướng về phía đường giao nhau (điểm cuối của đường) hoặc đi từ điểm cuối của đường (hàm này không thể dùng để đi đến hai điểm giữa trên một đường thẳng)
	 * 
	 * @param currentCoord
	 * @param destinationCoord
	 * @param road
	 * @param toJunction
	 *            whether or not we're travelling towards or away from a Junction
	 * @param coordList
	 *            A list which will be populated with the coordinates that the agent should follow to move along the
	 *            road.
	 * @param roadList
	 *            A list of roads associated with each coordinate.
	 * @throws Exception
	 */
	private void getCoordsAlongRoad(Coordinate currentCoord, Coordinate destinationCoord, Road road,
			boolean toJunction, List<Coordinate> coordList) throws RoutingException {
		Coordinate[] roadCoords = ContextManager.roadProjection.getGeometry(road).getCoordinates();

		// Check that the either the destination or current coordinate are actually part of the road
		boolean currentCorrect = false, destinationCorrect = false;
		for (int i = 0; i < roadCoords.length; i++) {
			if (toJunction && destinationCoord.equals(roadCoords[i])) {
				destinationCorrect = true;
				break;
			} else if (!toJunction && currentCoord.equals(roadCoords[i])) {
				currentCorrect = true;
				break;
			}
		} // for

		if (!(destinationCorrect || currentCorrect)) {
			String roadCoordsString = "";
			for (Coordinate c : roadCoords)
				roadCoordsString += c.toString() + " - ";
			throw new RoutingException("Neigher the origin or destination nor the current"
					+ "coordinate are part of the road '" + road.toString() + "' (person '" + this.agent.toString()
					+ "').\n" + "Road coords: " + roadCoordsString + "\n" + "\tOrigin: " + currentCoord.toString()
					+ "\n" + "\tDestination: " + destinationCoord.toString() + " ( "
					+ this.destinationBuilding.toString() + " )\n " + "Heading " + (toJunction ? "to" : "away from")
					+ " a junction, so " + (toJunction ? "destination" : "origin")
					+ " should be part of a road segment");
		}

		// Might need to reverse the order of the road coordinates
		if (toJunction && !destinationCoord.equals(roadCoords[roadCoords.length - 1])) {
			// If heading towards a junction, destination coordinate must be at end of road segment
			ArrayUtils.reverse(roadCoords);
		} else if (!toJunction && !currentCoord.equals(roadCoords[0])) {
			// If heading away form junction current coord must be at beginning of road segment
			ArrayUtils.reverse(roadCoords);
		}
		GeometryFactory geomFac = new GeometryFactory();
		Point destinationPointGeom = geomFac.createPoint(destinationCoord);
		Point currentPointGeom = geomFac.createPoint(currentCoord);
		
		search: for (int i = 0; i < roadCoords.length - 1; i++) {
			Coordinate[] segmentCoords = new Coordinate[] { roadCoords[i], roadCoords[i + 1] };
			// Draw a small buffer around the line segment and look for the coordinate within the buffer
			Geometry buffer = geomFac.createLineString(segmentCoords).buffer(GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.SMALL.dist);
			if (!toJunction) {
				/* If heading away from a junction, keep adding road coords until we find the destination */
				coordList.add(roadCoords[i]);
				if (destinationPointGeom.within(buffer)) {
					coordList.add(destinationCoord);
					break search;
				}
			} else if (toJunction) {
				/*
				 * If heading towards a junction: find the curent coord, add it to the route, then add all the remaining
				 * coords which make up the road segment
				 */
				if (currentPointGeom.within(buffer)) {
					for (int j = i + 1; j < roadCoords.length; j++) {
						coordList.add(roadCoords[j]);
					}
					coordList.add(destinationCoord);
					break search;
				}
			}
		} // for
	}

	/**
	 * Trả về tất cả các tọa độ mô tả cách đi dọc theo con đường, giới hạn đến các tọa độ đường. 
	 * Trong một số trường hợp đường không có đường liên kết, điều này xảy ra nếu tuyến đường là một phần của mạng lưới vận tải. 
	 * Trong trường hợp này, chỉ cần thêm tọa độ nguồn gốc và điểm đến vào tuyến đường.
	 * 
	 * @param shortestPath
	 * @param startingJunction
	 *         Đường nối của đường đi bắt đầu, điều này được yêu cầu để thuật toán biết đường phối hợp để thêm đầu tiên 
	 *         (có thể là đầu tiên hoặc cuối cùng tùy thuộc vào thứ tự mà tọa độ đường được lưu trữ nội bộ).
	 * @return tọa độ như là một ánh xạ giữa coord và tốc độ liên quan của nó (tức là tốc độ tác nhân có thể đi đến coord tiếp theo) 
	 * 			phụ thuộc vào loại cạnh và tác nhân (ví dụ như đi bộ / đi bộ / xe buýt). 
	 * 			LinkedHashMap được sử dụng để đảm bảo thứ tự chèn của các coords được duy trì.
	 */
	private void getRouteBetweenJunctions(List<RepastEdge<Junction>> shortestPath, Junction startingJunction)
			throws RoutingException {
		if (shortestPath.size() < 1) {
			// Điều này có thể xảy ra khi đích đến của agent là chính nhà mình
			return;
		}
		// Lock the currentAgent so that NetworkEdge obejcts know what speed to use (depends on transport available to the specific agent).
		synchronized (GlobalVars.TRANSPORT_PARAMS.currentBurglarLock) {
			GlobalVars.TRANSPORT_PARAMS.currentAgent = this.agent;

			// Iterate over all edges in the route adding coords and weights as appropriate
			NetworkEdge<Junction> e;
			Road r;
			// Use sourceFirst to represent whether or not the edge's source does actually represent the start of the
			// edge (agent could be going 'forwards' or 'backwards' over edge
			boolean sourceFirst;
			for (int i = 0; i < shortestPath.size(); i++) {
				e = (NetworkEdge<Junction>) shortestPath.get(i);
				if (i == 0) {
					// No coords in route yet, compare the source to the starting junction
					sourceFirst = (e.getSource().equals(startingJunction)) ? true : false;
				} else {
					// Otherwise compare the source to the last coord added to the list
					sourceFirst = (e.getSource().getCoords().equals(this.routeX.get(this.routeX.size() - 1))) ? true : false;
				}
				
				r = e.getRoad();
				
				double speed = e.getSpeed();				//speed = 1

				if (r == null) { // Không có con đường nào liên quan đến cạnh này (đó là một tuyến vận chuyển) vì vậy chỉ cần thêm nguồn
					if (sourceFirst) {
						this.addToRoute(e.getSource().getCoords(), r, speed);
						this.addToRoute(e.getTarget().getCoords(), r, -1);
						// (Note speed = -1 used because we don't know the weight to the next coordinate - this can be removed later)
					} else {
						this.addToRoute(e.getTarget().getCoords(), r, speed);
						this.addToRoute(e.getSource().getCoords(), r, -1);
					}
				} else {
					// This edge is a road, add all the coords which make up its geometry
					Coordinate[] roadCoords = ContextManager.roadProjection.getGeometry(r).getCoordinates();
					if (roadCoords.length < 2)
						throw new RoutingException("Route.getRouteBetweenJunctions: for some reason road " + "'"
								+ r.toString() + "' doesn't have at least two coords as part of its geometry ("
								+ roadCoords.length + ")");
					// Make sure the coordinates of the road are added in the correct order
					if (!sourceFirst) {
						ArrayUtils.reverse(roadCoords);
					}
					// Add all the road geometry's coords
					for (int j = 0; j < roadCoords.length; j++) {
						this.addToRoute(roadCoords[j], r, speed);
						// (Note that last coord will have wrong weight)
					}
				}
			}
			return;
		}
	}

	/**
	 * Xác định agent đã đến đích hay chưa
	 * @return True nếu người đó ở đích đến
	 */
	public boolean atDestination() {
		return ContextManager.getAgentGeometry(this.agent).getCoordinate().equals(this.destination);
	}
	
	/**
	 * Tìm đối tượng gần nhất trong geography cho phù hợp.
	 * 
	 * @param <T>
	 * @param x
	 *            The coordinate to search from
	 * @param geography
	 *            The given geography to look through
	 * @param closestPoints
	 *            An optional List that will be populated with the closest points to x (i.e. the results of
	 *            <code>distanceOp.closestPoints()</code>.
	 * @param searchDist
	 *            The maximum distance to search for objects in. Small distances are more efficient but larger ones are
	 *            less likely to find no objects.
	 * @return The nearest object.
	 * @throws RoutingException
	 *             If an object cannot be found.
	 */
	public static synchronized <T> T findNearestObject(Coordinate x, Geography<T> geography,
			List<Coordinate> closestPoints, GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE searchDist)
			throws RoutingException {
		if (x == null) {
			throw new RoutingException("The input coordinate is null, cannot find the nearest object");
		}
		T nearestObject = SpatialIndexManager.findNearestObject(geography, x, closestPoints, searchDist);
		if (nearestObject == null) {
			throw new RoutingException("Couldn't find an object close to these coordinates:\n\t" + x.toString());
		} else {
			return nearestObject;
		}
	}


	/**
	 * Kiểm tra nếu một tọa độ là một phần của đoạn đường.
	 * 
	 * @param coord
	 *            The coordinate which we want to test
	 * @return True if the coordinate is part of a road segment
	 */
	private boolean coordOnRoad(Coordinate coord) {
		populateCoordCache(); // kiểm tra bộ nhớ cache đã được cư trú
		return coordCache.containsKey(coord);
	}

	private synchronized static void populateCoordCache() {

		if (coordCache == null) { // Fist check cache has been created
			coordCache = new HashMap<Coordinate, List<Road>>();
			LOGGER.log(Level.FINER,"Route.populateCoordCache called for first time, creating new cache of all Road coordinates.");
		}
		if (coordCache.size() == 0) { // Now popualte it if it hasn't already
										// been populated
			LOGGER.log(Level.FINER, "Route.populateCoordCache: is empty, creating new cache of all Road coordinates.");

			for (Road r : ContextManager.roadContext.getObjects(Road.class)) {
				for (Coordinate c : ContextManager.roadProjection.getGeometry(r).getCoordinates()) {
					if (coordCache.containsKey(c)) {
						coordCache.get(c).add(r);
					} else {
						List<Road> l = new ArrayList<Road>();
						l.add(r);
						// TODO Need to put *new* coordinate here? Not use
						// existing one in memory?
						coordCache.put(new Coordinate(c), l);
					}
				}
			}
		}
	}
	/**
	 *Tính toán khoảng cách (tính bằng mét) giữa hai Tọa độ, sử dụng hệ thống tham chiếu tọa độ mà bản đồ đường đang sử dụng. 
	 *Đối với hiệu suất nó có thể trả lại góc (trong khoảng -0 đến 2PI) nếu returnVals truyền như là một đôi [2] 
	 *(khoảng cách được lưu trữ trong chỉ số 0 và góc lưu trữ trong chỉ số 1).
	 * 
	 * @param c1
	 * @param c2
	 * @param returnVals
	 *            Used to return both the distance and the angle between the two Coordinates. If null then the distance
	 *            is just returned, otherwise this array is populated with the distance at index 0 and the angle at
	 *            index 1.
	 * @return The distance between Coordinates c1 and c2.
	 */
	public static synchronized double distance(Coordinate c1, Coordinate c2, double[] returnVals) {
		// TODO check this now, might be different way of getting distance in new Simphony
		GeodeticCalculator calculator = new GeodeticCalculator(ContextManager.roadProjection.getCRS());
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		double distance = calculator.getOrthodromicDistance();
		if (returnVals != null && returnVals.length == 2) {
			returnVals[0] = distance;
			double angle = Math.toRadians(calculator.getAzimuth()); // Angle in range -PI to PI
			// Need to transform azimuth (in range -180 -> 180 and where 0 points north)
			// to standard mathematical (range 0 -> 360 and 90 points north)
			if (angle > 0 && angle < 0.5 * Math.PI) { // NE Quadrant
				angle = 0.5 * Math.PI - angle;
			} else if (angle >= 0.5 * Math.PI) { // SE Quadrant
				angle = (-angle) + 2.5 * Math.PI;
			} else if (angle < 0 && angle > -0.5 * Math.PI) { // NW Quadrant
				angle = (-1 * angle) + 0.5 * Math.PI;
			} else { // SW Quadrant
				angle = -angle + 0.5 * Math.PI;
			}
			returnVals[1] = angle;
		}
		return distance;
	}

	@Override
	public void clearCaches() {
		if (coordCache != null)
			coordCache.clear();
		if (nearestRoadCoordCache != null) {
			nearestRoadCoordCache.clear();
			nearestRoadCoordCache = null;
		}
	}
}

/* ****************************************************************************************************************************************/
/**
 * Lưu trữ đường gần nhất Phối hợp với mọi tòa nhà để có hiệu quả (các đại lý thường cần lấy từ các nhà trung tâm đến đường gần nhất).
 * Lớp này có thể được tuần tự để nếu dữ liệu GIS không thay đổi thì không phải tính lại mỗi lần.
 */
class NearestRoadCoordCache implements Serializable {

	private static Logger LOGGER = Logger.getLogger(NearestRoadCoordCache.class.getName());

	private static final long serialVersionUID = 1L;
	private Hashtable<Coordinate, Coordinate> theCache; // Bộ nhớ cache thực
	// Check that the road/building data hasn't been changed since the cache was last created
	// Kiểm tra xem dữ liệu road/building đã không được thay đổi kể từ khi bộ nhớ cache được tạo ra lần cuối
	private File buildingsFile;
	private File roadsFile;
	// The time that this cache was created, can be used to check data hasn't changed since
	private long createdTime;

	private GeometryFactory geomFac;

	private NearestRoadCoordCache(Geography<Building> buildingEnvironment, File buildingsFile,
			Geography<Road> roadEnvironment, File roadsFile, GeometryFactory geomFac)
			throws Exception {

		this.buildingsFile = buildingsFile;
		this.roadsFile = roadsFile;
		this.theCache = new Hashtable<Coordinate, Coordinate>();
		this.geomFac = geomFac;
	}

	public void clear() {
		this.theCache.clear();
	}
	
	public Coordinate get(Coordinate c) throws Exception {
		if (c == null) {
			throw new Exception("Route.NearestRoadCoordCache.get() error: the given coordinate is null.");
		}
		double time = System.nanoTime();
		Coordinate nearestCoord = this.theCache.get(c);
		if (nearestCoord != null) {			
			LOGGER.log(Level.FINER, "NearestRoadCoordCache.get() (using cache) - ("
					+ (0.000001 * (System.nanoTime() - time)) + "ms)");
			return nearestCoord;
		}		
		// If get here then the coord is not in the cache, agent not starting their journey from a house, search for
		// it manually. Search all roads in the vicinity, looking for the point which is nearest the person
		double minDist = Double.MAX_VALUE;
		Coordinate nearestPoint = null;
		Point coordGeom = this.geomFac.createPoint(c);

		// Note: could use an expanding envelope that starts small and gets bigger
		double bufferDist = GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LONG.dist;
		double bufferMultiplier = 1.0;
		Envelope searchEnvelope = coordGeom.buffer(bufferDist * bufferMultiplier).getEnvelopeInternal();
		StringBuilder debug = new StringBuilder(); // incase the operation fails		
		
		for (Road r : ContextManager.roadProjection.getObjectsWithin(searchEnvelope)) {
			DistanceOp distOp = new DistanceOp(coordGeom, ContextManager.roadProjection.getGeometry(r));
			double thisDist = distOp.distance();
			// BUG?: if an agent is on a really long road, the long road will not be found by getObjectsWithin because it is not within the buffer
			debug.append("\troad ").append(r.toString()).append(" is ").append(thisDist).append(
					" distance away (at closest point). ");

			if (thisDist < minDist) {				
				minDist = thisDist;
				@SuppressWarnings("deprecation")
				Coordinate[] closestPoints = distOp.closestPoints();
				// Two coordinates returned by closestPoints(), need to find the one which isn''t the coord parameter
				debug.append("Closest points (").append(closestPoints.length).append(") are: ").append(
						Arrays.toString(closestPoints));
				nearestPoint = (c.equals(closestPoints[0])) ? closestPoints[1] : closestPoints[0];
				debug.append("Nearest point is ").append(nearestPoint.toString());
				nearestPoint = (c.equals(closestPoints[0])) ? closestPoints[1] : closestPoints[0];
			} // if thisDist < minDist
			debug.append("\n");

		} // for nearRoads

		if (nearestPoint != null) {
			LOGGER.log(Level.FINER, "NearestRoadCoordCache.get() (not using cache) - ("+ (0.000001 * (System.nanoTime() - time)) + "ms)");
			return nearestPoint;
		}
		/* IF HERE THEN ERROR, PRINT DEBUGGING INFO */
		StringBuilder debugIntro = new StringBuilder(); // Some extra info for debugging
		debugIntro.append("Route.NearestRoadCoordCache.get() error: couldn't find a coordinate to return.\n");
		Iterable<Road> roads = ContextManager.roadProjection.getObjectsWithin(searchEnvelope);
		debugIntro.append("Looking for nearest road coordinate around ").append(c.toString()).append(".\n");
		debugIntro.append("RoadEnvironment.getObjectsWithin() returned ").append(
				ContextManager.sizeOfIterable(roads) + " roads, printing debugging info:\n");
		debugIntro.append(debug);
		throw new Exception(debugIntro.toString());
	}
	/**
	 * Used to create a new BuildingsOnRoadCache object. This function is used instead of the constructor directly so
	 * that the class can check if there is a serialised version on disk already. If not then a new one is created and
	 * returned.
	 * 
	 * @param buildingEnv
	 * @param buildingsFile
	 * @param roadEnv
	 * @param roadsFile
	 * @param geomFac
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("resource")
	public synchronized static NearestRoadCoordCache getInstance(Geography<Building> buildingEnv, File buildingsFile,
			Geography<Road> roadEnv, File roadsFile, File serialisedLoc, GeometryFactory geomFac) throws Exception {
		// See if there is a cache object on disk.
		if (serialisedLoc.exists()) {
			FileInputStream fis = null;
			ObjectInputStream in = null;
			NearestRoadCoordCache ncc = null;
			try {
				fis = new FileInputStream(serialisedLoc);
				in = new ObjectInputStream(fis);
				ncc = (NearestRoadCoordCache) in.readObject();
				in.close();
				// Check that the cache is representing the correct data and the modification dates are ok
				if (!buildingsFile.getAbsolutePath().equals(ncc.buildingsFile.getAbsolutePath())
						|| !roadsFile.getAbsolutePath().equals(ncc.roadsFile.getAbsolutePath())
						|| buildingsFile.lastModified() > ncc.createdTime || roadsFile.lastModified() > ncc.createdTime) {
					LOGGER.log(Level.FINE, "BuildingsOnRoadCache, found serialised object but it doesn't match the "
							+ "data (or could have different modification dates), will create a new cache.");
				} else {
					return ncc;
				}
			} catch (IOException ex) {
				if (serialisedLoc.exists())
					serialisedLoc.delete(); // delete to stop problems loading incomplete file next tinme
				throw ex;
			} catch (ClassNotFoundException ex) {
				if (serialisedLoc.exists())
					serialisedLoc.delete();
				throw ex;
			}
		}
		// No serialised object, or got an error when opening it, just create a new one
		return new NearestRoadCoordCache(buildingEnv, buildingsFile, roadEnv, roadsFile, geomFac);
	}
}