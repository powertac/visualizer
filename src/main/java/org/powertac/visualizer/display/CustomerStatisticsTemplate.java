package org.powertac.visualizer.display;

/**
 * A template class which resembles the structure needed for HighStock charts
 * (JSON library).
 * 
 * @author Jurica Babic
 * 
 */
public class CustomerStatisticsTemplate {

	@SuppressWarnings("unused") private String name;
	@SuppressWarnings("unused") private String color;
	@SuppressWarnings("unused") private Object drilldown;
	@SuppressWarnings("unused") private long y;

	public CustomerStatisticsTemplate(String name, String color, long y,
			Object drilldown) {
		this.y = y;
		this.name = name;
		this.color = color;
		this.drilldown = drilldown;
	}

}
