package edu.umd.lib.process;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

/**
 * @author audani
 */

public class DrivePathUpdateProcessor extends EventProcessor {

  private static Logger log = Logger.getLogger(DrivePathUpdateProcessor.class);
  private Map<String, String> config;

  public DrivePathUpdateProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    DrivePollEventProcessor processor = new DrivePollEventProcessor(this.config);

    String fileID = exchange.getIn().getHeader("source_id", String.class);
    String fullPath = exchange.getIn().getHeader("local_path", String.class);
    processor.updateFileAttributeProperties(fileID, fullPath, null);
    super.process(exchange);

  }

}
