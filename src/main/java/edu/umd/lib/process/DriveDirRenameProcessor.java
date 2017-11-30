package edu.umd.lib.process;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

/**
 * @author audani
 */

public class DriveDirRenameProcessor extends EventProcessor {

  private static Logger log = Logger.getLogger(DriveDirRenameProcessor.class);
  private Map<String, String> config;

  public DriveDirRenameProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public void process(Exchange exchange) {
    try {
      DrivePollEventProcessor processor = new DrivePollEventProcessor(this.config);

      String fileId = exchange.getIn().getHeader("source_id", String.class);

    } catch (Exception e) {
      e.printStackTrace();
    }

  }

}
