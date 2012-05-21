package org.powertac.visualizer.services.competitioncontrol;

import org.springframework.stereotype.Service;

/**
 * Purpose of this service is to allow (very) primitive control of PowerTAC
 * simulator. Feel free to change this class to match your requirements.
 * 
 * @author Jurica Babic
 * 
 */
@Service
public class WebCompetitionControlService
{

  private String message;

  public void runSim ()
  {
    message = "Unable to run a sim game in the tournament config";
  }

  public void runBoot ()
  {
    message = "Unable to run a bootstrap gamein the tournament config.";
  }

  public void shutDown ()
  {
    message = "There is no running game to shut down.";
  }

  public String getMessage ()
  {
    return message;
  }
}
