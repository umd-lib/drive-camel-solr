package edu.umd.lib.process;


import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

public class DriveDeleteProcessor extends DownloadProcessor {


  private static Logger log = Logger.getLogger(DriveDeleteProcessor.class);


  /**
   * Deletes a file or a folder and its children, specified by header
   * "source_path" and constructs a JSON string for Solr deletion.
   */
  @Override
  public void process(Exchange exchange) throws Exception {
    
	 /*String sourcePath = exchange.getIn().getHeader("source_path", String.class);
	  
	  java.io.File fileToDelete = new java.io.File(sourcePath);

	  String type = exchange.getIn().getHeader("source_type", String.class);
	  if ("file".equals(type)) {
		  boolean fileDeleted = fileToDelete.delete();
		  
		  if (fileDeleted) {
		    log.info("File deleted: " + fileToDelete);
		  } else {
		    log.info("WARNING: Could not delete file: " + fileToDelete);
		  }
	  } else {
      try {
        // Deleting the directory recursively.
        delete(sourcePath);
        log.info("Directory & its children deleted: " + sourcePath);
      } catch (IOException e) {
        log.info("WARNING: Could not delete directory: " + sourcePath);
      }

    }*/

    // create JSON for Solr Exchange
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    log.info("Creating JSON for deleting file with ID:" + sourceID);
    
    super.process(exchange);

  }

  /**
   * Delete a file or a directory and its children.
   *
   * @param file
   *          The directory to delete.
   * @throws IOException
   *           Exception when problem occurs during deleting the directory.
   */
  private static void delete(String directoryName) throws IOException {

    Path directory = Paths.get(directoryName);
    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult visitFile(Path file,
          BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc)
          throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

}
