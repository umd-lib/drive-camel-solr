package edu.umd.lib.process;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import edu.umd.lib.services.GoogleDriveConnector;

public class GoogleDriveDownloadProcessor extends DownloadProcessor {

  private static Logger log = Logger.getLogger(GoogleDriveDownloadProcessor.class);
  private Map<String, String> config;
  
  public GoogleDriveDownloadProcessor(Map<String,String> config) {
	  
	  this.config = config;
  }

  @Override
  public void process(Exchange exchange) throws Exception {
    // To download a file from Google Drive, you need an authorized drive client
    // service and a file ID

    
    //ProducerTemplate dummy = new DefaultCamelContext().createProducerTemplate();
   // GoogleDriveConnector connector = new GoogleDriveConnector(account, dummy);
	  GoogleDriveConnector connector = new GoogleDriveConnector(this.config);
	  Drive service = connector.getDriveService();

    // Get file & get its file type
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    File file = service.files().get(sourceID)
    		.setSupportsTeamDrives(true)
    		.execute();
    String sourceMimeType = file.getMimeType();
    String downloadMimeType = sourceMimeType;

    // Choose a download type
    // All options listed here:
    // https://developers.google.com/drive/v3/web/manage-downloads
    if (sourceMimeType.equals("application/vnd.google-apps.document") || sourceMimeType.equals("application/pdf")) {
      // Download google documents as PDFs
      downloadMimeType = "application/pdf";
    } else if (sourceMimeType.equals("application/vnd.google-apps.spreadsheet")) {
      // Download google spreadsheets as MS Excel files
      downloadMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    } else if (sourceMimeType.equals("application/vnd.google-apps.drawing")) {
      // Download google drawings as jpegs
      downloadMimeType = "image/jpeg";
    } else if (sourceMimeType.equals("application/vnd.google-apps.presentation")) {
      // Download google slides as MS powerpoint
      downloadMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    } else if (sourceMimeType.equals("application/vnd.google-apps.script")) {
      // Download google apps scripts as JSON
      downloadMimeType = "application/vnd.google-apps.script+json";
    }

    if (downloadMimeType != null) {

      // Get download destination
      String sourcePath = exchange.getIn().getHeader("source_path", String.class);
   
      // Create paths to destination file if they don't exist
      java.io.File outputFile = new java.io.File(sourcePath);
      if (!outputFile.exists()) {
        java.io.File dir = outputFile.getParentFile();
        dir.mkdirs();
        outputFile.createNewFile();
      }

      // Export file to output stream
      OutputStream out = new FileOutputStream(outputFile);
      service.files().get(sourceID).executeMediaAndDownloadTo(out);
      out.flush();
      out.close();

      // create JSON for SolrUpdater exchange
      log.info("Creating JSON for indexing Google Drive file with ID:" + sourceID);
      super.process(exchange);

    } else {
      log.info("Cannot download google file of type: " + sourceMimeType);
      // service.files().get(sourceID).executeMediaAndDownloadTo(out);
    }

  }

}
