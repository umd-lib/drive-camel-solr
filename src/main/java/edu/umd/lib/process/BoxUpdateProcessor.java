package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.json.JSONObject;
import org.xml.sax.SAXException;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxFile;

import edu.umd.lib.exception.BoxCustomException;
import edu.umd.lib.services.BoxAuthService;

/****
 * Create a JSON to add the file to Solr. Connect to box and download the whole
 * file that needs to be indexed.
 *
 * @author rameshb
 *
 */
public class BoxUpdateProcessor implements Processor {

  private static Logger log = Logger.getLogger(BoxUpdateProcessor.class);

  Map<String, String> config;

  public BoxUpdateProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    String file_name = exchange.getIn().getHeader("item_name", String.class);
    String file_ID = exchange.getIn().getHeader("item_id", String.class);

    log.info("Creating JSON for indexing box file with ID:" + file_ID);

    BoxAuthService box = new BoxAuthService(config);
    BoxAPIConnection api = box.getBoxAPIConnection();// Get Box Connection
    log.info("Connecting to Box to download the file through box API");

    try {
      BoxFile file = new BoxFile(api, file_ID);
      BoxFile.Info info = file.getInfo();
      URL previewurl = file.getPreviewLink();
      FileOutputStream stream = new FileOutputStream("data/files/" + info.getName());
      file.download(stream);
      stream.close();
      File download_file = new File("data/files/" + info.getName());

      Tika tika = new Tika();
      JSONObject json = new JSONObject();

      json.put("id", file_ID);
      json.put("name", file_name);
      json.put("type", tika.detect(download_file));// Detect file type
      json.put("url", previewurl);
      json.put("fileContent", parseToPlainText(download_file));
      json.put("file", encodeFileToBase64Binary("data/files/" + info.getName()));
      json.put("genre", "BoxContent");// Hardcoded

      download_file.delete();// Delete the file which was down loaded
      exchange.getIn().setBody("[" + json.toString() + "]");
      // log.info("File" + json.toString());
    } catch (BoxAPIException e) {
      throw new BoxCustomException(
          "File cannot be found. Please provide access for APP User.");
    }

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

  /****
   * Encode File to Base64BinaryString
   *
   * @param fileName
   * @return
   * @throws IOException
   */
  private static String encodeFileToBase64Binary(String fileName)
      throws IOException {

    File file = new File(fileName);
    byte[] bytes = loadFile(file);
    byte[] encoded = Base64.encodeBase64(bytes);
    String encodedString = new String(encoded, StandardCharsets.US_ASCII);
    return encodedString;
  }

  /*****
   * Loadfile to convert into String
   *
   * @param file
   * @return
   * @throws IOException
   */
  private static byte[] loadFile(File file) throws IOException {
    InputStream is = new FileInputStream(file);

    long length = file.length();
    if (length > Integer.MAX_VALUE) {
      // File is too large
    }
    byte[] bytes = new byte[(int) length];

    int offset = 0;
    int numRead = 0;
    while (offset < bytes.length
        && (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0) {
      offset += numRead;
    }

    if (offset < bytes.length) {
      throw new IOException("Could not completely read file " + file.getName());
    }

    is.close();
    return bytes;
  }

}