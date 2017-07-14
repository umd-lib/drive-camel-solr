package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.json.JSONObject;
import org.xml.sax.SAXException;

public class DownloadProcessor implements Processor {

   /**
   * Processes message exchange by creating a JSON for SolrUpdater exchange
   */
  public void process(Exchange exchange) throws Exception {

    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    String sourceName = exchange.getIn().getHeader("source_name", String.class);
    String sourcePath = exchange.getIn().getHeader("source_path", String.class);
    String sourceType = exchange.getIn().getHeader("source_type", String.class);



    File destItem = new File(sourcePath);

    JSONObject json = new JSONObject();
    json.put("id", sourceID);
    json.put("name", sourceName);
    json.put("path", sourcePath);


    if (sourceType == "file") {
      Tika tika = new Tika();

      String metadata = exchange.getIn().getHeader("metadata", String.class);

      json.put("type", tika.detect(destItem));
      json.put("content", parseToPlainText(destItem));
      json.put("metadata", metadata);
    }

    exchange.getIn().setBody("[" + json.toString() + "]");

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

}
