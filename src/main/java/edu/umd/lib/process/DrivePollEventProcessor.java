package edu.umd.lib.process;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;
import org.apache.log4j.Logger;
import org.json.JSONException;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.StartPageToken;
import com.google.api.services.drive.model.TeamDrive;
import com.google.api.services.drive.model.TeamDriveList;

import edu.umd.lib.services.GoogleDriveConnector;

/****
 * Create a JSON to add the file to Solr. Connect to Drive and download the
 * whole file that needs to be indexed.
 *
 * @author audani
 *
 */
public class DrivePollEventProcessor implements Processor {

  private static Logger log = Logger.getLogger(DrivePollEventProcessor.class);
  Map<String, String> config;
  ProducerTemplate producer;
  final static String categories[] = { "policies", "reports", "guidelines", "links", "workplans", "minutes" };

  public DrivePollEventProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    producer = exchange.getContext().createProducerTemplate();
    GoogleDriveConnector gd = new GoogleDriveConnector(this.config);
    Drive service = gd.getDriveService();
    poll(service);
  }

  /**
   * Connects to Box and starts long polling Box events. On an event, sends
   * exchange to ActionListener and update's account's poll token.
   */
  public void poll(Drive service) throws Exception {

    // String propertiesFilePath = this.config.get("localStorage") +
    // this.config.get("propertiesName");
    java.io.File propFile = new java.io.File(this.config.get("propertiesFile"));
    if (!propFile.exists()) {

      downloadAllFiles(service);

    } else

    {
      String drivePageToken = null;

      do {
        TeamDriveList result = service.teamdrives().list()
            .setPageToken(drivePageToken)
            .setPageSize(100)
            .execute();

        List<TeamDrive> teamDrives = result.getTeamDrives();

        for (TeamDrive td : teamDrives) {

          String pageToken = loadDriveChangesToken(td.getId());

          while (pageToken != null) {
            ChangeList changes = service.changes().list(pageToken)
                .setFields("changes,nextPageToken,newStartPageToken")
                .setIncludeTeamDriveItems(true)
                .setSupportsTeamDrives(true)
                .setTeamDriveId(td.getId())
                .setPageSize(100)
                .execute();

            for (Change change : changes.getChanges()) {

              File changeItem = change.getFile();
              log.info("Change detected for item: " + changeItem.getId() + "\t" + changeItem.getName());

              String sourcePath = getSourcePath(service, changeItem);
              log.info("Source Path of changed file:" + sourcePath);

              if ("published".equals(sourcePath.split("//")[2])) {
                if ((change.getRemoved() || changeItem.getTrashed())
                    && !"application/vnd.google-apps.folder".equals(changeItem.getMimeType())) {

                  log.info("Delete request");
                  sendDeleteRequest(service, change, sourcePath);

                } else if (changeItem.getMimeType().equals("application/vnd.google-apps.folder")) {
                  String directoryPath = this.config.get("localStorage") + sourcePath;
                  java.io.File dir = new java.io.File(directoryPath);
                  if (!dir.exists()) {
                    log.info("Makedir request");
                    sendMakedirRequest(service, directoryPath);
                  }
                } else {

                  String filePath = this.config.get("localStorage") + sourcePath;
                  java.io.File file = new java.io.File(filePath);
                  if (!file.exists()) {
                    log.info("Download request");
                    sendDownloadRequest(service, changeItem, sourcePath);
                  }
                }
              }

            }

            // save latest page token
            if (changes.getNewStartPageToken() != null) {
              pageToken = changes.getNewStartPageToken();
              log.info("Page token for team drive:" + td.getName() + ":" + pageToken);
              updateDriveChangesToken(td.getId(), pageToken);
            }

            pageToken = changes.getNextPageToken();

          }

        }

        drivePageToken = result.getNextPageToken();
      } while (drivePageToken != null);

    }

  }

  /**
   * Sends a new message exchange to ActionListener requesting to make a
   * directory on the local system.
   *
   * @param service
   * @param file
   * @throws IOException
   */
  private void sendMakedirRequest(Drive service, String directoryPath) throws IOException {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "make_directory");
    headers.put("local_path", directoryPath);

    sendActionExchange(headers, "");

  }

  /**
   * Sends a new message exchange to ActionListener requesting to delete a file
   * or folder from the local system, along with its children.
   *
   * @param service
   * @param file
   * @throws IOException
   */
  private void sendDeleteRequest(Drive service, Change change, String sourcePath) throws IOException {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "delete");
    headers.put("source_id", change.getFileId());
    File deletedFile = change.getFile();

    // file source path of the deleted file
    headers.put("url", getSourcePath(service, deletedFile));
    headers.put("source_name", deletedFile.getName());
    headers.put("local_path", this.config.get("localStorage") + sourcePath);
    headers.put("source_type", "file");
    sendActionExchange(headers, "");

  }

  /**
   * Sends requests to make all directories and download all files associated
   * with a Google Drive account.
   *
   * @param service
   * @throws IOException
   * @throws JSONException
   */
  private void downloadAllFiles(Drive service) throws IOException, JSONException {

    log.info("First time connecting to Google Drive Account");
    log.info("Sending requests to download all published files of this account...");

    String pageToken = null;
    java.io.File propertiesFile = new java.io.File(this.config.get("propertiesFile"));
    propertiesFile.createNewFile();

    do {

      TeamDriveList result = service.teamdrives().list()
          .setPageToken(pageToken)
          .execute();

      List<TeamDrive> teamDrives = result.getTeamDrives();
      log.info("Total no. of Team Drives:" + teamDrives.size());

      for (TeamDrive teamDrive : teamDrives) {
        log.info("Team Drive ID:" + teamDrive.getId() + "\t Team Drive Name:" + teamDrive.getName());

        // StringBuilder path = new StringBuilder(config.get("localStorage"));
        File publishedFolder = accessPublishedFolder(service, teamDrive);

        if (publishedFolder != null) {
          // path.append("//" + teamDrive.getName() + "//" + "published");
          accessPublishedFiles(service, publishedFolder, teamDrive);
        }

        StartPageToken response = service.changes().getStartPageToken()
            .setSupportsTeamDrives(true)
            .setTeamDriveId(teamDrive.getId())
            .execute();

        updateDriveChangesToken(teamDrive.getId(), response.getStartPageToken());
      }

      pageToken = result.getNextPageToken();
    } while (pageToken != null);
  }

  public File accessPublishedFolder(Drive service, TeamDrive td) {
    try {
      FileList list = service.files().list()
          .setTeamDriveId(td.getId())
          .setSupportsTeamDrives(true)
          .setCorpora("teamDrive")
          .setFields("files(id,name,parents)")
          .setIncludeTeamDriveItems(true)
          .setQ("mimeType='application/vnd.google-apps.folder' and name='published' and trashed=false")
          .execute();

      List<File> fileList = list.getFiles();

      if (fileList.size() > 0) {
        log.info("Published folder id:" + fileList.get(0).getId());
        return fileList.get(0);
      }

    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }

  public void accessPublishedFiles(Drive service, File file, TeamDrive td) throws JSONException {

    try {

      String query = "'" + file.getId() + "' in parents and trashed=false";
      String pageToken = null;
      do {
        FileList list = service.files().list()
            .setQ(query)
            .setFields("nextPageToken,files(id,name,mimeType,parents)")
            .setCorpora("teamDrive")
            .setIncludeTeamDriveItems(true)
            .setSupportsTeamDrives(true)
            .setTeamDriveId(td.getId())
            .setPageToken(pageToken)
            .execute();

        List<File> fileList = list.getFiles();

        System.out.println("File Count:" + fileList.size());

        for (File pubFile : fileList) {

          log.info("File Name:" + pubFile.getName());
          log.info("Mime type:" + pubFile.getMimeType());
          String path = getSourcePath(service, pubFile);
          if ("application/vnd.google-apps.folder".equals(pubFile.getMimeType())) {

            sendMakedirRequest(service, this.config.get("localStorage") + path);
            accessPublishedFiles(service, pubFile, td);
          } else {
            sendDownloadRequest(service, pubFile, path);
          }

        }
        pageToken = list.getNextPageToken();
      } while (pageToken != null);

    } catch (IOException e) {

      e.printStackTrace();
    }
  }

  /**
   * Send a new message exchange to ActionListener requesting to download a file
   * to the local system.
   *
   * @param service
   * @param file
   * @throws IOException
   * @throws JSONException
   */
  private void sendDownloadRequest(Drive service, File file, String path) throws IOException, JSONException {

    HashMap<String, String> headers = new HashMap<String, String>();

    String localPath = this.config.get("localStorage") + path;

    headers.put("action", "download");
    headers.put("source_type", "file");
    headers.put("source_id", file.getId());
    headers.put("source_name", file.getName());
    headers.put("local_path", localPath);
    headers.put("url", path);

    String paths[] = path.split("//");
    String group = paths[1];

    headers.put("group", group);

    if (paths.length > 3) {
      for (String category : categories) {
        if (paths[3].equals(category)) {
          headers.put("category", category);
        }
      }
    }

    sendActionExchange(headers, "");

  }

  /**
   * Sends a new message exchange with given headers and body to ActionListener
   * route
   *
   * @param headers
   * @param body
   */
  public void sendActionExchange(HashMap<String, String> headers, String body) {
    Exchange exchange = new DefaultExchange(this.producer.getCamelContext());
    Message message = new DefaultMessage();
    message.setBody(body);

    for (Map.Entry<String, String> entry : headers.entrySet()) {
      message.setHeader(entry.getKey(), entry.getValue());
    }

    exchange.setIn(message);
    this.producer.send("direct:actions", exchange);
  }

  /****
   * Create the properties file and load the poll token if properties file
   * exists load the poll token from the file.
   */
  public String loadDriveChangesToken(String teamDriveId) {
    String token = null;
    try {
      String drivePropFile = this.config.get("propertiesFile");
      java.io.File f = new java.io.File(drivePropFile);
      if (f.exists() && !f.isDirectory()) {
        Properties defaultProps = new Properties();
        FileInputStream in = new FileInputStream(drivePropFile);
        defaultProps.load(in);
        token = defaultProps.getProperty("drivetoken_" + teamDriveId);
        in.close();
      }
    } catch (FileNotFoundException e) {
      log.error("Properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("Properties file cannot be opened" + e.getMessage());
    }
    return token;
  }

  public void updateDriveChangesToken(String teamDriveId, String driveToken) {
    try {
      String propFilePath = this.config.get("propertiesFile");

      FileInputStream in = new FileInputStream(propFilePath);
      Properties props = new Properties();
      props.load(in);
      in.close();
      FileOutputStream out = new FileOutputStream(propFilePath);
      props.setProperty("drivetoken_" + teamDriveId, driveToken);
      props.store(out, "Drive Page token updated by the program - Do not delete");
      out.close();
    } catch (FileNotFoundException e) {
      log.error("Properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("Properties file cannot be opened" + e.getMessage());
    }
  }

  /****
   * Update Stream Position to the properties file
   *
   * @param streamPosition
   */
  public void updatePageToken(String pageToken) {
    try {
      String drivePropFile = this.config.get("propertiesName");
      FileOutputStream out = new FileOutputStream(drivePropFile);
      FileInputStream in = new FileInputStream(drivePropFile);
      Properties props = new Properties();
      props.load(in);
      in.close();
      props.setProperty("pagetoken", pageToken);
      props.store(out, "Drive Page token updated by the program - Do not delete");
      out.close();
    } catch (FileNotFoundException e) {
      log.error("Properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("Properties file cannot be opened" + e.getMessage());
    }
  }

  /**
   * Gets the absolute path of a file or folder as it stands in Drive storage.
   *
   * @param service
   * @param item
   * @return
   * @throws IOException
   */
  private String getSourcePath(Drive service, File item) throws IOException {

    String itemName = item.getName();
    String parentID = item.getParents().get(0);
    Stack<String> path = new Stack<String>();
    StringBuilder fullPathBuilder = new StringBuilder();
    path.push(itemName);

    while (true) {

      File parent = service.files().get(parentID)
          .setSupportsTeamDrives(true)
          .setFields("id,name,parents")
          .execute();

      if (parent.getParents() == null) {
        String teamDriveName = service.teamdrives().get(parent.getId()).execute().getName();
        path.push(teamDriveName);
        break;
      } else {
        path.push(parent.getName());
        parentID = parent.getParents().get(0);
      }
    }

    while (!path.isEmpty()) {
      fullPathBuilder.append("//").append(path.pop());
    }

    return fullPathBuilder.toString();
  }

}