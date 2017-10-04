package edu.umd.lib.process;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import edu.umd.lib.services.GoogleDriveConnector;

/**
 * @author audani
 */

public class DriveDownloadProcessor extends EventProcessor {

  private static Logger log = Logger.getLogger(DriveDownloadProcessor.class);
  private Map<String, String> config;

  public DriveDownloadProcessor(Map<String, String> config) {

    this.config = config;
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    GoogleDriveConnector connector = new GoogleDriveConnector(this.config);
    Drive service = connector.getDriveService();
    DrivePollEventProcessor processor = new DrivePollEventProcessor(this.config);
    
    // Get file & get its file type
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    File file = service.files().get(sourceID)
        .setSupportsTeamDrives(true)
        .execute();
    String sourceMimeType = file.getMimeType();
    String fileName = file.getName();

    if (sourceMimeType != null)

    {

      // Get download destination
      String fullFilePath = exchange.getIn().getHeader("local_path", String.class);

      // Create paths to destination file if they don't exist
      Path outputFile = Paths.get(fullFilePath);
      if (Files.notExists(outputFile)) {
        Path dir = outputFile.getParent();
        Files.createDirectories(dir);
        Files.createFile(outputFile);
      }

      // Export file to output stream
      OutputStream out = Files.newOutputStream(outputFile);

      service.files().get(sourceID).executeMediaAndDownloadTo(out);

      out.flush();
      out.close();
      
      processor.updateFileAttributeProperties(sourceID, fullFilePath, null);
      // create JSON for SolrUpdater exchange
      log.info("Creating JSON for indexing Google Drive file: " + fileName);
      super.process(exchange);

    }

  }

}
