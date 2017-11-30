package edu.umd.lib.process;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.txt.TXTParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import edu.umd.lib.services.GoogleDriveConnector;

/**
 * @author audani
 */

public class DriveNewFileProcessor extends AbstractSolrProcessor {

  private static Logger log = Logger.getLogger(DriveNewFileProcessor.class);
  private Map<String, String> config;

  public DriveNewFileProcessor(Map<String, String> config) {

    this.config = config;
  }

  @Override
  public String generateMessage(Exchange exchange) throws Exception {
    GoogleDriveConnector connector = new GoogleDriveConnector(this.config);
    Drive service = connector.getDriveService();

    // Get file & get its file type
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    File file = service.files().get(sourceID)
        .setFields("name,mimeType,size")
        .setSupportsTeamDrives(true)
        .execute();
    String sourceMimeType = file.getMimeType();
    String fileName = file.getName();
    String messageBody = null;

    if (sourceMimeType != null) {

      Path outputFile = Paths.get("/tmp/" + fileName);
      Files.deleteIfExists(outputFile);
      Files.createFile(outputFile);

      // Export file to output stream
      OutputStream out = Files.newOutputStream(outputFile);

      service.files().get(sourceID).executeMediaAndDownloadTo(out);

      String allowedFileSize = this.config.get("allowedFileSize");
      if (file.getSize() < Integer.parseInt(allowedFileSize)) {
        String encodedMessage = Base64.encodeBase64String(Files.readAllBytes(outputFile));
        exchange.getIn().setHeader("encodedMsg", encodedMessage);
      }

      // java.io.File destItem = new java.io.File(outputFile.toString());
      Tika tika = new Tika();
      String fileType = tika.detect(outputFile.toFile());
      exchange.getIn().setHeader("file_content", parseToPlainText2(outputFile.toFile(), fileType));
      exchange.getIn().setHeader("file_type", fileType);

      out.flush();
      out.close();
      Files.delete(outputFile);
      messageBody = SolrJsonGenerator.newFileJson(exchange);

    }
    return messageBody;
  }

  /**
   * In karaf these files are not auto loaded and should be included in
   * BodyContentHandler handler = new BodyContentHandler(); imports even though
   * not used explicitly import org.apache.tika.parser.AutoDetectParser; import
   * org.apache.tika.parser.CompositeParser; import
   * org.apache.tika.parser.ParseContext; import org.apache.tika.parser.pdf.*;
   * import org.apache.tika.parser.txt.*; import
   * org.apache.tika.parser.microsoft.*; import org.apache.tika.parser.html.*;
   * import org.apache.tika.parser.rtf.*; import org.apache.tika.parser.xml.*;
   * import org.apache.tika.parser.microsoft.ooxml.*; import
   * org.apache.tika.parser.jpeg.*; import org.apache.tika.parser.image.*;
   **/
  public String parseToPlainText2(java.io.File file, String fileType) throws IOException, SAXException, TikaException {

    AutoDetectParser parser = new AutoDetectParser();
    BodyContentHandler handler = new BodyContentHandler();
    Metadata metadata = new Metadata();
    try {

      ParseContext pcontext = new ParseContext();
      InputStream targetStream = new FileInputStream(file.getAbsolutePath());

      if (fileType.equalsIgnoreCase("text/plain")) {
        TXTParser TexTParser = new TXTParser();
        TexTParser.parse(targetStream, handler, metadata, pcontext);
      } else if (fileType.equalsIgnoreCase("application/pdf")) {
        PDFParser pdfparser = new PDFParser();
        pdfparser.parse(targetStream, handler, metadata, pcontext);
      } else {
        parser.parse(targetStream, handler, metadata, pcontext);
      }
      if (handler.toString().equalsIgnoreCase("")) {
        log.info("Error Extracting file content for fileType: " + fileType);
      }
      return handler.toString();
    } catch (Exception e) {
      log.info("Error:" + e.getMessage() + " for file of type :" + fileType);
      return "Empty String";
    }
  }

}
