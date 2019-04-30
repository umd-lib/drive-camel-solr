package edu.umd.lib.process;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

/**
 * @author audani
 */
public class DriveFileMoveProcessor extends AbstractSolrProcessor {
  private static Logger log = Logger.getLogger(DriveFileMoveProcessor.class);
  private Map<String, String> config;

  public DriveFileMoveProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  String generateMessage(Exchange exchange) throws Exception {
    String messageBody = SolrJsonGenerator.moveFileJson(exchange);
    return messageBody;
  }

}
