package org.powertac.visualizer.domain.wholesale;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.powertac.common.Competition;
import org.powertac.common.Order;
import org.powertac.common.Timeslot;
import org.powertac.visualizer.domain.wholesale.WholesaleSnapshot;

public class WholesaleSnapshotTest {

  private WholesaleSnapshot wholesaleSnapshot;
  private Timeslot timeslot = new Timeslot(0, null);

  @Before
  public void setUp() throws Exception {
    wholesaleSnapshot = new WholesaleSnapshot(12345, timeslot, 360);


  }

  @Test
  public void test() {

    Competition.newInstance("VizTest");
    int num = timeslot.getSerialNumber();
    wholesaleSnapshot.addOrder(new Order(null, num, -1.0, null));
    wholesaleSnapshot.addOrder(new Order(null, num, -2.0, 2.0));
    wholesaleSnapshot.addOrder(new Order(null, num, -3.0, 3.0));

    wholesaleSnapshot.addOrder(new Order(null, num, 4.0, -4.0));
    wholesaleSnapshot.addOrder(new Order(null, num, 5.0, null));
    wholesaleSnapshot.addOrder(new Order(null, num, 6.0, -6.0));

    VisualizerOrderbook orderbookWithNulls = new VisualizerOrderbook(timeslot, 0.0, null).addAsk(new VisualizerOrderbookOrder(10, null)).addBid(new VisualizerOrderbookOrder(-10, null));
    wholesaleSnapshot.setOrderbook(orderbookWithNulls);
    wholesaleSnapshot.close();
    assertEquals("Wholesale snapshot should be successfully closed", wholesaleSnapshot.isClosed(), true);

    VisualizerOrderbook orderbookWithoutNulls = new VisualizerOrderbook(timeslot, 0.0, null).addAsk(new VisualizerOrderbookOrder(10, -10.0)).addBid(new VisualizerOrderbookOrder(-10, 10.0));
    wholesaleSnapshot.setOrderbook(orderbookWithoutNulls);
    wholesaleSnapshot.close();
    assertEquals("Wholesale snapshot should be successfully closed", wholesaleSnapshot.isClosed(), true);



  }

}
