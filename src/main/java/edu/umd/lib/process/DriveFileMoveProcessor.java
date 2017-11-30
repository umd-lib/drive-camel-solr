package edu.umd.lib.process;

import java.io.IOException;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

/**
 * @author audani
 */
public class DriveFileMoveProcessor extends EventProcessor {
  private static Logger log = Logger.getLogger(DriveFileMoveProcessor.class);
  private Map<String, String> config;

  public DriveFileMoveProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public void process(Exchange exchange) {
    try {
      DrivePollEventProcessor processor = new DrivePollEventProcessor(this.config);

      String fileId = exchange.getIn().getHeader("source_id", String.class);

      super.process(exchange);

    } catch (IOException e) {
      log.info("File Not found. Check the file paths.");
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

}
