package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;
import org.apache.log4j.Logger;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIRequest;
import com.box.sdk.BoxEvent;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxJSONResponse;
import com.box.sdk.EventListener;
import com.box.sdk.EventStream;
import com.eclipsesource.json.JsonObject;

import edu.umd.lib.services.BoxAuthService;

/****
 * Create a JSON to add the file to Solr. Connect to box and download the whole
 * file that needs to be indexed.
 *
 * @author rameshb
 *
 */
public class BoxPollEventProcessor implements Processor {

  private static Logger log = Logger.getLogger(BoxPollEventProcessor.class);
  Exchange exchange;

  Map<String, String> config;
  ProducerTemplate producer;
  private long streamPosition = 0;

  public BoxPollEventProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public void process(Exchange exchange) throws Exception {
    loadStreamPosition();
    BoxAuthService box = new BoxAuthService(config);
    BoxAPIConnection api = box.getBoxAPIConnection();// Get Box Connection
    box.getBoxAPIConnectionasBackUpUser();// Create BackUp User
    producer = exchange.getContext().createProducerTemplate();
    pollBox(api);
  }

  /**
   * Connects to Box and starts long polling Box events. On an event, sends
   * exchange to ActionListener and update's account's poll token.
   */
  public void pollBox(BoxAPIConnection apiArg) throws Exception {

    final BoxAPIConnection api = apiArg;
    // If stream position is not 0, start an event stream to poll updates on API
    // connection
    if (this.getStreamPosition() != 0) {
      EventStream stream = new EventStream(api, getStreamPosition());
      stream.addListener(new EventListener() {
        String body;
        HashMap<String, String> headers;
        @Override
        public void onEvent(BoxEvent event) {

          log.info("Box event received of type: " + event.getType().toString());
          body = event.toString();
          headers = new HashMap<String, String>();

          if (event.getSourceInfo() instanceof BoxFile.Info) {
            BoxItem.Info srcInfo = (BoxItem.Info) event.getSourceInfo();
            if (srcInfo != null) {
              headers.put("item_id", srcInfo.getID());
              headers.put("item_name", srcInfo.getName());
              headers.put("item_path", getFullBoxPath(srcInfo));
              headers.put("event_type", event.getType().toString());
              switch (event.getType()) {
              case ITEM_UPLOAD:
                updateHeaders(api, srcInfo, headers);
                sendActionExchange(headers, body);
                break;
              case ITEM_CREATE:
                updateHeaders(api, srcInfo, headers);
                sendActionExchange(headers, body);
                break;
              case ITEM_UNDELETE_VIA_TRASH:
                updateHeaders(api, srcInfo, headers);
                sendActionExchange(headers, body);
                break;
              case ITEM_COPY:
                updateHeaders(api, srcInfo, headers);
                sendActionExchange(headers, body);
                break;
              case ITEM_RENAME:
              case ITEM_MOVE:
                // It does not look like there is a way to see attributes of a
                // previous version of file.
                // If an item is renamed or moved, we download the most recent
                // version of the file
                // BUT THE OLD VERSION MAY STILL BE IN THE LOCAL STORE since we
                // cannot look it up by path location
                // NOTE: We can 'guess' the location of the old file, if it is
                // still in
                // the same directory using BoxFile.getVersions(), and
                // BoxFileVersion.getName(), but I do not implement this here
                // since it will only work some of the time.
                // I leave these 2 event types to handled separately in case a new
                // handling method is found.
                updateHeaders(api, srcInfo, headers);
                sendActionExchange(headers, body);
                break;
              case ITEM_TRASH:
                headers.put("item_type", "file");
                sendActionExchange(headers, body);
                break;
              default:
                log.info("Unhandled Box event." + event.getType().toString());
                break;
              }
            }
          }

        }

        @Override
        public void onNextPosition(long position) {
          updatePollToken(position);
        }

        @Override
        public boolean onException(Throwable e) {
          e.printStackTrace();
          return false;
        }
      });


      stream.start();
      log.info("Box Streaming Started");
      Thread.sleep(1000 * 60 * 1); // 30 seconds to receive events from box
      stream.stop();
      log.info("Box Streaming Stopped");
      // If poll token is 0, get a current stream position and download all
      // files
      // from that account to the sync folder
    } else {
      updatePollToken(getCurrentStreamPosition(api));
      log.info("First time connecting to Box Account. Downloading all account items to local sync folder...");
      downloadAllFiles(api);
    }
  }

  /**
   * Sends exchanges to ActionListener to download all files associated with an
   * api connection to account's local sync folder starting from root folder
   *
   * @param api
   */
  private void downloadAllFiles(BoxAPIConnection api) {
    BoxFolder rootFolder = BoxFolder.getRootFolder(api);
    String rootPath = Paths.get("").toString();
    exchangeFolderItems(rootFolder, rootPath, api);
  }

  /**
   * Sends a new message exchange with given headers and body to ActionListener
   * route
   *
   * @param headers
   * @param body
   */
  public void sendActionExchange(HashMap<String, String> headers, String body) {

    log.info("Sending action for some items");

    Exchange exchange = new DefaultExchange(producer.getCamelContext());
    Message message = new DefaultMessage();
    message.setBody(body);

    for (Map.Entry<String, String> entry : headers.entrySet()) {
      //log.info("Key:"+entry.getKey()+";Value"+entry.getValue());
      message.setHeader(entry.getKey(), entry.getValue());
    }

    exchange.setIn(message);
    producer.send("direct:route.events", exchange);
  }

