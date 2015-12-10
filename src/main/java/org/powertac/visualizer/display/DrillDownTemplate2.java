package org.powertac.visualizer.display;

/**
 * A template class which resembles the structure needed for HighStock charts
 * (JSON library).
 * 
 * @author Jurica Babic
 * 
 */
public class DrillDownTemplate2 {

	@SuppressWarnings("unused") private String name;
	@SuppressWarnings("unused") private long y;
	//private String color;
	@SuppressWarnings("unused") private Object drilldown;

	public DrillDownTemplate2(String name, long y, Object drilldown) {
		this.name = name;
		//this.color = color;
		this.drilldown = drilldown;
		this.y = y;
	}

}
