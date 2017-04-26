package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Stack;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.*;
import org.apache.tika.parser.txt.*;
import org.apache.tika.parser.microsoft.*;
import org.apache.tika.parser.html.*;
import org.apache.tika.parser.rtf.*;
import org.apache.tika.parser.xml.*;
import org.apache.tika.parser.microsoft.ooxml.*;
import org.apache.tika.parser.jpeg.*;
import org.apache.tika.parser.image.*;


import org.apache.tika.sax.BodyContentHandler;
import org.json.JSONObject;
import org.xml.sax.SAXException;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxFolder.Info;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxSharedLink;

import edu.umd.lib.exception.BoxCustomException;
import edu.umd.lib.services.BoxAuthService;

/****
 * Create a JSON to add the file to Solr. Connect to box and download the whole
 * file that needs to be indexed.
 *
 * @author rameshb
 
 */
@SuppressWarnings("unused")
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
    /****
     * When we download a file from a folder in which the App user has access
     * and create a backup in another folder under the same app user the process
     * will trigger a infinite loop. uploading the file to backup storage in box
     * will act as new upload event and the same process will be triggered thus
     * creating a backup for backup and so on.. In order to avoid this the
     * backup should be created under another app user which does not poll for
     * events
     */
    BoxAPIConnection backUpapi = box.getBoxAPIConnectionasBackUpUser();
    log.info("Connecting to Box to download the file through box API");
    String boxStorageFile = config.get("boxStorage");
    try {

      BoxFile file = new BoxFile(api, file_ID);
      BoxFile.Info info = file.getInfo();
      String version = info.getVersion().getID();// Used for boxStorage
      // backUpStorage created under another app user
      BoxFolder rootFolder = BoxFolder.getRootFolder(backUpapi);
      BoxFolder boxStorage = null;

      /*****
       * Iterate through ChildFolders to find the BoxStorage. Box doesn't allow
       * to access a folder by path or name.we must use ID to use access the
       * folder. From the root folder we iterate through all folder to see if
       * box storage is found. if not found box storage is created
       */
      Iterable<BoxItem.Info> childFolder = rootFolder.getChildren();
      for (BoxItem.Info boxInfo : childFolder) {
        if (boxInfo.getName().equals(boxStorageFile) && boxInfo instanceof BoxFolder.Info) {
          boxStorage = new BoxFolder(backUpapi, boxInfo.getID());
          break;
        }
      }
      if (boxStorage == null) {
        BoxFolder.Info boxInfo = rootFolder.createFolder(boxStorageFile);
        boxStorage = new BoxFolder(backUpapi, boxInfo.getID());
        /****
         * If Any user need permission to see this need to provide access here.
         */
        log.info("Folder Created:" + boxStorage.getID());
      }
      /****
       * From Box we receive the file's info. We do not know the fullPath in
       * Box. The path can be computed by traversing through parent folders till
       * the root folder is reached. Note: This will get the path only till the
       * parent folder till which the Box App user has access.
       */
      List<BoxFolder.Info> Listpaths = info.getPathCollection();
      Queue<BoxFolder.Info> queue = new LinkedList<BoxFolder.Info>(Listpaths);
      String group = "";
      String category = "";

      String Localfilepath = config.get("localStorage");
      String boxStoragePath = boxStorage.getInfo().getName();
      /****
       * We need to create the same folder in localStorage. Loop through the
       * folders from the stack and create the folders and add to the current
       * filePath String. The first folder is the parent folder to which the app
       * user has access.
       */
      queue.poll();// Removing root;
      String parentFolder = createParentFolder(queue.poll(), Localfilepath);
      Localfilepath = Localfilepath + "/" + parentFolder;

      if (!queue.isEmpty()) {
        /****
         * First folderName after the root folder indicates the group to which
         * the file belongs, This information is stored in Solr under group
         * field
         */
        group = createParentFolder(queue.poll(), Localfilepath);
        boxStorage = getBoxFolder(boxStorage, group, backUpapi);
        Localfilepath = Localfilepath + "/" + group;
        boxStoragePath = boxStoragePath + "/" + group;
      }
      if (!queue.isEmpty()) {
        /****
         * second folderName after the root folder indicates the category to
         * which the file belongs, This information is stored in Solr under
         * category field
         */
        category = createParentFolder(queue.poll(), Localfilepath);
        boxStorage = getBoxFolder(boxStorage, category, backUpapi);
        Localfilepath = Localfilepath + "/" + category;
        boxStoragePath = boxStoragePath + "/" + category;
      }

      /****
       * We only need to create the path in local storage and box storage with
       * group and categories
       */
      Localfilepath = Localfilepath + "/" + info.getID() + "_" + info.getName();
      boxStoragePath = boxStoragePath + "/" + info.getID() + "_" + version + "_" + info.getName();

      FileOutputStream stream = new FileOutputStream(Localfilepath);
      file.download(stream);
      stream.close();
      /****
       * Files are down loaded to the localStoragePath. File ID is used to
       * uniquely identify the files from different paths. Since we are trimming
       * the folder path after first and second level we need to make sure files
       * with same name from different path are not overwritten in localStorage.
       */
      File download_file = new File(Localfilepath);

      /****
       * Upload the file to the boxStorage
       */
      FileInputStream inputstream = new FileInputStream(Localfilepath);

      String backedUpFileId = "";
      try {
        BoxFile.Info backedUpFile = boxStorage.uploadFile(inputstream,
            info.getID() + "_" + version + "_" + info.getName());
        inputstream.close();
        backedUpFileId = backedUpFile.getID();
      } catch (BoxAPIException e) {
        if (e.getResponseCode() == 409) {
          log.info("File name already in use, Already backedup");
        } else {
          throw new BoxCustomException(
              "File cannot be backed up in box storage.");
        }

      }

      Tika tika = new Tika();
      JSONObject json = new JSONObject();
      String fileType = tika.detect(download_file);
      json.put("id", file_ID);
      json.put("title", file_name);
      json.put("type", fileType);// Detect file type
      json.put("url", createSharedLink(file));
      json.put("fileContent", parseToPlainText(download_file, fileType));
      json.put("genre", "BoxContent");// Hardcoded
      json.put("group", group);
      json.put("category", category);
      json.put("localStoragePath", Localfilepath);
      json.put("boxStoragePath", boxStoragePath);
      json.put("boxStorageFileId", backedUpFileId);

      // download_file.delete();// Delete the file which was down loaded
      exchange.getIn().setBody("[" + json.toString() + "]");
      //log.info("File" + json.toString());
    } catch (BoxAPIException e) {
      throw new BoxCustomException(
          "File cannot be found or backedup. Please provide access for APP User.");
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
  /** In karaf these files are not auto loaded and should be included in 
   imports even though not used explicitly
   * import org.apache.tika.parser.AutoDetectParser;
   * import org.apache.tika.parser.CompositeParser;
   * import org.apache.tika.parser.ParseContext;
   * import org.apache.tika.parser.pdf.*;
   * import org.apache.tika.parser.txt.*;
   * import org.apache.tika.parser.microsoft.*;
   * import org.apache.tika.parser.html.*;
   * import org.apache.tika.parser.rtf.*;
   * import org.apache.tika.parser.xml.*;
   * import org.apache.tika.parser.microsoft.ooxml.*;
   * import org.apache.tika.parser.jpeg.*;
   * import org.apache.tika.parser.image.*;
   * **/
  public String parseToPlainText(File file, String fileType) throws IOException, SAXException, TikaException {

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
      if( handler.toString().equalsIgnoreCase("")){
        log.info("Error Extracting file content for fileType: "+fileType);
      }
      return handler.toString();
    } catch (Exception e) {
      log.info("Error:"+e.getMessage()+" for file of type :"+fileType);
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
  @SuppressWarnings("unused")
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
  private String createParentFolder(BoxFolder.Info folder, String currentPath) {
    String folderName = folder.getName();
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
   * Encode File to Base64BinaryString for storing file as blob
   *
   * @param fileName
   * @return
   * @throws IOException
   */
  @SuppressWarnings("unused")
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
  @SuppressWarnings("resource")
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

  /*****
   * Create box folder if it does not exists. If exist return the folder
   *
   * @param folder
   * @param folderName
   * @return
   */
  private BoxFolder getBoxFolder(BoxFolder folder, String folderName, BoxAPIConnection api) {
    Iterable<BoxItem.Info> childFolder = folder.getChildren();
    for (BoxItem.Info boxInfo : childFolder) {
      if (boxInfo.getName().equals(folderName) && boxInfo instanceof BoxFolder.Info) {
        folder = new BoxFolder(api, boxInfo.getID());
        return folder;
      }
    }
    BoxFolder.Info boxInfo = folder.createFolder(folderName);
    folder = new BoxFolder(api, boxInfo.getID());
    return folder;
  }

}