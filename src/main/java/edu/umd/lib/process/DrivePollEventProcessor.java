package edu.umd.lib.process;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Revision;
import com.google.api.services.drive.model.RevisionList;
import com.google.api.services.drive.model.StartPageToken;
import com.google.api.services.drive.model.TeamDrive;
import com.google.api.services.drive.model.TeamDriveList;

import edu.umd.lib.services.GoogleDriveConnector;

/****
 * Create a JSON to add the file to Solr. Connect to Drive and download the whole
 * file that needs to be indexed.
 *
 * @author audani
 *
 */
public class DrivePollEventProcessor implements Processor {
	
	private static Logger log = Logger.getLogger(DrivePollEventProcessor.class);
	Map<String, String> config;
  	ProducerTemplate producer;
 
	private String pageToken;


  public DrivePollEventProcessor(Map<String, String> config) {
    this.config = config;
  }

  @Override
  public void process(Exchange exchange) throws Exception {
	  
	  loadPageToken();
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
	  
	  String token = this.pageToken;
	  if (token.equals("0")) {

	      downloadAllFiles(service);

	      //List<TeamDrive> teamDriveList = service.teamdrives().list().execute().getTeamDrives();
	      
	      
	      // save latest page token
	      StartPageToken response = service.changes().getStartPageToken()
	    		  .setSupportsTeamDrives(true)
	    		  .execute();
	      this.pageToken = response.getStartPageToken();
	      updatePageToken(this.pageToken);
	       

	    } /*else {

	      while (token != null) {

	        ChangeList changes = service.changes().list(token)
	        		.setIncludeTeamDriveItems(true)
	        		.setSupportsTeamDrives(true)
	        		.execute();

	        for (Change change : changes.getChanges()) {

	          File changeItem = change.getFile();
	          log.info("Change detected for item: "
	              + changeItem.getName());

	          if (change.getRemoved()) {
	            sendDeleteRequest(service, change);

	          } else if (changeItem.getMimeType().equals("application/vnd.google-apps.folder")){
	        	  continue;
	          }
	          else {

	            sendDownloadRequest(service, changeItem);
	          }
	        }

	        // save latest page token
	        if (changes.getNewStartPageToken() != null) {
	          this.pageToken = changes.getNewStartPageToken();
	          updatePageToken(this.pageToken);
	        }

	        token = changes.getNextPageToken();

	      }

	    }*/
 
	  
  }
  
  /**
   * Sends a new message exchange to ActionListener requesting to delete a file
   * or folder from the local system, along with its children.
   *
   * @param service
   * @param file
   * @throws IOException
   */
  private void sendDeleteRequest(Drive service, Change change) throws IOException {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "delete");
    headers.put("source_id", change.getFileId());
    headers.put("details", "remove_childen");

    // get revisions of deleted file
    RevisionList revList = service.revisions()
        .list(change.getFileId())
        .execute();

    List<Revision> revisions = revList.getRevisions();
    String prevRevID = revisions.get(0).getId();

    File deletedFile = service.files()
        .get(change.getFileId())
        .set("revisionId", prevRevID)
        .execute();

    // file source path of the deleted file
    headers.put("source_path", getSourcePath(service, deletedFile));

    if (deletedFile.getMimeType().equals("application/vnd.google-apps.folder")) {
      headers.put("source_type", "folder");
    } else {
      headers.put("source_type", "file");
    }

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
    
    do {
    TeamDriveList result = service.teamdrives().list()
    		.setPageToken(pageToken)
    		.execute();
    
    List<TeamDrive> teamDrives = result.getTeamDrives();
    log.debug("Total no. of Team Drives:" + teamDrives.size());
    
    for (TeamDrive td : teamDrives) {
    	log.debug("Team Drive ID:" + td.getId() + "\t Team Drive Name:" + td.getName());

		StringBuilder path = new StringBuilder(config.get("localStorage")); 
    	File publishedFolder = accessPublishedFolder(service, td);
    	
    	if(publishedFolder != null) {
    		path.append("//" + td.getName() + "//" + "published");
    		accessPublishedFiles(service, publishedFolder, td, path);
    	}
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
		
		if(fileList.size() > 0) {
		log.info("Published folder id:" + fileList.get(0).getId());
			return fileList.get(0);
		}
		
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
  	return null;
  }
  	
  	public void accessPublishedFiles(Drive service, File file, TeamDrive td, StringBuilder path) throws JSONException {
  		
  		try {
  	
  			String query = "'" + file.getId() + "' in parents and trashed=false";
				String pageToken=null;
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
				
				for (File f : fileList) {
					
					System.out.println("File Name:" + f.getName());
					System.out.println("Mime type:" + f.getMimeType());
					
					if("application/vnd.google-apps.folder".equals(f.getMimeType())) {
						
						accessPublishedFiles(service, f, td, new StringBuilder(path).append("//").append(f.getName()));
						continue;
					}
					
					sendDownloadRequest(service, f, path.append("//" + f.getName()).toString());
					
				}
				pageToken = list.getNextPageToken();
				} while(pageToken!=null);
				
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

      headers.put("action", "download");
      headers.put("source_type", "file");
      headers.put("source_id", file.getId());
      headers.put("source_path", path);
      headers.put("source_name", file.getName());
      

      JSONObject meta = new JSONObject();
      meta.put("description", file.getDescription());
      headers.put("metadata", meta.toString());      

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
   * Create the properties file and load the stream position
   * if properties file exists load the steam position for the file.
   */
  public void loadPageToken(){
    try {
      String drivePropFile = this.config.get("propertiesName");
      java.io.File f = new java.io.File(drivePropFile);
      if(f.exists() && !f.isDirectory()) {
        Properties defaultProps = new Properties();
        FileInputStream in = new FileInputStream(drivePropFile);
        defaultProps.load(in);
        this.pageToken = defaultProps.getProperty("pagetoken");
        in.close();
      }else{
        log.error("create Properties file");
        Properties properties = new Properties();
        properties.setProperty("pagetoken", "0");
        this.pageToken = "0";
        java.io.File file = new java.io.File(drivePropFile);
        FileOutputStream fileOut = new FileOutputStream(file);
        properties.store(fileOut, "Drive Page token updated by the program - Do not delete");
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
  public void updatePageToken(String pageToken){
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

    String fullPath = null;
    String itemName = item.getName();
    List<String> parentIDs = item.getParents();

    StringBuffer fullPathStringBuffer = new StringBuffer("");

    if (parentIDs == null) {
      fullPath = itemName;
    } else {
      for (String id : parentIDs) {
        File parent = service.files().get(id).execute();
        String parentName = parent.getName();
        fullPathStringBuffer.append("/").append(parentName);
      }
    }

    fullPathStringBuffer.append("/").append(itemName);
    fullPath = fullPathStringBuffer.toString();

    return fullPath;
  }
  
}