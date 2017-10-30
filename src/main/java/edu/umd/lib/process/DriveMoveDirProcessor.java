package edu.umd.lib.process;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

/**
 * @author audani
 */
public class DriveMoveDirProcessor extends EventProcessor {

  private static Logger log = Logger.getLogger(DriveMoveDirProcessor.class);
  private Map<String, String> config;

  public DriveMoveDirProcessor(Map<String, String> config) {
    this.config = config;

  }

  @Override
  public void process(Exchange exchange) {
    try {
      DrivePollEventProcessor processor = new DrivePollEventProcessor(this.config);

      String fileId = exchange.getIn().getHeader("source_id", String.class);
      Path oldPath = Paths.get(exchange.getIn().getHeader("old_path", String.class));
      Path updatedPath = Paths.get(exchange.getIn().getHeader("local_path", String.class));

      FileUtils.moveDirectory(oldPath.toFile(), updatedPath.toFile());

      processor.updateFileAttributeProperties(fileId, updatedPath.toString(), null);

    } catch (IOException e) {
      log.info("File Not found. Check the file paths.");
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

}