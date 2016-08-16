package edu.umd.lib.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxDeveloperEditionAPIConnection;
import com.box.sdk.BoxUser;
import com.box.sdk.BoxUser.Info;
import com.box.sdk.CreateUserParams;
import com.box.sdk.EncryptionAlgorithm;
import com.box.sdk.IAccessTokenCache;
import com.box.sdk.InMemoryLRUAccessTokenCache;
import com.box.sdk.JWTEncryptionPreferences;

public class BoxAuthService {

  private String CLIENT_ID = "";
  private String CLIENT_SECRET = "";
  private String ENTERPRISE_ID = "";
  private String PUBLIC_KEY_ID = "";
  private String PRIVATE_KEY_FILE = "";
  private String PRIVATE_KEY_PASSWORD = "";
  private String APP_USER_NAME = "";
  private int MAX_CACHE_ENTRIES = 100;
  private String USER_ID = "";

  /****
   * Connect to Box as Enterprise User
   *
   * @return
   * @throws IOException
   */
  public BoxDeveloperEditionAPIConnection getAppEnterpriseConnection(String privateKey,
      JWTEncryptionPreferences encryptionPref, IAccessTokenCache accessTokenCache) throws IOException {

    // System.out.println("Inside Method getAppEnterpriseConnection >>>");
    BoxDeveloperEditionAPIConnection api = BoxDeveloperEditionAPIConnection.getAppEnterpriseConnection(
        ENTERPRISE_ID, CLIENT_ID, CLIENT_SECRET, encryptionPref, accessTokenCache);

    return api;

  }

  /****
   * Connect to Box as App User
   *
   * @return
   * @throws IOException
   */
  public BoxDeveloperEditionAPIConnection getAppUserConnection(String privateKey,
      JWTEncryptionPreferences encryptionPref, IAccessTokenCache accessTokenCache) throws IOException {

    // System.out.println("Inside Method getAppUserConnection >>>");

    BoxDeveloperEditionAPIConnection api = BoxDeveloperEditionAPIConnection.getAppUserConnection(USER_ID, CLIENT_ID,
        CLIENT_SECRET, encryptionPref, accessTokenCache);

    return api;

  }

  /****
   * Create App User with Platform Access only
   */
  public String createAppUser(BoxAPIConnection api) throws IOException {

    // System.out.println("Inside Method createAppUser >>>");
    CreateUserParams params = new CreateUserParams();
    BoxUser.Info user = BoxUser.createAppUser(api, APP_USER_NAME, params);
    System.out.println(user.getIsPlatformAccessOnly() + ":Is Platform Access Only");
    return user.getID();

  }

  public BoxAPIConnection getBoxAPIConnection() throws IOException {

    // System.out.println("Inside Method boxAPIConnection >>>");
    String privateKey = new String(Files.readAllBytes(Paths.get(PRIVATE_KEY_FILE)));

    JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
    encryptionPref.setPublicKeyID(PUBLIC_KEY_ID);
    encryptionPref.setPrivateKey(privateKey);
    encryptionPref.setPrivateKeyPassword(PRIVATE_KEY_PASSWORD);
    encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

    // It is a best practice to use an access token cache to prevent unneeded
    // requests to Box for access tokens.
    // For production applications it is recommended to use a distributed cache
    // like Memcached or Redis, and to
    // implement IAccessTokenCache to store and retrieve access tokens
    // appropriately for your environment.
    IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(MAX_CACHE_ENTRIES);

    BoxAPIConnection api = getAppEnterpriseConnection(privateKey, encryptionPref, accessTokenCache);
    String appUserID = getAppUserID(api);
    if (appUserID.equalsIgnoreCase("0")) {
      appUserID = createAppUser(api);
    }
    this.USER_ID = appUserID;
    api = getAppUserConnection(privateKey, encryptionPref, accessTokenCache);
    return api;
  }

  public String getAppUserID(BoxAPIConnection api) {
    // System.out.println("Inside Method getAppUserID >>>");
    Iterable<Info> user = BoxUser.getAllEnterpriseUsers(api);
    for (Info info : user) {
      if (APP_USER_NAME.equalsIgnoreCase(info.getName())) {
        System.out.println(info.getIsPlatformAccessOnly() + "???");
        return info.getID();
      }
    }
    // System.out.println("No User Found >>>");
    return "0";
  }

}
