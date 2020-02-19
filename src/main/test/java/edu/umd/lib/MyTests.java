package edu.umd.lib;

import com.google.api.client.http.FileContent;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.DriveList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import edu.umd.lib.process.DrivePollEventProcessor;
import edu.umd.lib.services.GoogleDriveConnector;
import org.apache.commons.io.FilenameUtils;
import org.junit.*;
import java.io.IOException;
import java.util.*;

public class MyTests {
  private static String testDriveID = "0AF_Xa1NImn7uUk9PVA",published_id;
  private DrivePollEventProcessor test;
  private static com.google.api.services.drive.Drive service;

  private static Map<String, String> mimetype = new HashMap<String, String>() {{
    put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    put("pdf", "application/pdf");
    put("jpeg", "image/jpeg");
    put("jpg", "image/jpeg");
    put("doc", "application/msword");
    put("xls", "application/vnd.ms-excel");
    put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    put("ppt", "application/vnd.ms-powerpoint");
    put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
  }};
  private static Map<String, String> config = new HashMap<String, String>() {{
    put("solrScheme", "http");
    put("solrBaseUrl", "localhost:8983/solr/libi");
    put("driveAcronymProperties", "/Users/pankul/apps/git/driveacronym.properties");
    put("maxCacheTries", "");
    put("appName", "Drive Integration");
    put("clientSecretFile", "/Users/pankul/apps/git/Harvester-client.json");
    put("tokenProperties", "/Users/pankul/apps/git/googledrivetoken.properties");
    put("allowedFileSize", "1000000");
  }};

  @Before
  public void setUp() throws IOException {
    test = new DrivePollEventProcessor(config);
    service = new GoogleDriveConnector(config).getDriveService();
    File data = new File();
    data.setName("published");
    data.setMimeType("application/vnd.google-apps.folder");
    data.setParents(Collections.singletonList(testDriveID));
    File file = service.files().create(data).
        setFields("id, parents").
        setSupportsTeamDrives(true).
        execute();
    published_id = file.getId();
  }

  @After
  public void destroySetUp() throws IOException{
    service.files().
        delete(published_id).
        setSupportsTeamDrives(true).
        execute();
  }

  public void deleteFilesAndFolders(String names, String folder) throws IOException{
    if(folder == null || folder.length() == 0)
      folder = "published";

    if(names == null || names.length() == 0){
      FileList files = service.files().list().setSupportsAllDrives(true).execute();
      for(File x: files.getFiles()) {
         if("application/vnd.google-apps.folder".equals(x.getMimeType()) && folder.equals(x.getName())){
           folder = x.getId();
           break;
         }
      }
      service.files().delete(folder).setSupportsTeamDrives(true).execute();
    }

    FileList files = service.files().list()
        .setDriveId(testDriveID)
        .setSupportsAllDrives(true)
        .setCorpora("drive")
        .setFields("files(id,name,parents,mimeType)")
        .setIncludeItemsFromAllDrives(true)
        .setQ("mimeType='application/vnd.google-apps.folder' and name='"+folder+"' and trashed=false")
        .execute();

    for(File x: files.getFiles()) {
      if("application/vnd.google-apps.folder".equals(x.getMimeType()) && folder.equals(x.getName())){
        folder = x.getId();
        break;
      }
    }

    FileList filesWithParentID = service.files().list()
        .setDriveId(testDriveID)
        .setCorpora("drive")
        .setSupportsAllDrives(true)
        .setQ("'" + folder + "' in parents and trashed=false")
        .setFields("files(id,name,parents,mimeType)")
        .setIncludeItemsFromAllDrives(true)
        .execute();

    List<String> name = Arrays.asList(names.split(","));
    for(String x : name){
      for(File y: filesWithParentID.getFiles()) {
        if (x.equals(y.getName()) && y.getParents().contains(folder)) {
          service.files().
              delete(y.getId()).
              setSupportsTeamDrives(true).
              execute();
        break;
        }
      }
    }


  }

  public void addFiles(String names, String folder) throws IOException {

    String id = addNewFoldersToPublishedFolder(folder);

    if (id == null)
        id = published_id;

    List<String> name = Arrays.asList(names.split(","));
    for (String n : name) {
      File fileMetadata = new File();
      fileMetadata.setName(n);
      fileMetadata.setParents(Collections.singletonList(id));
      java.io.File filePath = new java.io.File("./src/main/test/resources/" + n);
      FileContent mediaContent = new FileContent(mimetype.get(FilenameUtils.getExtension(n)), filePath);
      service.files().create(fileMetadata, mediaContent)
          .setFields("id, parents")
          .setSupportsAllDrives(true)
          .execute();
    }
  }

  public String addNewFoldersToPublishedFolder(String names) throws IOException {

    if ("published".equals(names))
      return null;

    File data = new File();
    data.setName(names);
    data.setMimeType("application/vnd.google-apps.folder");
    data.setParents(Collections.singletonList(published_id));
    File file = service.files().create(data).
        setFields("id, parents").
        setSupportsTeamDrives(true).
        execute();
    return file.getId();
  }

  @Test
  public void isAccessingPubishedFolder() throws IOException {
    addFiles("hi.docx,sample.pdf","published");
    addFiles("Hacking modern Vending Machine.pdf","links");
    DriveList result = service.drives().list().execute();
    File file = test.accessPublishedFolder(result.getDrives().get(0));
    int count = test.accessPublishedFiles(file,result.getDrives().get(0));
    Assert.assertEquals(3,count);
  }

  @Test
  public void isManagingDeleteEvents() throws IOException{
    addFiles("hi.docx","published");
    addFiles("Hacking modern Vending Machine.pdf,hi.docx","links");
    deleteFilesAndFolders("hi.docx","published");
    DriveList result = service.drives().list().execute();
    ChangeList changes = test.getChangeList(null,result.getDrives().get(0));
    if(changes.getChanges().get(0).getRemoved() ||changes.getChanges().get(0).getTrashed())
    Assert.assertEquals(3,3);

  }
}
