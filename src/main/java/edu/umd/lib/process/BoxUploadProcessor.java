package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.json.JSONObject;
import org.xml.sax.SAXException;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxFile;

import edu.umd.lib.services.BoxAuthService;

/****
 * Process the file to add to Solr
 *
 * @author rameshb
 *
 */
public class BoxUploadProcessor implements Processor {

  public void process(Exchange exchange) throws Exception {

    String file_name = exchange.getIn().getHeader("item_name", String.class);
    String file_ID = exchange.getIn().getHeader("item_id", String.class);

    BoxAuthService box = new BoxAuthService();
    BoxAPIConnection api = box.getBoxAPIConnection();

    BoxFile file = new BoxFile(api, file_ID);
    BoxFile.Info info = file.getInfo();
    URL previewurl = file.getPreviewLink();

    FileOutputStream stream = new FileOutputStream("data/files/" + info.getName());
    file.download(stream);
    stream.close();
    File donwload_file = new File("data/files/" + info.getName());

    Tika tika = new Tika();
    JSONObject json = new JSONObject();

    json.put("id", file_ID);
    json.put("name", file_name);
    json.put("type", tika.detect(donwload_file));
    json.put("url", previewurl);
    json.put("fileContent", parseToPlainText(donwload_file));
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