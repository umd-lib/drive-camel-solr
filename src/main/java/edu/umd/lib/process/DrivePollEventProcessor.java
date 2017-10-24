package edu.umd.lib.process;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
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
        // Perform bulk download of files
        downloadAllFiles();

      } else

      {
        // Fetch incremental changes i.e. changes that have occurred since the
        // last polling action
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

                if (changeItem != null) {
                  boolean isGoogleDoc = chkIfGoogleDoc(changeItem.getMimeType());

                  if (!isGoogleDoc) {
                    log.info("Change detected for item: " + changeItem.getId() + ":" + changeItem.getName());

                    String sourcePath = getSourcePath(changeItem);
                    log.debug("Source Path of accessed file:" + sourcePath);

                    // We are interested only in the changes that occur inside
                    // the published folder
                    if ("published".equals(sourcePath.split("/")[2])) {

                      // Delete event
                      if (change.getRemoved() || changeItem.getTrashed()) {

                        manageDeleteEvent(changeItem, sourcePath);
                      } else if (changeItem.getMimeType().equals("application/vnd.google-apps.folder")) {
                        if (!"published".equals(changeItem.getName())) {

                          manageDirectoryEvents(changeItem, sourcePath);
                        }
                      } else {
                        manageFileEvents(changeItem, sourcePath);
                      }

                    } // End of published folder check
                  } // End of isGoogleDoc check
                } // End of null check
              } // End of for loop for changes

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
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  /**
   * This method handles all events related to file changes
   *
   * @param changeItem
   * @param sourcePath
   */
  public void manageFileEvents(File changeItem, String sourcePath) {

    // Either its a new file download, file rename or a file
    // update request

    String storedFilePath = checkFileAttribute(changeItem.getId());

    if (storedFilePath == null) {
      // New file download request
      log.info("New File download request");
      sendDownloadRequest(changeItem, sourcePath);
    } else {

      Path file = Paths.get(storedFilePath);
      Path storedFileName = file.getFileName();

      // Checking for file content update
      if (!changeItem.getMd5Checksum().equals(getMd5ForFile(file))) {
        log.info("File update request");
        sendUpdateContentRequest(changeItem, storedFilePath);
      }

      // Checking for file rename
      if (!storedFileName.toString().equals(changeItem.getName())) {
        log.info("File Rename request");
        sendFileRenameRequest(storedFilePath, changeItem);
      }

      // For checking file move, we are comparing the paths
      // without the file name.
      // (we are skipping the filename because in the
      // scenario that a file rename + file move event
      // occurs we are already handling file rename
      // separately, and if we keep the filename in the path
      // while checking for the file move event, this event
      // will be triggered even when the file rename occurs.
      String localPathServerFile = this.config.get("localStorage")
          + sourcePath.substring(0, sourcePath.lastIndexOf("/"));
      String localFilePath = storedFilePath.substring(0, storedFilePath.lastIndexOf("/"));

      // Checking for file move request
      if (!localPathServerFile.equals(localFilePath)) {
        sendFileMoveRequest(changeItem, localFilePath + "/" + storedFileName,
            localPathServerFile + "/" + storedFileName, sourcePath);
      }
    }
  }

  /**
   * This method handles all the events related to directory changes
   *
   * @param changeItem
   * @param sourcePath
   */
  public void manageDirectoryEvents(File changeItem, String sourcePath) {

    String directoryPath = this.config.get("localStorage") + sourcePath;
    String storedFilePath = checkFileAttribute(changeItem.getId());

    if (storedFilePath == null) {
      log.info("Makedir request");
      sendMakedirRequest(directoryPath, changeItem.getId());
    } else {

      Path file = Paths.get(storedFilePath);
      Path storedDirName = file.getFileName();

      if (!storedDirName.toString().equals(changeItem.getName())) {

        log.info("Directory Rename request");
        sendDirRenameRequest(storedFilePath, directoryPath, changeItem);

        log.info("Update File Paths request");
        updateFilePathChanges(changeItem.getId());
      }

      String localPathServerFile = this.config.get("localStorage")
          + sourcePath.substring(0, sourcePath.lastIndexOf("/"));
      String localFilePath = storedFilePath.substring(0, storedFilePath.lastIndexOf("/"));

      // Checking for directory move request
      if (!localPathServerFile.equals(localFilePath)) {

        log.info("Directory Move request");
        sendDirMoveRequest(changeItem, localFilePath + "/" + storedDirName,
            localPathServerFile + "/" + storedDirName, sourcePath);

        log.info("Update File Paths request");
        updateFilePathChanges(changeItem.getId());
      }

    }

  }

  /**
   * This method handles the delete event for files and directories
   *
   * @param changeItem
   * @param sourcePath
   */
  public void manageDeleteEvent(File changeItem, String sourcePath) {
    if ("application/vnd.google-apps.folder".equals(changeItem.getMimeType())) {

      log.info("Directory Delete request");
      updateFileAttributeProperties(changeItem.getId(), null, "delete");

      List<File> files = fetchFileList(changeItem.getId());

      for (File file : files) {

        if (!"application/vnd.google-apps.folder".equals(file.getMimeType())) {
          sendDeleteRequest(file, getSourcePath(file));
        } else {
          updateFileAttributeProperties(file.getId(), null, "delete");
        }
      }
    }

    if (!"application/vnd.google-apps.folder".equals(changeItem.getMimeType())) {

      log.info("File Delete request");
      sendDeleteRequest(changeItem, sourcePath);
    }
  }

  /**
   * This method is used to check if the file is a Google Doc
   *
   * @param mimeType
   * @return boolean
   */

  public boolean chkIfGoogleDoc(String mimeType) {

    if (mimeType.equals("application/vnd.google-apps.document")
        || mimeType.equals("application/vnd.google-apps.spreadsheet")
        || mimeType.equals("application/vnd.google-apps.drawing")
        || mimeType.equals("application/vnd.google-apps.presentation")
        || mimeType.equals("application/vnd.google-apps.script")) {
      return true;
    }

    return false;
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

    String fileName = changedFile.getName();

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
   */

  public void sendDirRenameRequest(String oldFilePath, String newFilePath, File changedFile) {

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
   */
  public void sendDeleteRequest(File changeItem, String sourcePath) {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "delete");
    headers.put("source_id", changeItem.getId());
    headers.put("local_path", this.config.get("localStorage") + sourcePath);
    headers.put("source_type", "file");
    sendActionExchange(headers, "");
  }

  /**
   * Sends a new message exchange to ActionListener requesting to move a file
   *
   * @param file
   * @param localFilePath
   * @param localFilePathServerFile
   */

  public void sendFileMoveRequest(File file, String localFilePath, String localPathServerFile, String srcPath) {
    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "move_file");
    headers.put("source_type", "file");
    headers.put("source_id", file.getId());
    headers.put("local_path", localPathServerFile);
    headers.put("old_path", localFilePath);

    String paths[] = srcPath.split("/");

    // We have this condition to check if the file has been moved to another
    // category. In that case, we need to update the category in Solr too.
    if (paths.length > 3) {
      for (String category : categories) {
        if (paths[3].equals(category)) {
          headers.put("category", category);
        }
      }
    }
    sendActionExchange(headers, "");
  }

  public void sendDirMoveRequest(File file, String localFilePath, String localPathServerFile, String srcPath) {
    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "move_dir");
    headers.put("source_type", "directory");
    headers.put("source_id", file.getId());
    headers.put("local_path", localPathServerFile);
    headers.put("old_path", localFilePath);

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
  public void downloadAllFiles() {

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

        for (File pubFile : fileList) {

          log.debug("File Name:" + pubFile.getName());
          log.debug("Mime type:" + pubFile.getMimeType());
          String path = getSourcePath(pubFile);
          if ("application/vnd.google-apps.folder".equals(pubFile.getMimeType())) {
            sendMakedirRequest(this.config.get("localStorage") + path, pubFile.getId());
            accessPublishedFiles(pubFile, teamDrive);
          } else {
            if (!chkIfGoogleDoc(pubFile.getMimeType()))
              sendDownloadRequest(pubFile, path);
          }

        }
        pageToken = list.getNextPageToken();
      } while (pageToken != null);

    } catch (Exception ex) {

      ex.printStackTrace();
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
   * Updates this fileattribute properties file with the File id and source path
   * of each file
   *
   * @param teamDriveId
   * @param driveToken
   */
  public void updateFileAttributeProperties(String fileId, String path, String action) {
    try {
      String propFilePath = this.config.get("fileAttributeProperties");
      FileInputStream in = new FileInputStream(propFilePath);
      Properties props = new Properties();
      props.load(in);
      in.close();
      FileOutputStream out = new FileOutputStream(propFilePath);
      if ("delete".equals(action)) {
        props.remove(fileId);
      } else {
        props.setProperty(fileId, path);
      }
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
    } catch (Exception e) {
      e.printStackTrace();
    }

    while (!path.isEmpty()) {
      fullPathBuilder.append("/").append(path.pop());
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

          fullFilesList.addAll(fetchFileList(f.getId()));

        }

        pageToken = list.getNextPageToken();
      } while (pageToken != null);

    }

    catch (IOException e) {
      e.printStackTrace();
    }

    return fullFilesList;
  }

  public void updateFilePathChanges(String fileId) {

    HashMap<String, String> headers = new HashMap<String, String>();
    List<File> files = fetchFileList(fileId);

    for (File file : files) {
      String originalPath = checkFileAttribute(file.getId());

      if (originalPath != null) {
        String url = getSourcePath(file);
        String fullPath = this.config.get("localStorage") + url;

        headers.put("action", "update_paths");
        headers.put("source_id", file.getId());
        headers.put("local_path", fullPath);

        if (!"application/vnd.google-apps.folder".equals(file.getMimeType())) {
          headers.put("source_type", "file");

          String paths[] = url.split("/");

          // We have this condition to check if the file has been moved to
          // another
          // category. In that case, we need to update the category in Solr too.
          if (paths.length > 3) {
            for (String category : categories) {
              if (paths[3].equals(category)) {
                headers.put("category", category);
              }
            }
          }
        }

        sendActionExchange(headers, "");
      }

    }

  }

}