  /**
   * Sends an exchanges to ActionListener to download all box files under a
   * given folder at a given depth
   *
   * @param folder
   * @param depth
   */
  private void exchangeFolderItems(BoxFolder folder, String path, BoxAPIConnection api) {

    for (BoxItem.Info itemInfo : folder) {

      HashMap<String, String> headers = new HashMap<String, String>();
      String itemID = itemInfo.getID();
      String itemName = itemInfo.getName();
      String itemPath = Paths.get(path, itemName).toString();
      headers.put("item_id", itemID);
      headers.put("item_name", itemName);
      headers.put("item_path", itemPath);

      if (itemInfo instanceof BoxFile.Info) {
        updateHeaders(api, itemInfo,headers);
        headers.put("event_type", BoxEvent.Type.ITEM_UPLOAD.toString());
        sendActionExchange(headers, "");
      } else if (itemInfo instanceof BoxFolder.Info) {
        BoxFolder dir = new BoxFolder(api, itemID);
        exchangeFolderItems(dir, itemPath, api);
      }
    }
  }

  /****
   * Updating Headers from the file. If file not found in box ignore the event,
   * In some scenarios file can be deleted after this event was triggered and it
   * would through an error since the file does not exist presently in
   * box when the code accesses it
   */
  public void updateHeaders(final BoxAPIConnection api, BoxItem.Info srcInfo, HashMap<String, String> headers) {
    // BoxFile file = (BoxFile) srcInfo.getResource();
    headers.put("item_type", "file");

    BoxFile file;
    try {
      log.info("File_Name:"+srcInfo.getName());
      file = new BoxFile(api, srcInfo.getID());
    } catch (Exception ex) {
      log.info("File Not Available in Box presently");
      headers.put("event_type", "skip");
      return;
    }

    try{
      BoxFolder.Info parent = file.getInfo().getParent();
      headers.put("parent_id", parent.getID());
      headers.put("metadata", file.getMetadata().toString());
    } catch (Exception ex) {
      headers.put("metadata", "none");
      return;
    }

  }

  /**
   * Defines a box stream position (as long) from a poll token string.
   *
   * @param pollToken
   * @return box stream position
   */
  public long defineStreamPosition(String pollToken) {
    long position;
    if (pollToken != null && pollToken != "0") {
      position = Long.parseLong(pollToken);
    } else {
      position = 0;
    }
    return position;
  }

  /**
   * Updates this account's poll token from a box stream position
   *
   * @param position
   */
  private void updatePollToken(long position) {

    // log.info("Poll Position:" + position);
    updateStreamPosition(position);
    // Updating Poll Position to the configuration file
  }

  /**
   * Returns the full box path of a box item as a String.
   *
   * @param srcInfo
   * @return absolute path name of box item in box file system
   */
  private static String getFullBoxPath(BoxItem.Info srcInfo) {

    BoxItem.Info parentInfo = srcInfo;
    String dest = "";

    while (parentInfo != null) {
      dest = Paths.get(parentInfo.getName(), dest).toString();
      parentInfo = parentInfo.getParent();
    }
    return dest;
  }

  /**
   * Gets a Box stream position for given api connection for the current
   * date/time
   *
   * @param api
   * @return
   * @throws MalformedURLException
   */
  private static long getCurrentStreamPosition(BoxAPIConnection api) throws MalformedURLException {

    String urlString = String.format(api.getBaseURL() + "events?stream_position=%s", "now");
    URL url = new URL(urlString);
    BoxAPIRequest request = new BoxAPIRequest(api, url, "GET");
    BoxJSONResponse response = (BoxJSONResponse) request.send();
    JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
    Long position = jsonObject.get("next_stream_position").asLong();

    return position;
  }

  /***
   * Getter Method for StreamPosition
   *
   * @return
   */
  private long getStreamPosition() {
    return streamPosition;
  }

  /****
   * Create the properties file and load the stream position
   * if properties file exists load the steam position for the file.
   */
  public void loadStreamPosition(){
    try {
      String boxPropFile = this.config.get("propertiesName");
      File f = new File(boxPropFile);
      if(f.exists() && !f.isDirectory()) {
        Properties defaultProps = new Properties();
        FileInputStream in = new FileInputStream(boxPropFile);
        defaultProps.load(in);
        streamPosition = Long.parseLong(defaultProps.getProperty("CurrentPollPosition"));
        in.close();
      }else{
        log.error("create Properties file");
        Properties properties = new Properties();
        properties.setProperty("CurrentPollPosition", "0");
        streamPosition = 0;
        File file = new File(boxPropFile);
        FileOutputStream fileOut = new FileOutputStream(file);
        properties.store(fileOut, "Box Poll Position Updated by the program - Do not delete");
        fileOut.close();
      }
    } catch (FileNotFoundException e) {
      log.error("Properties file not found"+e.getMessage());
    } catch (IOException e) {
      log.error("Properties file cannot be opened"+e.getMessage());
    }
  }

  /****
   * Update Stream Position to the properties file
   * @param streamPosition
   */
  public void updateStreamPosition(long streamPosition){
    try {
      String boxPropFile = this.config.get("propertiesName");
      FileOutputStream out = new FileOutputStream(boxPropFile);
      FileInputStream in = new FileInputStream(boxPropFile);
      Properties props = new Properties();
      props.load(in);
      in.close();
      props.setProperty("CurrentPollPosition", streamPosition+"");
      props.store(out, "Box Poll Position Updated by the program - Do not delete");
      out.close();
    } catch (FileNotFoundException e) {
      log.error("Properties file not found"+e.getMessage());
    }catch (IOException e) {
      log.error("Properties file cannot be opened"+e.getMessage());
    }
  }


}