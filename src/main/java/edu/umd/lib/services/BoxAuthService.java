package edu.umd.lib.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.log4j.Logger;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxDeveloperEditionAPIConnection;
import com.box.sdk.BoxUser;
import com.box.sdk.BoxUser.Info;
import com.box.sdk.CreateUserParams;
import com.box.sdk.EncryptionAlgorithm;
import com.box.sdk.IAccessTokenCache;
import com.box.sdk.InMemoryLRUAccessTokenCache;
import com.box.sdk.JWTEncryptionPreferences;

import edu.umd.lib.exception.BoxCustomException;

public class BoxAuthService {

  private static Logger log = Logger.getLogger(BoxAuthService.class);

  private String CLIENT_ID = "";
  private String CLIENT_SECRET = "";
  private String ENTERPRISE_ID = "";
  private String PUBLIC_KEY_ID = "";
  private String PRIVATE_KEY_FILE = "";
  private String PRIVATE_KEY_PASSWORD = "";
  private String APP_USER_NAME = "";
  private int MAX_CACHE_ENTRIES = 100;
  private String USER_ID = "";

  Map<String, String> config;
  /***
   * Initializing the Box Configuration
   *
   * @param config
   */
  public BoxAuthService(Map<String, String> config) {

    CLIENT_ID = config.get("clientID");
    CLIENT_SECRET = config.get("clientSecret");
    ENTERPRISE_ID = config.get("enterpriseID");
    PUBLIC_KEY_ID = config.get("publicKeyID");
    PRIVATE_KEY_FILE = config.get("privateKeyFile");
    PRIVATE_KEY_PASSWORD = config.get("privateKeyPassword");
    APP_USER_NAME = config.get("appUserName");
    MAX_CACHE_ENTRIES = Integer.parseInt(config.get("maxCacheTries"));
    USER_ID = "";
    this.config = config;
  }

  /****
   * This Method connects to box API using APP user and returns the Connection.
   *
   * @return
   * @throws IOException
   * @throws BoxCustomException
   */
  public BoxAPIConnection getBoxAPIConnection() throws IOException, BoxCustomException {

    if(this.config.containsKey("appUserId")){

      this.USER_ID = config.get("appUserId");
      BoxAPIConnection api = getAppUserConnection();
      log.info("User ID already known:" + this.USER_ID);
      return api;

    } else {
      log.info("User ID not known:");
      BoxDeveloperEditionAPIConnection api = getAppEnterpriseConnection();
      String appUserID = getAppUserID(api);
      if (appUserID.equalsIgnoreCase("0")) {
        log.info("Create new APP user ID since App user not found in Box.");
        appUserID = createAppUser(api);
      } else {
        log.info("App user found:" + appUserID);
      }
      this.USER_ID = appUserID;
      this.config.put("appUserId", USER_ID);
      api = getAppUserConnection();
      return api;
    }

  }



  /****
   * Connect to Box API as Enterprise User
   *
   * @return
   * @throws IOException
   * @throws BoxCustomException
   */
  public BoxDeveloperEditionAPIConnection getAppEnterpriseConnection()
      throws IOException, BoxCustomException {

    BoxDeveloperEditionAPIConnection api;
    try {
      log.info("Connecting to Box as Enterprise User.");
      String privateKey = new String(Files.readAllBytes(Paths.get(PRIVATE_KEY_FILE)));

      JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
      encryptionPref.setPublicKeyID(PUBLIC_KEY_ID);
      encryptionPref.setPrivateKey(privateKey);
      encryptionPref.setPrivateKeyPassword(PRIVATE_KEY_PASSWORD);
      encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

      // It is a best practice to use an access token cache to prevent unneeded
      // requests to Box for access tokens.
      // For production applications it is recommended to use a distributed
      // cache
      // like Memcached or Redis, and to
      // implement IAccessTokenCache to store and retrieve access tokens
      // appropriately for your environment.
      IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(MAX_CACHE_ENTRIES);
      api = BoxDeveloperEditionAPIConnection.getAppEnterpriseConnection(
          ENTERPRISE_ID, CLIENT_ID, CLIENT_SECRET, encryptionPref, accessTokenCache);

    } catch (BoxAPIException e) {
      throw new BoxCustomException(
          "Error connecting to Box API as Enterprise user. Verify Box Configuration Properties. " + e.getMessage());

    }
    return api;
  }

  /****
   * Connect to Box API as App User
   *
   * @return
   * @throws IOException
   * @throws BoxCustomException
   */
  public BoxDeveloperEditionAPIConnection getAppUserConnection()
      throws IOException, BoxCustomException {

    log.info("Connecting to Box as APP User.");
    BoxDeveloperEditionAPIConnection api;
    try {
      String privateKey = new String(Files.readAllBytes(Paths.get(PRIVATE_KEY_FILE)));

      JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
      encryptionPref.setPublicKeyID(PUBLIC_KEY_ID);
      encryptionPref.setPrivateKey(privateKey);
      encryptionPref.setPrivateKeyPassword(PRIVATE_KEY_PASSWORD);
      encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

      // It is a best practice to use an access token cache to prevent unneeded
      // requests to Box for access tokens.
      // For production applications it is recommended to use a distributed
      // cache
      // like Memcached or Redis, and to
      // implement IAccessTokenCache to store and retrieve access tokens
      // appropriately for your environment.
      IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(MAX_CACHE_ENTRIES);
      api = BoxDeveloperEditionAPIConnection.getAppUserConnection(USER_ID, CLIENT_ID,
          CLIENT_SECRET, encryptionPref, accessTokenCache);

    } catch (BoxAPIException e) {
      throw new BoxCustomException(
          "Error connecting to Box API as App user. Verify Box Configuration Properties. " + e.getMessage());

    }

    return api;

  }

  /****
   * Create App User with Platform Access only when app user is not found
   *
   * @throws BoxCustomException
   */
  public String createAppUser(BoxDeveloperEditionAPIConnection api) throws IOException, BoxCustomException {

    CreateUserParams params = new CreateUserParams();
    try {
      params.setSpaceAmount(1073741824); //
      BoxUser.Info user = BoxUser.createAppUser(api, APP_USER_NAME, params);
      return user.getID();
    } catch (BoxAPIException e) {
      throw new BoxCustomException(
          "Error creating APP User. Verify Box Configuration Properties. " + e.getMessage());
    }

  }

  /*****
   * This Method connects to Box API as enterprise user and gets all Enterprise
   * users. From the list of enterprise user APP user's ID is found and
   * returned.
   *
   * @param api
   * @return
   */
  public String getAppUserID(BoxAPIConnection api) {
    Iterable<Info> user = BoxUser.getAllEnterpriseUsers(api);
    log.info("Connecting to Box to retrive all Enterprise user and find App user ID.");
    for (Info info : user) {
      if (APP_USER_NAME.equalsIgnoreCase(info.getName())) {
        return info.getID();
      }
    }
    return "0";
  }

  /**
   * @return the uSER_ID
   */
  public String getUSER_ID() {
    return USER_ID;
  }

  /**
   * @param uSER_ID
   *          the uSER_ID to set
   */
  public void setUSER_ID(String uSER_ID) {
    USER_ID = uSER_ID;
  }

}
