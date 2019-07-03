package com.dub.spring.controller;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.dub.spring.minimumSpanningTree.DistMin;
import com.dub.spring.minimumSpanningTree.InitMessage;
import com.dub.spring.minimumSpanningTree.Worker;
import com.dub.spring.minimumSpanningTree.WorkerResponse;

/**
 * Unique worker rest endpoint 
 * On init POST each immediately 
 * returns its first WorkerResponse
 * Each worker has its own LocaDistanceArray with 3 rows
 * */
@RestController
public class WorkerRestEndpoint {
	
	private Worker worker;
	
	@RequestMapping(
			value = "/init", 
			method = RequestMethod.POST)
	public WorkerResponse init(@RequestBody InitMessage init) {
		/** 
		 * Used only one time for a given graph
		 * */
		worker = new Worker(init);
	
		DistMin distMin = worker.getMin();
		boolean finished = worker.isFinished();
		WorkerResponse response = new WorkerResponse(distMin, finished);
		
		return response;
	}
	
	@RequestMapping(
			value = "/step",
			method = RequestMethod.POST)
	public WorkerResponse step(@RequestBody DistMin distMin) {
		/**
		 * Used several times, 
		 * as long as local MWOE candidates are found
		 * First step is dummy
		 * */
		// first update adjacency
		if (worker.isFirst()) {
			System.out.println("First step");
			worker.setFirst(false);
		} else {
			worker.updateAdjacency(distMin);		
		}
	
		// find new local MWOE
		DistMin newDistMin = worker.getMin();
		boolean finished = worker.isFinished();
		WorkerResponse response = new WorkerResponse(newDistMin, finished);
	
		return response;
	}
	
}
