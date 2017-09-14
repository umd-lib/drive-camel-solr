package edu.umd.lib.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

/**
 * @author audani
 */

public class DriveMakedirProcessor extends EventProcessor {

  private static Logger log = Logger.getLogger(DriveMakedirProcessor.class);
  private Map<String, String> config;

  public DriveMakedirProcessor(Map<String, String> config) {

    this.config = config;
  }

  /**
   * Makes a directory in this project's sync folder. If directory's parent
   * folders do not exist, it creates them as well.
   */
  @Override
  public void process(Exchange exchange) throws Exception {

    DrivePollEventProcessor processor = new DrivePollEventProcessor(this.config);
    String fileId = exchange.getIn().getHeader("source_id", String.class);
    String destPath = exchange.getIn().getHeader("local_path", String.class);

    Path dir = Paths.get(destPath);

    if (Files.notExists(dir)) {
      try {
        Files.createDirectories(dir);
        log.info("Directories created: " + destPath);
        processor.updateFileAttributeProperties(fileId, destPath);
      } catch (Exception ex) {
        log.info("Failed to create directories: " + destPath);
        ex.printStackTrace();
      }
    }
  }

}
