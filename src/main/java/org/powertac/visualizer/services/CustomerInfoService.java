package org.powertac.visualizer.services;

import com.google.gson.Gson;
import org.powertac.common.TariffTransaction;
import org.powertac.common.TariffTransaction.Type;
import org.powertac.visualizer.display.BrokerSeriesTemplate;
import org.powertac.visualizer.domain.customer.Customer;
import org.powertac.visualizer.services.handlers.VisualizerHelperService;
import org.primefaces.event.TabChangeEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;


@Service
public class CustomerInfoService
{
  @Autowired
  VisualizerHelperService helper;
  @Autowired
  CustomerService customerService;

  private ArrayList<Customer> customers;
  private int customerType = 0;

  public ArrayList<Customer> getCustomers ()
  {
    return customers;
  }

  public CustomerService getCustomerService ()
  {
    return customerService;
  }

  public String getWholesaleDynDataOneTimeslot ()
  {
    Gson gson = new Gson();
    ArrayList<Object> wholesaleTxDataOneTimeslot = new ArrayList<Object>();
    ArrayList<Object> profitDataOneTimeslot = new ArrayList<Object>();
    ArrayList<Object> mwhDataOneTimeslot = new ArrayList<Object>();

    for (TariffTransaction tx : customerService.getTariffTransactions(customerType)) {
      if (tx.getTxType() == Type.CONSUME || tx.getTxType() == Type.PRODUCE) {
        Object[] profitOneTimeslot =
            {helper.getMillisForIndex(tx.getPostedTimeslotIndex()),
                tx.getCharge()};
        Object[] kWhOneTimeslot =
            {helper.getMillisForIndex(tx.getPostedTimeslotIndex()), tx.getKWh()};

        profitDataOneTimeslot.add(profitOneTimeslot);
        mwhDataOneTimeslot.add(kWhOneTimeslot);
      }
    }

    wholesaleTxDataOneTimeslot
        .add(new BrokerSeriesTemplate("Price(€)", "#808080", 0,
            profitDataOneTimeslot, true));
    wholesaleTxDataOneTimeslot.add(new BrokerSeriesTemplate("Energy(MWh)",
        "#8BBC21", 1,
        mwhDataOneTimeslot,
        true));

    return gson.toJson(wholesaleTxDataOneTimeslot);
  }

  public void onChange (TabChangeEvent event)
  {
    customerType = customerService.getCustomerType(event.getData().toString());
  }
}
