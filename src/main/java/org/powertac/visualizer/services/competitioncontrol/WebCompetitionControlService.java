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
    message = "Unable to run sim in tournament configuration.";
  }

  public void runBoot ()
  {
    message = "Unable to run boot in tournament configuration.";
  }

  public void shutDown ()
  {
    message = "not allowed in tournamet configuration";
  }

  public String getMessage ()
  {
    return message;
  }
}
