package edu.umd.lib.services;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

public class GoogleDriveConnector {

  private final String appName;
  private final String clientSecretFileName;

  private static Logger log = Logger.getLogger(GoogleDriveConnector.class);

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
  private static HttpTransport HTTP_TRANSPORT;
  private static final List<String> SCOPES = Arrays.asList(DriveScopes.DRIVE, DriveScopes.DRIVE_METADATA);

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
   * @param config
   */

  public GoogleDriveConnector(Map<String, String> config) {

    this.appName = config.get("appName");
    this.clientSecretFileName = config.get("clientSecretFile");
  }

  /**
   * Build and return an authorized Drive client service.
   *
   * @return an authorized Drive client service
   * @throws IOException
   */
  public Drive getDriveService() throws IOException {
    final GoogleCredential credential = authorize();
    return new Drive.Builder(
        HTTP_TRANSPORT, JSON_FACTORY, credential).setHttpRequestInitializer(new HttpRequestInitializer() {
          @Override
          public void initialize(HttpRequest request) throws IOException {

            credential.initialize(request);
            request.setConnectTimeout(3 * 60000); // 3 minutes connect timeout
                                                  
            request.setReadTimeout(3 * 60000); // 3 minutes read timeout

          }

        }).setApplicationName(this.appName).build();
  }

  /**
   * Returns credential object for an authorized connection to a google drive
   * account
   *
   * @return
   * @throws IOException
   */
  public GoogleCredential authorize() throws IOException {
    GoogleCredential credential = GoogleCredential.fromStream(new FileInputStream(this.clientSecretFileName))
        .createScoped(SCOPES);
    return credential;
  }
}
