package edu.umd.lib.process;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import edu.umd.lib.services.GoogleDriveConnector;

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

    // Get file & get its file type
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    File file = service.files().get(sourceID)
        .setSupportsTeamDrives(true)
        .execute();
    String sourceMimeType = file.getMimeType();
    String downloadMimeType = sourceMimeType;
    boolean isGoogleDoc = false;
    String googleDocExtension = null;

    // If the file is a Google document, we set the mime type
    if (sourceMimeType.equals("application/vnd.google-apps.document")) {
      // Download google documents as MS Word files
      isGoogleDoc = true;
      googleDocExtension = ".docx";
      downloadMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    } else if (sourceMimeType.equals("application/vnd.google-apps.spreadsheet")) {
      // Download google spreadsheets as MS Excel files
      isGoogleDoc = true;
      googleDocExtension = ".xlsx";
      downloadMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    } else if (sourceMimeType.equals("application/vnd.google-apps.drawing")) {
      // Download google drawings as jpegs
      isGoogleDoc = true;
      googleDocExtension = ".jpg";
      downloadMimeType = "image/jpeg";
    } else if (sourceMimeType.equals("application/vnd.google-apps.presentation")) {
      // Download google slides as MS powerpoint
      isGoogleDoc = true;
      googleDocExtension = ".pptx";
      downloadMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    } else if (sourceMimeType.equals("application/vnd.google-apps.script")) {
      // Download google apps scripts as JSON
      isGoogleDoc = true;
      googleDocExtension = ".json";
      downloadMimeType = "application/vnd.google-apps.script+json";
    }

    if (downloadMimeType != null) {

      // Get download destination
      String fullFilePath = exchange.getIn().getHeader("local_path", String.class);

      if (isGoogleDoc) {
        fullFilePath += googleDocExtension;
        exchange.getIn().setHeader("local_path", fullFilePath);
      }

      // Create paths to destination file if they don't exist
      java.io.File outputFile = new java.io.File(fullFilePath);
      if (!outputFile.exists()) {
        java.io.File dir = outputFile.getParentFile();
        dir.mkdirs();
        outputFile.createNewFile();
      }

      // Export file to output stream
      OutputStream out = new FileOutputStream(outputFile);

      if (isGoogleDoc) {
        service.files().export(sourceID, downloadMimeType).executeMediaAndDownloadTo(out);
      } else {
        service.files().get(sourceID).executeMediaAndDownloadTo(out);
      }
      out.flush();
      out.close();

      // create JSON for SolrUpdater exchange
      log.info("Creating JSON for indexing Google Drive file with ID:" + sourceID);
      super.process(exchange);

    } else {
      log.info("Cannot download google file of type: " + sourceMimeType);
    }

  }

}
