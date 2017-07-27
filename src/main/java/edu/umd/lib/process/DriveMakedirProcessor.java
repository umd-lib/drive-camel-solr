package edu.umd.lib.process;

import java.io.File;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

public class DriveMakedirProcessor extends DownloadProcessor {

  private static Logger log = Logger.getLogger(DriveMakedirProcessor.class);

  /**
   * Makes a directory in this project's sync folder. If directory's parent
   * folders do not exist, it creates them as well.
   */
  @Override
  public void process(Exchange exchange) throws Exception {

    String destPath = exchange.getIn().getHeader("local_path", String.class);

    File dir = new File(destPath);

    if (!dir.exists()) {
      if (dir.mkdirs()) {
        log.info("Directories created: " + destPath);
      } else {
        log.info("Failed to create directories: " + destPath);
      }
    }

  }

}
