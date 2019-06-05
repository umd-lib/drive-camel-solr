package edu.umd.lib.process;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

/**
 * @author audani
 */

public class DriveDeleteProcessor extends AbstractSolrProcessor {

  private Map<String, String> config;
  private static Logger log = Logger.getLogger(DriveDeleteProcessor.class);

  public DriveDeleteProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public String generateMessage(Exchange exchange) throws Exception {
    String messageBody = SolrJsonGenerator.deleteJson(exchange);
    return messageBody;

  }

}
