package edu.umd.lib.process;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
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
  Drive service;
  ProducerTemplate producer;
  final static String categories[] = { "policies", "reports", "guidelines", "links", "workplans", "minutes" };

  public DrivePollEventProcessor() {

  }

  public DrivePollEventProcessor(Map<String, String> config) {
    try {
      this.config = config;
      service = new GoogleDriveConnector(config).getDriveService();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    producer = exchange.getContext().createProducerTemplate();
    poll();
  }

  /**
   * Connects to Drive and starts long polling Drive events. On an event, sends
   * exchange to ActionListener and updates the poll token.
   *
   * @param service
   */
  public void poll() {

    Path tokenProperties = Paths.get(this.config.get("tokenProperties"));
    try {
      if (Files.notExists(tokenProperties) || Files.size(tokenProperties) == 0) {

        downloadAllFiles();

      } else

      {
        String drivePageToken = null;

        do {
          TeamDriveList result = service.teamdrives().list()
              .setPageToken(drivePageToken)
              .setPageSize(100)
              .execute();

          List<TeamDrive> teamDrives = result.getTeamDrives();
          log.debug("Number of Team Drives:" + teamDrives.size());

          // Checking for the addition of a new Team Drive. If a new team drive
          // has been added with a published folder, we download the files
          // inside
          // the published folder
          checkForNewTeamDrives(teamDrives, tokenProperties);

          for (TeamDrive td : teamDrives) {

            String pageToken = loadDriveChangesToken(td.getId());

            while (pageToken != null) {

              log.info("Checking changes for Drive " + td.getName());
              ChangeList changes = service.changes().list(pageToken)
                  .setFields("changes,nextPageToken,newStartPageToken")
                  .setIncludeTeamDriveItems(true)
                  .setSupportsTeamDrives(true)
                  .setTeamDriveId(td.getId())
                  .setPageSize(100)
                  .execute();

              for (Change change : changes.getChanges()) {

                File changeItem = change.getFile();

                log.info("Change detected for item: " + changeItem.getId() + ":" + changeItem.getName());

                String sourcePath = getSourcePath(changeItem);
                log.info("Source Path of accessed file:" + sourcePath);

                // We are interested only in the changes that occur inside the
                // published folder
                if ("published".equals(sourcePath.split("/")[2])) {

                  // Delete event
                  if (change.getRemoved() || changeItem.getTrashed()) {

                    if (!"application/vnd.google-apps.folder".equals(changeItem.getMimeType())) {

                      log.info("File Delete request");
                      sendDeleteRequest(change, sourcePath);
                    }
                  } else if (changeItem.getMimeType().equals("application/vnd.google-apps.folder")) {
                    if (!"published".equals(changeItem.getName())) {

                      String directoryPath = this.config.get("localStorage") + sourcePath;
                      String storedFilePath = checkFileAttribute(changeItem.getId());

                      if (storedFilePath == null) {
                        log.info("Makedir request");
                        sendMakedirRequest(directoryPath, changeItem.getId());
                      } else if (storedFilePath != null
                          && !storedFilePath.equals(directoryPath)) {
                        log.info("Directory Rename request");
                        sendDirRenameRequest(storedFilePath, directoryPath, changeItem,
                            sourcePath);
                        log.info("Update File Paths request");
                        updateFilePathChanges(changeItem);

                      }
                    }
                  } else {

                    // Either its a new file download, file rename or a file
                    // update request

                    String storedFilePath = checkFileAttribute(changeItem.getId());

                    if (storedFilePath == null) {
                      // New file download request
                      log.info("New File download request");
                      sendDownloadRequest(changeItem, sourcePath);
                    } else {

                      Path file = Paths.get(storedFilePath);
                      List<String> googleDoc = checkForGoogleDoc(changeItem.getMimeType());
                      String storedFileName = storedFilePath.substring(storedFilePath.lastIndexOf("/") + 1,
                          storedFilePath.length());

                      if (googleDoc.size() == 0) {
                        // Not a Google doc

                        // Checking for content update
                        if (!changeItem.getMd5Checksum().equals(getMd5ForFile(file))) {
                          log.info("Non Google Doc file update request");
                          sendUpdateContentRequest(changeItem, storedFilePath);
                        }

                        // Checking for file rename
                        if (!storedFileName.equals(changeItem.getName())) {
                          log.info("Non Google Doc File Rename request");
                          sendFileRenameRequest(storedFilePath, changeItem);
                        }

                      } else {
                        // Google doc
                        String ext = googleDoc.get(0);
                        String mimeType = googleDoc.get(1);

                        String tempFilePath = changeItem.getName() + ext;
                        Path outputFile = Paths.get(tempFilePath);
                        Files.createFile(outputFile);
                        OutputStream out = Files.newOutputStream(outputFile);

                        service.files().export(changeItem.getId(), mimeType).executeMediaAndDownloadTo(out);

                        log.info("Checksum of Output file:" + getMd5ForFile(outputFile));
                        log.info("Checksum of stored file:" + getMd5ForFile(file));

                        // Checking for content update
                        if (!getMd5ForFile(outputFile).equals(getMd5ForFile(file))) {
                          log.info("Google Doc file update request");
                          sendUpdateContentRequest(changeItem, storedFilePath);
                        }
                        out.flush();
                        out.close();
                        Files.delete(outputFile);

                        // Checking for file rename
                        if (!storedFileName.equals(changeItem.getName() + ext)) {
                          log.info("Google Doc File Rename request");
                          sendFileRenameRequest(storedFilePath, changeItem);
                        }
                      }

                    }
                  } // End of file download,rename or update

                }
              }

              // save latest page token
              if (changes.getNewStartPageToken() != null) {
                pageToken = changes.getNewStartPageToken();
                log.debug("Page token for team drive:" + td.getName() + ":" + pageToken);
                updateDriveChangesToken(td.getId(), pageToken);
              }

              pageToken = changes.getNextPageToken();

            }
          }
          drivePageToken = result.getNextPageToken();
        } while (drivePageToken != null);

      }
    } catch (

    IOException e) {
      e.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  public List<String> checkForGoogleDoc(String mimeType) {
    List<String> googleDocMetaData = new ArrayList<String>();

    if (mimeType.equals("application/vnd.google-apps.document")) {
      // Download google documents as MS Word files
      googleDocMetaData.add(".docx");
      googleDocMetaData.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    } else if (mimeType.equals("application/vnd.google-apps.spreadsheet")) {
      // Download google spreadsheets as MS Excel files
      googleDocMetaData.add(".xlsx");
      googleDocMetaData.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    } else if (mimeType.equals("application/vnd.google-apps.drawing")) {
      // Download google drawings as jpegs
      googleDocMetaData.add(".jpg");
      googleDocMetaData.add("image/jpeg");
    } else if (mimeType.equals("application/vnd.google-apps.presentation")) {
      // Download google slides as MS powerpoint
      googleDocMetaData.add(".pptx");
      googleDocMetaData.add("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    } else if (mimeType.equals("application/vnd.google-apps.script")) {
      // Download google apps scripts as JSON
      googleDocMetaData.add(".json");
      googleDocMetaData.add("application/vnd.google-apps.script+json");
    }

    return googleDocMetaData;
  }

  /**
   * This method is used to check for new Team Drives that have been added after
   * the first run of this tool. It checks for new team drives, and creates them
   * on the local server along with all the published files and folders. It also
   * adds the token for the new drive in the properties file.
   *
   * @param service
   * @param teamDriveList
   * @param tokenProperties
   * @throws JSONException
   */

  public void checkForNewTeamDrives(List<TeamDrive> teamDriveList, Path tokenProperties) {
    try {
      if (Files.exists(tokenProperties) && !Files.isDirectory(tokenProperties)) {
        Properties props = new Properties();
        FileInputStream in = new FileInputStream(tokenProperties.toFile());
        props.load(in);
        in.close();

        if (teamDriveList.size() > props.size()) {
          for (TeamDrive td : teamDriveList) {
            if (!props.containsKey("drivetoken_" + td.getId())) {
              log.info("Team Drive ID:" + td.getId() + "\t Team Drive Name:" + td.getName());

              File publishedFolder = accessPublishedFolder(td);

              if (publishedFolder != null) {
                accessPublishedFiles(publishedFolder, td);

                StartPageToken response = service.changes().getStartPageToken()
                    .setSupportsTeamDrives(true)
                    .setTeamDriveId(td.getId())
                    .execute();

                updateDriveChangesToken(td.getId(), response.getStartPageToken());
              }

            }
          }
        }

        in.close();
      }
    } catch (FileNotFoundException e) {
      log.error("Properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("Properties file cannot be opened" + e.getMessage());
    }

  }

  /**
   * Returns the MD5 checksum value for a file
   *
   * @param file
   * @return MD5 checksum string
   */

  public String getMd5ForFile(Path file) {
    String md5Value = null;
    FileInputStream is = null;
    try {
      is = new FileInputStream(file.toFile());
      md5Value = DigestUtils.md5Hex(IOUtils.toByteArray(is));
    } catch (IOException e) {
      log.info("Hey there is an error: " + e);
    } finally {
      IOUtils.closeQuietly(is);
    }
    return md5Value;
  }

  /**
   * Sends a new message exchange to ActionListener requesting to make a
   * directory on the local system.
   *
   * @param directoryPath
   * @throws IOException
   */
  private void sendMakedirRequest(String directoryPath, String fileId) {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "make_directory");
    headers.put("source_id", fileId);
    headers.put("local_path", directoryPath);

    sendActionExchange(headers, "");

  }

  /**
   * Sends a new message exchange to ActionListener requesting to rename a file
   * on the local system.
   *
   * @param service
   * @param oldFile
   * @param newFile
   * @param changedFile
   * @param path
   * @throws IOException
   */
  public void sendFileRenameRequest(String oldFilePath, File changedFile) {

    String sourceMimeType = changedFile.getMimeType();
    String fileName = changedFile.getName();

    if (sourceMimeType.equals("application/vnd.google-apps.document")) {
      fileName += ".docx";
    } else if (sourceMimeType.equals("application/vnd.google-apps.spreadsheet")) {
      fileName += ".xlsx";
    } else if (sourceMimeType.equals("application/vnd.google-apps.drawing")) {
      fileName += ".jpg";
    } else if (sourceMimeType.equals("application/vnd.google-apps.presentation")) {
      fileName += ".pptx";
    } else if (sourceMimeType.equals("application/vnd.google-apps.script")) {
      fileName += ".json";
    }

    String updatedFilePath = oldFilePath
        .replace(oldFilePath.substring(oldFilePath.lastIndexOf("/") + 1, oldFilePath.length()), fileName);

    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("action", "rename_file");
    headers.put("source_id", changedFile.getId());
    headers.put("source_name", fileName);
    headers.put("local_path", updatedFilePath);
    headers.put("source_type", "file");
    headers.put("old_path", oldFilePath);
    headers.put("modified_time", changedFile.getModifiedTime().toString());
    sendActionExchange(headers, "");
  }

  /**
   * Sends a new message exchange to ActionListener requesting to rename a
   * directory on the local system.
   *
   * @param oldFilePath
   * @param newFilePath
   * @param changedFile
   * @param path
   */

  public void sendDirRenameRequest(String oldFilePath, String newFilePath, File changedFile, String path) {

    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("action", "rename_dir");
    headers.put("source_id", changedFile.getId());
    headers.put("local_path", newFilePath);
    headers.put("old_path", oldFilePath);
    sendActionExchange(headers, "");
  }

  /**
   * Sends a new message exchange to ActionListener requesting to delete a file
   * or folder
   *
   * @param service
   * @param change
   * @param sourcePath
   * @throws IOException
   */
  private void sendDeleteRequest(Change change, String sourcePath) {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "delete");
    headers.put("source_id", change.getFileId());
    headers.put("local_path", this.config.get("localStorage") + sourcePath);
    headers.put("source_type", "file");
    sendActionExchange(headers, "");

  }

  /**
   * Sends requests to make all directories and download all files associated
   * with the Team Drive.
   *
   * @param service
   * @throws IOException
   * @throws JSONException
   */
  private void downloadAllFiles() {

    log.info("First time connecting to Google Drive Account");
    log.info("Sending requests to download all published files of this account...");

    try {
      String pageToken = null;
      Path tokenProperties = Paths.get(this.config.get("tokenProperties"));
      Path fileAttributeProperties = Paths.get(this.config.get("fileAttributeProperties"));
      Path driveAcronymProperties = Paths.get(this.config.get("driveAcronymProperties"));

      if (Files.notExists(tokenProperties)) {
        Files.createFile(tokenProperties);
      }

      if (Files.notExists(fileAttributeProperties)) {
        Files.createFile(fileAttributeProperties);
      }

      if (Files.notExists(driveAcronymProperties)) {
        Files.createFile(driveAcronymProperties);
        log.info("Application paused for 60 seconds. Please populate the newly created drive acronym properties file.");
        Thread.sleep(60000);
      }

      do {

        TeamDriveList result = service.teamdrives().list()
            .setPageToken(pageToken)
            .execute();

        List<TeamDrive> teamDrives = result.getTeamDrives();
        log.info("Number of Team Drives:" + teamDrives.size());

        for (TeamDrive teamDrive : teamDrives) {
          log.info("Team Drive ID:" + teamDrive.getId() + "\t Team Drive Name:" + teamDrive.getName());

          File publishedFolder = accessPublishedFolder(teamDrive);

          if (publishedFolder != null) {

            accessPublishedFiles(publishedFolder, teamDrive);
          }

          StartPageToken response = service.changes().getStartPageToken()
              .setSupportsTeamDrives(true)
              .setTeamDriveId(teamDrive.getId())
              .execute();

          updateDriveChangesToken(teamDrive.getId(), response.getStartPageToken());
        }

        pageToken = result.getNextPageToken();
      } while (pageToken != null);
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  /**
   * Returns the published folder for a Team Drive
   *
   * @param service
   * @param teamDrive
   * @return the published folder for a team drive, if it exists
   */
  public File accessPublishedFolder(TeamDrive teamDrive) {
    try {
      FileList list = service.files().list()
          .setTeamDriveId(teamDrive.getId())
          .setSupportsTeamDrives(true)
          .setCorpora("teamDrive")
          .setFields("files(id,name,parents)")
          .setIncludeTeamDriveItems(true)
          .setQ("mimeType='application/vnd.google-apps.folder' and name='published' and trashed=false")
          .execute();

      List<File> fileList = list.getFiles();

      if (fileList.size() > 0) {
        log.debug("Published folder id:" + fileList.get(0).getId());
        return fileList.get(0);
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * This method lists all the files inside the published folder of a Team Drive
   * and sends a download request for these files
   *
   * @param service
   * @param file
   * @param tdteamDrive
   * @throws JSONException
   */
  public void accessPublishedFiles(File file, TeamDrive teamDrive) {

    try {

      String query = "'" + file.getId() + "' in parents and trashed=false";
      String pageToken = null;
      do {
        FileList list = service.files().list()
            .setQ(query)
            .setFields("nextPageToken,files(id,name,mimeType,parents,createdTime,modifiedTime)")
            .setCorpora("teamDrive")
            .setIncludeTeamDriveItems(true)
            .setSupportsTeamDrives(true)
            .setTeamDriveId(teamDrive.getId())
            .setPageToken(pageToken)
            .execute();

        List<File> fileList = list.getFiles();

        System.out.println("File Count:" + fileList.size());

        for (File pubFile : fileList) {

          log.debug("File Name:" + pubFile.getName());
          log.debug("Mime type:" + pubFile.getMimeType());
          String path = getSourcePath(pubFile);
          if ("application/vnd.google-apps.folder".equals(pubFile.getMimeType())) {
            sendMakedirRequest(this.config.get("localStorage") + path, pubFile.getId());
            accessPublishedFiles(pubFile, teamDrive);
          } else {
            sendDownloadRequest(pubFile, path);
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
   * @param path
   * @throws IOException
   * @throws JSONException
   */
  public void sendDownloadRequest(File file, String path) {

    HashMap<String, String> headers = new HashMap<String, String>();

    String localPath = this.config.get("localStorage") + path;
    Path acronymPropertiesFile = Paths.get(this.config.get("driveAcronymProperties"));
    Properties props = new Properties();

    headers.put("action", "download");
    headers.put("source_type", "file");
    headers.put("source_id", file.getId());
    headers.put("source_name", file.getName());
    headers.put("local_path", localPath);
    headers.put("creation_time", file.getCreatedTime().toString());
    headers.put("modified_time", file.getModifiedTime().toString());

    String paths[] = path.split("/");
    String teamDrive = paths[1];

    headers.put("teamDrive", teamDrive);

    if (paths.length > 3) {
      for (String category : categories) {
        if (paths[3].equals(category)) {
          headers.put("category", category);
        }
      }
    }

    try {
      if (Files.exists(acronymPropertiesFile) && Files.size(acronymPropertiesFile) > 0) {

        FileInputStream in = new FileInputStream(acronymPropertiesFile.toFile());
        props.load(in);

        String acronym = (String) props.get(teamDrive.replaceAll(" ", "_"));
        if (acronym != null) {
          headers.put("group", acronym);
        } else {
          log.info("Could not set the group name. Ensure that it exists in the properties file.");
        }

        in.close();
      }

    } catch (IOException e) {

      e.printStackTrace();
    }

    sendActionExchange(headers, "");

  }

  /**
   * Send a new message exchange to ActionListener requesting to update an
   * existing file
   *
   * @param service
   * @param file
   */
  public void sendUpdateContentRequest(File file, String localPath) {

    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("action", "update");
    headers.put("source_id", file.getId());
    headers.put("local_path", localPath);
    headers.put("source_type", "file");
    headers.put("modified_time", file.getModifiedTime().toString());

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
   * Loads the poll token from the properties file, if the properties file
   * exists
   *
   * @param teamDriveId
   * @return the poll token for the Team Drive
   */
  public String loadDriveChangesToken(String teamDriveId) {
    String token = null;
    try {
      String drivePropFile = this.config.get("tokenProperties");
      Path f = Paths.get(drivePropFile);
      if (Files.exists(f) && !Files.isDirectory(f)) {
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

  /**
   * This method updates the token for the Team Drive in the properties file
   *
   * @param teamDriveId
   * @param driveToken
   */
  public void updateDriveChangesToken(String teamDriveId, String driveToken) {
    try {
      String propFilePath = this.config.get("tokenProperties");

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

  /**
   * Checks the fileAttribute properties file for existence of a file and
   * returns the path if fileId exists
   *
   * @param fileId
   * @return boolean
   */
  public String checkFileAttribute(String fileId) {
    try {
      String attrPropFile = this.config.get("fileAttributeProperties");
      Path f = Paths.get(attrPropFile);
      if (Files.exists(f) && !Files.isDirectory(f)) {
        Properties defaultProps = new Properties();
        FileInputStream in = new FileInputStream(attrPropFile);
        defaultProps.load(in);
        String path = defaultProps.getProperty(fileId);
        in.close();
        if (path != null) {
          return path;
        }
      }
    } catch (FileNotFoundException e) {
      log.error("FileAttribute Properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("FileAttribute Properties file cannot be opened" + e.getMessage());
    }
    return null;
  }

  /**
   * Updates this fileattribute properties file with the File id and spurce path
   * of each file
   *
   * @param teamDriveId
   * @param driveToken
   */
  public void updateFileAttributeProperties(String fileId, String path) {
    try {
      String propFilePath = this.config.get("fileAttributeProperties");
      FileInputStream in = new FileInputStream(propFilePath);
      Properties props = new Properties();
      props.load(in);
      in.close();
      FileOutputStream out = new FileOutputStream(propFilePath);
      props.setProperty(fileId, path);
      props.store(out, "File updated by the program - Do not delete");
      out.close();
    } catch (FileNotFoundException e) {
      log.error("FileAttribute properties file not found" + e.getMessage());
    } catch (IOException e) {
      log.error("FileAttribute properties file cannot be opened" + e.getMessage());
    }
  }

  /**
   * Gets the absolute path of a file or folder as it stands in Drive storage.
   *
   * @param service
   * @param item
   * @return the source path (as it stands in Google Drive) for a file
   * @throws IOException
   */
  public String getSourcePath(File item) {

    String itemName = item.getName();
    String parentID = item.getParents().get(0);
    Stack<String> path = new Stack<String>();
    StringBuilder fullPathBuilder = new StringBuilder();
    path.push(itemName);

    try {
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
    } catch (IOException ex) {
      ex.printStackTrace();
    }

    while (!path.isEmpty()) {
      fullPathBuilder.append("/").append(path.pop());
    }

    String sourceMimeType = item.getMimeType();

    if (sourceMimeType.equals("application/vnd.google-apps.document")) {
      fullPathBuilder.append(".docx");
    } else if (sourceMimeType.equals("application/vnd.google-apps.spreadsheet")) {
      fullPathBuilder.append(".xlsx");
    } else if (sourceMimeType.equals("application/vnd.google-apps.drawing")) {
      fullPathBuilder.append(".jpg");
    } else if (sourceMimeType.equals("application/vnd.google-apps.presentation")) {
      fullPathBuilder.append(".pptx");
    } else if (sourceMimeType.equals("application/vnd.google-apps.script")) {
      fullPathBuilder.append(".json");
    }

    return fullPathBuilder.toString();
  }

  /**
   *
   * @param fileId
   * @return the list of files within a directory
   * @throws IOException
   */

  public List<File> fetchFileList(String fileId) {

    log.debug("Inside fetchFileList");
    List<File> fullFilesList = new ArrayList<File>();
    String teamDriveId = null;

    try {
      teamDriveId = service.files().get(fileId)
          .setSupportsTeamDrives(true)
          .execute().getTeamDriveId();

      log.debug("Team DriveId:" + teamDriveId);
      String query = "'" + fileId + "' in parents and trashed=false";
      String pageToken = null;
      do {
        FileList list = service.files().list()
            .setQ(query)
            .setFields("nextPageToken,files(id,name,mimeType,parents)")
            .setCorpora("teamDrive")
            .setIncludeTeamDriveItems(true)
            .setSupportsTeamDrives(true)
            .setTeamDriveId(teamDriveId)
            .setPageToken(pageToken)
            .setPageSize(50)
            .execute();

        List<File> fileList = list.getFiles();

        fullFilesList.addAll(fileList);

        for (File f : fileList) {
          if ("application/vnd.google-apps.folder".equals(f.getMimeType())) {
            fullFilesList.addAll(fetchFileList(f.getId()));
          }
        }

        pageToken = list.getNextPageToken();
      } while (pageToken != null);

    }

    catch (IOException e) {
      e.printStackTrace();
    }

    return fullFilesList;
  }

  public void updateFilePathChanges(File changeItem) {

    HashMap<String, String> headers = new HashMap<String, String>();
    List<File> files = fetchFileList(changeItem.getId());

    for (File file : files) {
      String originalPath = checkFileAttribute(file.getId());

      if (originalPath != null) {
        String url = getSourcePath(file);
        String fullPath = this.config.get("localStorage") + url;

        updateFileAttributeProperties(file.getId(), fullPath);

        if (!"application/vnd.google-apps.folder".equals(file.getMimeType())) {

          headers.put("action", "update_paths");
          headers.put("source_id", file.getId());
          headers.put("local_path", fullPath);
          headers.put("source_type", "file");

          sendActionExchange(headers, "");
        }
      }

    }

  }

}
