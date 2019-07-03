package com.dub.spring.controller;

import java.util.Arrays;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestOperations;

import com.dub.spring.minimumSpanningTree.DFSGraph;
import com.dub.spring.minimumSpanningTree.DistMin;
import com.dub.spring.minimumSpanningTree.DistanceMatrix;
import com.dub.spring.minimumSpanningTree.GraphInitRequest;
import com.dub.spring.minimumSpanningTree.GraphServices;
import com.dub.spring.minimumSpanningTree.InitMessage;
import com.dub.spring.minimumSpanningTree.JSONEdge;
import com.dub.spring.minimumSpanningTree.JSONSnapshot;
import com.dub.spring.minimumSpanningTree.JSONVertex;
import com.dub.spring.minimumSpanningTree.MSTAnimResponse;
import com.dub.spring.minimumSpanningTree.MSTAnimResponse.StatusCode;
import com.dub.spring.minimumSpanningTree.MSTGraph;
import com.dub.spring.minimumSpanningTree.MSTResponse;
import com.dub.spring.minimumSpanningTree.MSTVertex;
import com.dub.spring.minimumSpanningTree.SearchRequest;
import com.dub.spring.minimumSpanningTree.StepResult;
import com.dub.spring.minimumSpanningTree.WeightedEdge;
import com.dub.spring.minimumSpanningTree.WorkerResponse;

/** 
 * This is the main supervisor class. 
 * It receives a local MWOE from each worker 
 * and computes a global MWOE. 
 * A while loop runs as long as all workers are not finished.
 * */
@Controller
public class MSTController {
	
	@Value("${worker-ports}")
	String workerPortsStr;
	
	// using a service layer
	@Autowired
	private GraphServices graphServices;

	@Autowired
	private RestOperations restTemplate;
	
	int N;// component size
	int Nworkers;
	
	private DFSGraph graph = null;
	private MSTGraph comp = null;
	
	private MSTAnimResponse mstAnimResponse;
	private DistMin restMinMin;
	private String[] workerPorts;
	private Integer[] finalDistances;
	private String[] workerURIs;

	private InitMessage[] initMessages;
	private boolean[] workerFinished;
	private int[][] workerVertices; 
	private boolean finished;
	
	private WorkerStepWrapper wrapper;
	
	int cost;// running cost
	
	private DistMin[] restDistMin;
	
	@PostConstruct
	void init() {
		workerPorts = workerPortsStr.split(",");
		Nworkers = workerPorts.length;
			
		workerURIs = new String[Nworkers];
		
		workerFinished = new boolean[Nworkers];
		initMessages = new InitMessage[Nworkers];
		
		restDistMin = new DistMin[Nworkers];
		
		for (int i = 0; i < Nworkers; i++) {
			workerURIs[i] = "http://localhost:" + workerPorts[i] + "/worker";
		}
				
		for (int i = 0; i < Nworkers; i++) {
			workerFinished[i] = false;
		}
	
	}// init 
	
	
	/** Initialize graph for both automatic and stepwise search */
	@RequestMapping(value="/initGraph")
	@ResponseBody
	public MSTResponse initGraph(@RequestBody GraphInitRequest message, 
				HttpServletRequest request) 
	{	
		List<JSONEdge> jsonEdges = message.getJsonEdges();
		List<JSONVertex> jsonVertices = message.getJsonVertices();
		
		graph = graphServices.jsonToDFS(jsonEdges, jsonVertices);
					
		MSTResponse mstResponse = new MSTResponse();
		mstResponse.setStatus(MSTResponse.Status.OK);
	
		System.out.println("graph constructed");
		
		graph.display2();
			
		// here the graph is ready for the search loop
		System.out.println("initGraph completed");
			
		return mstResponse;
	}// initGraph
	
