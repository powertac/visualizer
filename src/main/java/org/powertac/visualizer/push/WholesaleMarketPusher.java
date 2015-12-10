package org.powertac.visualizer.push;


public class WholesaleMarketPusher {
	@SuppressWarnings("unused") private String name;
	@SuppressWarnings("unused") private long millis;
	@SuppressWarnings("unused") private double  profit;
	@SuppressWarnings("unused") private double  energy;
	@SuppressWarnings("unused") private double  profitDelta;
	@SuppressWarnings("unused") private double  energyDelta;
	

	public WholesaleMarketPusher(String name, long millis, double  profitDelta, double  energyDelta, double  profit, double  energy ) {
		this.name = name;
		this.millis = millis;
		this.profitDelta = profitDelta;
		this.energyDelta = energyDelta;	
		this.energy = energy;
		this.profit = profit;
	}

}
