package edu.umd.lib.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

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
      Path oldPath = Paths.get(exchange.getIn().getHeader("old_path", String.class));
      Path newPath = Paths.get(exchange.getIn().getHeader("local_path", String.class));

      Files.move(oldPath, newPath);
      processor.updateFileAttributeProperties(fileId, newPath.toString());

    } catch (IOException e) {
      log.info("File Not found. Check the file paths.");
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

}