	@RequestMapping(value="/findComp")
	@ResponseBody
	public StepResult findComp(@RequestBody SearchRequest message, 
				HttpServletRequest request) 
	{	
		System.out.println("findComp begin");
		
		comp = graph.getComp();// global variable
		
		N = comp.getVertices().length;
			
		JSONSnapshot snapshot = graphServices.graphToJSON(comp);
		
		snapshot.displayVertices();
		snapshot.displayAdj();
		
		// check graph
		graph.display();
		
		// find the largest component  
		StepResult mstResponse = new StepResult();
		mstResponse.setStatus(StepResult.Status.INIT);
		
		mstResponse.setSnapshot(snapshot);
		
		// create DistanceMatrix and add it to comp
		DistanceMatrix distMat = new DistanceMatrix(comp);
		comp.setDistanceMatrix(distMat);
		
		distMat.display();
		
		int[] vertices = new int[N];
		Arrays.setAll(vertices, p -> p);
		
		for (int k = 0; k < N; k++) {
			System.out.print(vertices[k] + " ");
		}
		System.out.println();
		
		int[] boundaries = new int[Nworkers -1];
		
		for (int i = 0; i < Nworkers - 1; i++) {
			boundaries[i] = (i + 1) * N / 3 + 1;
		}
		
		workerVertices = new int[Nworkers][];
		
		for (int i = 0; i < Nworkers; i++) {
			if (i == 0) {
				workerVertices[i] = Arrays.copyOfRange(vertices, 1, boundaries[i]);
			} else if (i == Nworkers - 1) {
				workerVertices[i] = Arrays.copyOfRange(vertices, boundaries[i-1], vertices.length);
			} else {
				workerVertices[i] = Arrays.copyOfRange(vertices, boundaries[i-1], boundaries[i]);
			}
		}
				
		for (int i = 0; i < Nworkers; i++) {
			initMessages[i] = new InitMessage(distMat, workerVertices[i]);
		}
			
		initMessages[0].display();
		initMessages[1].display();
		initMessages[2].display();
		
		// RESTful workers initialization
		for (int i = 0; i < Nworkers; i++) {
			restDistMin[i] = workerInit(workerURIs[i] + "/init", initMessages[i]);
		}
				
		// find minMin
		restMinMin = getMinMin(restDistMin);
			
		// create helper array
		finalDistances = new Integer[N];
		finalDistances[0] = 0;
		for (int j = 1; j < N; j++) {
			finalDistances[j] = null;
		}
		
		mstAnimResponse = new MSTAnimResponse();
		
		for (int i = 0; i < Nworkers; i++) {
			workerFinished[i] = false;
		}
		
		// return to the browser a weighted undirected connected graph
		System.out.println("findComp return");
		
		return mstResponse;
	}// findComp
	
	
	@RequestMapping(value="/search")
	@ResponseBody
	public MSTAnimResponse search(@RequestBody SearchRequest message, 
			HttpServletRequest request) {
			
		/**
		 * This is where the algorithm core is implemented
		 * The while loop broadcasts the last MWOE to all workers
		 * and receives a new MWOE candidate from each worker.
		 * Then a new MWOE is computed for the next iteration 
		 */
		finished = false;
		while (!finished) {
			JSONSnapshot stepResult = updateSnapshot();
			mstAnimResponse.getSnapshots().add(stepResult);
		}
		mstAnimResponse.setStatus(StatusCode.OK);
		
		return mstAnimResponse;
	}
	
	
	private DistMin workerInit(String workerInitURI, InitMessage initMessage) {
		/**
		 * Here a worker listening on workerInitURI is initialized
		 * */
		try {				
			ResponseEntity<WorkerResponse> response 
					= restTemplate.postForEntity(workerInitURI, initMessage, WorkerResponse.class);

			WorkerResponse wr = response.getBody();
			
			DistMin distMin = wr.getDistMin();

			return distMin;
		} catch (HttpClientErrorException e) {
					throw new RuntimeException();
		}
	}
	
	private DistMin getMinMin(DistMin[] m) {
		/** 
		 * Only used in worker initialization 
		 * */
		DistMin min = m[0];
		int j = 0;
		int jMin = 0;
		for (j = 0; j < m.length; j++) {
			if (m[j].getDistance() != null) {			
				if (min.compareTo(m[j]) == 1) {
					min = m[j];
					jMin = j;
				} 
			}
		}
		return m[jMin];
	}

	
	
	private DistMin getMinMin(DistMin[] m, boolean[] finished) {
		/** 
		 * Computes the global MWOE from local MWOEs returned by workers
		 *  return null if all workers are finished 
		 *  */
		int j0 = 0;
		while (j0 < m.length && finished[j0]) {
			j0++;
		}
		if (j0 == Nworkers) {
			return null;// finished
		}
		
		DistMin min = m[j0];
		
		int jMin = j0;
		for (int j = j0; j < m.length; j++) {
			if (m[j].getDistance() != null && !finished[j]) {			
				if (min.compareTo(m[j]) == 1) {
					min = m[j];
					jMin = j;
				} 
			}
		}
		return m[jMin];
	}
	

