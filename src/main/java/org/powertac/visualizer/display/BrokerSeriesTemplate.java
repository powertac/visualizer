package org.powertac.visualizer.display;

import java.util.ArrayList;

/**
 * A template class which resembles the structure needed for HighStock charts
 * (JSON library).
 * 
 * @author Jurica Babic
 * 
 */
public class BrokerSeriesTemplate {

	@SuppressWarnings("unused") private String name;
	@SuppressWarnings("unused") private String color;
	@SuppressWarnings("unused") private ArrayList<Object> data;
	@SuppressWarnings("unused") private int yAxis;
	@SuppressWarnings("unused") private boolean step;
	@SuppressWarnings("unused") private boolean showInLegend;
	@SuppressWarnings("unused") private Tooltip tooltip = new Tooltip();
	

	public BrokerSeriesTemplate(String name, String color,
			ArrayList<Object> data, boolean showInLegend) {
		this.name = name;
		this.color = color;
		this.data = data;
		this.showInLegend = showInLegend;
	}
	
	public BrokerSeriesTemplate(String name, String color, int yAxis,
			ArrayList<Object> data) {
		this.yAxis = yAxis;
		this.name = name;
		this.color = color;
		this.data = data;
	}

	public BrokerSeriesTemplate(String name, String color, int yAxis,
			boolean step, ArrayList<Object> data) {
		this.yAxis = yAxis;
		this.name = name;
		this.color = color;
		this.data = data;
		this.step = step;
	}

	public BrokerSeriesTemplate(String name, String color, int yAxis,
			ArrayList<Object> data, boolean showInLegend) {
		this.yAxis = yAxis;
		this.name = name;
		this.color = color;
		this.data = data;
		this.showInLegend = showInLegend;
	}

	public BrokerSeriesTemplate(String name, String color, int yAxis,
			boolean step, ArrayList<Object> data, boolean showInLegend) {
		this.yAxis = yAxis;
		this.name = name;
		this.color = color;
		this.data = data;
		this.step = step;
		this.showInLegend = showInLegend;
	}
	
	private class Tooltip{
		@SuppressWarnings("unused") private int valueDecimals = 2;
	}

}
