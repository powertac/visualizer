package org.powertac.visualizer.user;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.powertac.visualizer.display.BrokerSeriesTemplate;
import org.powertac.visualizer.domain.broker.BrokerModel;
import org.powertac.visualizer.services.BrokerService;
import org.powertac.visualizer.services.handlers.VisualizerHelperService;
import org.powertac.visualizer.statistical.DynamicData;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.gson.Gson;

public class BalancingMarketBean implements Serializable {

	private static final long serialVersionUID = 1L;

	private String balancingDynData;
	private String balancingDynDataOneTimeslot;

	@Autowired
	public BalancingMarketBean(BrokerService brokerService, VisualizerHelperService helper) {

		Gson gson = new Gson();
		createBrokerBalancingTransactions(gson, brokerService, helper);
		
		

	}

	private void createBrokerBalancingTransactions(Gson gson,
			BrokerService brokerService, VisualizerHelperService helper) {
		Collection<BrokerModel> brokers = brokerService.getBrokers();

		
		ArrayList<Object> balancingTxData = new ArrayList<Object>();
		ArrayList<Object> balancingTxDataOneTimeslot = new ArrayList<Object>();

		// brokers:
		for (Iterator iterator = brokers.iterator(); iterator.hasNext();) {
			BrokerModel brokerModel = (BrokerModel) iterator.next();

			ArrayList<Object> profitData = new ArrayList<Object>();
			ArrayList<Object> netKwhData = new ArrayList<Object>();
			// one timeslot
			ArrayList<Object> profitDataOneTimeslot = new ArrayList<Object>();
			ArrayList<Object> kwhDataOneTimeslot = new ArrayList<Object>();
			
			ConcurrentHashMap<Integer, DynamicData> dynDataMap = brokerModel
					.getBalancingCategory().getDynamicDataMap();
			SortedSet<Integer> dynDataSet = new TreeSet<Integer>(dynDataMap.keySet());

		
			// dynamic balancing data:
			for (Iterator iterator2 = dynDataSet.iterator(); iterator2
					.hasNext();) {
				int key = (Integer) iterator2.next();
				DynamicData dynData = dynDataMap.get(key);
								
				Object[] profit = { helper.getMillisForIndex(key),
						dynData.getProfit()};
				Object[] netMwh = { helper.getMillisForIndex(key), dynData.getEnergy() };

				profitData.add(profit);
				netKwhData.add(netMwh);

				// one timeslot:
				Object[] profitOneTimeslot = { helper.getMillisForIndex(key),
						dynData.getProfitDelta() };
				Object[] kWhOneTimeslot = { helper.getMillisForIndex(key),
						dynData.getEnergyDelta() };
				profitDataOneTimeslot.add(profitOneTimeslot);
				kwhDataOneTimeslot.add(kWhOneTimeslot);
			}
			if(dynDataSet.size()==0){
				//dummy:
				double[] dummy = { helper.getMillisForIndex(0), 0};
				profitData.add(dummy);
				profitDataOneTimeslot.add(dummy);
				kwhDataOneTimeslot.add(dummy);
				netKwhData.add(dummy);
			}

			balancingTxData.add(new BrokerSeriesTemplate(brokerModel.getName()
					+ " PRICE", brokerModel.getAppearance().getColorCode(), 0,
					profitData));
			balancingTxData.add(new BrokerSeriesTemplate(brokerModel.getName()
					+ " KWH", brokerModel.getAppearance().getColorCode(), 1,
					netKwhData));

			// one timeslot:
			balancingTxDataOneTimeslot.add(new BrokerSeriesTemplate(brokerModel
					.getName() + " PRICE", brokerModel.getAppearance()
					.getColorCode(), 0, profitDataOneTimeslot));
			balancingTxDataOneTimeslot.add(new BrokerSeriesTemplate(brokerModel
					.getName() + " KWH", brokerModel.getAppearance()
					.getColorCode(), 1, kwhDataOneTimeslot));


		}
		this.balancingDynData = gson.toJson(balancingTxData);
		this.balancingDynDataOneTimeslot = gson
				.toJson(balancingTxDataOneTimeslot);

	}
	
	public String getBalancingDynData() {
		return balancingDynData;
	}
	
	public String getBalancingDynDataOneTimeslot() {
		return balancingDynDataOneTimeslot;
	}



}