	private JSONSnapshot updateSnapshot() {
			
		broadcastAll();// here happens the RESTful broadcast to all workers 
		
		if (restMinMin == null) {
			// algorithm finished, only display result
			JSONSnapshot snapshot = graphServices.graphToJSON(comp);
			
			return snapshot; 
		}
		
		// adding MWOE to MST
		MSTVertex v = (MSTVertex)comp.getVertices()[restMinMin.getVertex()];
		v.setParent(restMinMin.getRefVertex());
			
		// update finalDistance here
		finalDistances[restMinMin.getVertex()] 
				= comp.getDistanceMatrix().getDistance(restMinMin.getVertex(), restMinMin.getRefVertex()) 
				+ finalDistances[restMinMin.getRefVertex()];
			
		if (workerFinished[0] && workerFinished[1] && workerFinished[2]) {
			System.out.println("Algorithm finished");
		}
		
		displayFinalDistances();
		
		// update component vertices
		for (int j = 0; j < N; j++) {// for each vertex
			int key = (finalDistances[j] != null) ? finalDistances[j] : 1000;		
					((MSTVertex)comp.getVertices()[j]).setKey(key);
		}
			
		// update running cost
		updateRunningCost();
		
		JSONSnapshot snapshot = graphServices.graphToJSON(comp);
		
		return snapshot;
	}// updateSnapshot

	private void displayFinalDistances() {
		System.out.println("\nfinalDistances");
		for (int j = 0; j < N; j++) {
			System.out.print(finalDistances[j] + " ");
		}
		System.out.println();
	}// displayFinalDistances

	private void updateRunningCost() {
		cost = 0;// first reset
		for (int j = 0; j < N; j++) {// for each vertex
			MSTVertex u1 = (MSTVertex)comp.getVertices()[j];
			List<WeightedEdge> conn1 = u1.getAdjacency();// all edges from u1 
			for (int k = 0; k < conn1.size(); k++) {
				int iv1 = conn1.get(k).getTo();
				MSTVertex v1 = (MSTVertex)comp.getVertices()[iv1];
				if (v1.getParent() != null && v1.getParent() == j) {
					cost += conn1.get(k).getWeight();
				}// if
			}// for
		}// for
			
		comp.setCost(cost);
	}// updateRunningCost

	private void broadcastAll() {
		/**
		 * Here is the communication 
		 * between supervisor and workers
		 * */
		for (int i = 0; i < Nworkers; i++) {
			if (!workerFinished[i]) {
				wrapper = this.workerStep(workerURIs[i] + "/step", restMinMin);	
				restDistMin[i] = wrapper.getDistMin();
				workerFinished[i] = wrapper.isFinished();	
			}// if
		}// for
			
		finished = true;
		for (int k = 0; k < Nworkers; k++) {
			finished &= workerFinished[k];
		}
		
		// find minMin
		restMinMin = getMinMin(restDistMin, workerFinished);
						
	}// broadcastAll

	//helper wrapper class
	private static class WorkerStepWrapper {
		
		DistMin distMin;
		boolean finished;
		
		public WorkerStepWrapper(DistMin distMin, boolean finished) {
			this.distMin = distMin;
			this.finished = finished;
		}

		public DistMin getDistMin() {
			return distMin;
		}

		public boolean isFinished() {
			return finished;
		}
	}// WorkerStepWrapper
	
	private WorkerStepWrapper workerStep(String workerStepURI, DistMin minMin) {
		/**
		 * A simple REST request to workers
		 * */
		try {				
			ResponseEntity<WorkerResponse> response 
					= restTemplate.postForEntity(workerStepURI, minMin, WorkerResponse.class);

			WorkerResponse wr = response.getBody();
			
			DistMin distMin = wr.getDistMin();
			boolean finished = wr.getStatus().equals(WorkerResponse.Code.FINISHED) ? true : false;
			return new WorkerStepWrapper(distMin, finished);
		} catch (HttpClientErrorException e) {
					throw new RuntimeException();
		}
	}// workerStep
}
