package edu.umd.lib.process;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

/**
 * @author audani
 */

public class DriveDeleteProcessor extends EventProcessor {

  private Map<String, String> config;
  private static Logger log = Logger.getLogger(DriveDeleteProcessor.class);

  public DriveDeleteProcessor(Map<String, String> config) {
    this.config = config;
  }

  /**
   * Deletes a file or a folder and its children, specified by header
   * "source_path" and constructs a JSON string for Solr deletion.
   */
  @Override
  public void process(Exchange exchange) throws Exception {

    // create JSON for Solr Exchange
    DrivePollEventProcessor processor = new DrivePollEventProcessor(this.config);
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    processor.updateFileAttributeProperties(sourceID, null, "delete");
    log.info("Creating JSON for deleting file with ID:" + sourceID);
    super.process(exchange);

  }

}
