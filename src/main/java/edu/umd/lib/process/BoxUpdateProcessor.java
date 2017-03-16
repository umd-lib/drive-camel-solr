package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Stack;

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
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxFolder.Info;
import com.box.sdk.BoxSharedLink;

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

      String parentId = info.getParent().getID();
      /****
       * From Box we receive the file's info. We do not know the fullPath in
       * Box. The path can be computed by traversing through parent folders till
       * the root folder is reached. Note: This will get the path only till the
       * parent folder till which the Box App user has access.
       */
      Stack<BoxFolder> paths = this.getParentFolders(api, parentId);
      String group = "";
      String category = "";

      String Localfilepath = config.get("syncFolder");
      /****
       * We need to create the same folder in localStorage. Loop through the
       * folders from the stack and create the folders and add to the current
       * filePath String. The first folder is the parent folder to which the app
       * user has access.
       */
      String parentFolder = createParentFolder(paths.pop(), Localfilepath);
      Localfilepath = Localfilepath + "/" + parentFolder;

      if (!paths.isEmpty()) {
        /****
         * First folderName after the root folder indicates the group to which
         * the file belongs, This information is stored in Solr under group
         * field
         */
        group = createParentFolder(paths.pop(), Localfilepath);
        Localfilepath = Localfilepath + "/" + group;
      }
      if (!paths.isEmpty()) {
        /****
         * second folderName after the root folder indicates the category to
         * which the file belongs, This information is stored in Solr under
         * category field
         */
        category = createParentFolder(paths.pop(), Localfilepath);
        Localfilepath = Localfilepath + "/" + category;
      }

      /****
       * We only need to create the path in local storage and box storage with
       * group and categories
       */
      Localfilepath = Localfilepath + "/" + info.getID() + "_" + info.getName();

      FileOutputStream stream = new FileOutputStream(Localfilepath);
      file.download(stream);
      stream.close();
      /****
       * Files are downloaded to the localStoragePath. File ID is used to
       * uniquely identify the files from different paths. Since we are trimming
       * the folder path after first and second level we need to make sure files
       * with same name from different path are not overwritten in localStorage.
       */
      File download_file = new File(Localfilepath);

      Tika tika = new Tika();
      JSONObject json = new JSONObject();

      json.put("id", file_ID);
      json.put("title", file_name);
      json.put("type", tika.detect(download_file));// Detect file type
      json.put("url", createSharedLink(file));
      json.put("fileContent", parseToPlainText(download_file));
      json.put("file", encodeFileToBase64Binary(Localfilepath));
      json.put("genre", "BoxContent");// Hardcoded
      json.put("group", group);
      json.put("category", category);
      json.put("localfilePath", Localfilepath);

      // download_file.delete();// Delete the file which was down loaded
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
   * Get List of ParentFolders
   *
   * @param api
   * @param parentId
   * @return
   */
  private Stack<BoxFolder> getParentFolders(BoxAPIConnection api, String parentId) {
    BoxFolder folder = new BoxFolder(api, parentId);
    Stack<BoxFolder> paths = new Stack<BoxFolder>();
    while (folder != null) {
      paths.add(folder);
      Info folderInfo = folder.getInfo();
      if (folderInfo.getParent() != null) {
        folder = new BoxFolder(api, folderInfo.getParent().getID());
      } else {
        folder = null;
      }
    }
    return paths;
  }

  /***
   * CreateParentFolders in Storage Path
   *
   * @param folder
   * @param currentPath
   * @return
   */
  private String createParentFolder(BoxFolder folder, String currentPath) {
    String folderName = folder.getInfo().getName();
    File directory = new File(currentPath + "/" + folderName);
    if (!directory.exists()) {
      directory.mkdir();
    }
    return folderName;
  }

  /****
   * CreateSharedLink for the file
   *
   * @param file
   * @return
   */
  private String createSharedLink(BoxFile file) {
    BoxSharedLink.Permissions permissions = new BoxSharedLink.Permissions();
    permissions.setCanDownload(true);// Can download
    permissions.setCanPreview(true);// Can preview
    BoxSharedLink sharedLink = file.createSharedLink(BoxSharedLink.Access.OPEN, null, permissions);
    return sharedLink.getURL();
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