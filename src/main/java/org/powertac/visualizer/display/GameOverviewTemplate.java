package org.powertac.visualizer.display;

import java.util.ArrayList;

public class GameOverviewTemplate {

	@SuppressWarnings("unused") private String name;
	@SuppressWarnings("unused") private ArrayList<Double> data;
	@SuppressWarnings("unused") private String pointPlacement;

	public GameOverviewTemplate(String name, ArrayList<Double> data) {
		this.name = name;
		this.data = data;
		this.pointPlacement = "on";
	}

}
