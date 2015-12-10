package org.powertac.visualizer.beans.backing;

import org.springframework.stereotype.Service;

@Service
public class TrainingService {

	private int counter=0;
	
	public int getCounter() {
		return counter;
	}
	public void increment() {
		counter++;
		
	}
}
