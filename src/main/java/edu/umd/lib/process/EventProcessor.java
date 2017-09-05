package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.txt.TXTParser;
import org.apache.tika.sax.BodyContentHandler;
import org.json.JSONObject;
import org.xml.sax.SAXException;

/**
 * import org.apache.tika.parser.CompositeParser; import
 * org.apache.tika.parser.ParseContext; import org.apache.tika.parser.pdf.*;
 * import org.apache.tika.parser.txt.*; import
 * org.apache.tika.parser.microsoft.*; import org.apache.tika.parser.html.*;
 * import org.apache.tika.parser.rtf.*; import org.apache.tika.parser.xml.*;
 * import org.apache.tika.parser.microsoft.ooxml.*; import
 * org.apache.tika.parser.jpeg.*; import org.apache.tika.parser.image.*;
 */

@SuppressWarnings("unused")
public class EventProcessor implements Processor {

  private static Logger log = Logger.getLogger(EventProcessor.class);

  /**
   * Processes message exchange by creating a JSON for SolrUpdater exchange
   */
  @Override
  public void process(Exchange exchange) throws Exception {

    log.info(exchange.getIn());

    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    String sourceName = exchange.getIn().getHeader("source_name", String.class);
    String storagePath = exchange.getIn().getHeader("local_path", String.class);
    String sourceType = exchange.getIn().getHeader("source_type", String.class);
    String fileStatus = exchange.getIn().getHeader("fileStatus", String.class);
    String action = exchange.getIn().getHeader("action", String.class);
    String group = exchange.getIn().getHeader("group", String.class);
    String category = exchange.getIn().getHeader("category", String.class);

    // e.g., https://drive.google.com/open?id=0B6YxyGZNOvsqdjM2MFJ5Y2JVanc
    String url = "https://drive.google.com/open?id=" + sourceID;

    JSONObject json = new JSONObject();
    json.put("id", sourceID);

    String body = null;

    if (sourceType == "file" && "download".equals(action)) {
      json.put("title", sourceName);
      json.put("storagePath", storagePath);
      json.put("genre", "Google Drive");
      json.put("url", url);
      json.put("group", group);
      Tika tika = new Tika();
      File destItem = new File(storagePath);
      String fileType = tika.detect(destItem);
      json.put("type", fileType);
      json.put("fileContent", parseToPlainText2(destItem, fileType));
      json.put("category", category);
      body = "[" + json.toString() + "]";
    }
    if ("delete".equals(action)) {
      body = "{'delete':" + json.toString() + "}";
    }

    exchange.getIn().setBody(body);

  }

  /***
   * Convert the File into Plain Text file
   *
   * @param file
   * @return
   * @throws IOException
   * @throws SAXException
   * @throws TikaException
   */
  public String parseToPlainText(File file) throws IOException, SAXException, TikaException {
    BodyContentHandler handler = new BodyContentHandler();

    AutoDetectParser parser = new AutoDetectParser();
    Metadata metadata = new Metadata();
    try {
      InputStream targetStream = new FileInputStream(file.getAbsolutePath());
      parser.parse(targetStream, handler, metadata);
      return handler.toString();
    } catch (Exception e) {
      e.printStackTrace();
      return "Empty String";
    }
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
  public String parseToPlainText2(File file, String fileType) throws IOException, SAXException, TikaException {

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
