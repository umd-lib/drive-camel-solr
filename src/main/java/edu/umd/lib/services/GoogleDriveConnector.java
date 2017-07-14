package edu.umd.lib.services;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.ProducerTemplate;
import org.apache.log4j.Logger;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

public class GoogleDriveConnector {
	
	//private final String accountID;
	private final String appName;
	private final String clientSecretFileName;
	private final java.io.File dataStoreDir;
	private FileDataStoreFactory dataStoreFactory;
	 
	
	private static Logger log = Logger.getLogger(GoogleDriveConnector.class);

	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	private static HttpTransport HTTP_TRANSPORT;
	private static final List<String> SCOPES = Arrays.asList(DriveScopes.DRIVE);

	static {
		try {
			HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
	    } catch (Throwable t) {
	      t.printStackTrace();
	      System.exit(1);
	    }
	}

	/**
	 * Constructs a google drive connector from the 
	 *
	 * @param account
	 * @param producer
	 */
	
	public GoogleDriveConnector(Map<String,String> config) {

    this.appName = config.get("appName");
    this.clientSecretFileName = config.get("clientSecretFile");
    this.dataStoreDir = new java.io.File(config.get("localStorage"), ".credentials/googledrive_" + config.get("appName"));
    try {
      this.dataStoreFactory = new FileDataStoreFactory(this.dataStoreDir);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(1);
    }
    }
	
	 
	  /**
	   * Build and return an authorized Drive client service.
	   *
	   * @return an authorized Drive client service
	   * @throws IOException
	   */
	 public Drive getDriveService() throws IOException {
	    Credential credential = authorize();
	    return new Drive.Builder(
	        HTTP_TRANSPORT, JSON_FACTORY, credential)
	            .setApplicationName(this.appName)
	            .build();
	 }
	  
	  /**
	   * Returns credential object for an authorized connection to a google drive
	   * account
	   *
	   * @return
	   * @throws IOException
	   */
	  public Credential authorize() throws IOException {
	    // Load client secrets.
	    InputStream in = new FileInputStream(this.clientSecretFileName);
	    GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

	    // Build flow and trigger user authorization request.
	    // TODO: modify builder so user and API developer can be treated separately
	    GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
	        HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
	            .setDataStoreFactory(this.dataStoreFactory)
	            .setAccessType("offline")
	            .build();
	    Credential credential = new AuthorizationCodeInstalledApp(
	        flow, new LocalServerReceiver()).authorize("user");
	    log.info(
	        "Credentials saved to " + this.dataStoreDir.getAbsolutePath());

	    return credential;
	  }

}