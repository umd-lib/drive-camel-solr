package edu.umd.lib.routes;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;

import edu.umd.lib.process.DriveDeleteProcessor;
import edu.umd.lib.process.DriveDirRenameProcessor;
import edu.umd.lib.process.DriveDownloadProcessor;
import edu.umd.lib.process.DriveFileRenameProcessor;
import edu.umd.lib.process.DriveMakedirProcessor;
import edu.umd.lib.process.DrivePathUpdateProcessor;
import edu.umd.lib.process.DrivePollEventProcessor;
import edu.umd.lib.process.ExceptionProcessor;

/**
 * SolrRouter Contains all Route Configuration for Drive and Solr Integration
 * <p>
 *
 * @since 1.0
 */
public class SolrRouter extends RouteBuilder {

  private String clientSecret;
  private String appUserName;
  private String maxCacheTries;
  private String tokenProperties;
  private String fileAttributeProperties;
  private String pollInterval;
  private String solrBaseUrl;
  private String localStorage;
  private String fileAcronymProperties;

  Map<String, String> config = new HashMap<String, String>();

  public final String emailSubject = "Exception Occured in Drive-Solr Integration, "
      + "Total Number of Attempts: {{camel.maximum_tries}} retries.";

  Predicate delete = header("action").isEqualTo("delete");
  Predicate download = header("action").isEqualTo("download");
  Predicate makedir = header("action").isEqualTo("make_directory");
  Predicate renamedir = header("action").isEqualTo("rename_dir");
  Predicate renamefile = header("action").isEqualTo("rename_file");
  Predicate update = header("action").isEqualTo("update");
  Predicate update_paths = header("action").isEqualTo("update_paths");

  @Override
  public void configure() throws Exception {

    config.put("clientSecretFile", clientSecret);
    config.put("appName", appUserName);
    config.put("maxCacheTries", maxCacheTries);
    config.put("tokenProperties", tokenProperties);
    config.put("fileAttributeProperties", fileAttributeProperties);
    config.put("fileAcronymProperties", fileAcronymProperties);
    config.put("solrBaseUrl", solrBaseUrl);
    config.put("localStorage", localStorage);

    /**
     * A generic error handler (specific to this RouteBuilder)
     */
    onException(Exception.class)
        .routeId("ExceptionRoute")
        .process(new ExceptionProcessor())
        .handled(true)
        .maximumRedeliveries("{{camel.maximum_tries}}")
        .redeliveryDelay("{{camel.redelivery_delay}}")
        .backOffMultiplier("{{camel.backoff_multiplier}}")
        .useExponentialBackOff()
        .maximumRedeliveryDelay("{{camel.maximum_redelivery_delay}}")
        .to("direct:send_error_email");

    from("timer://runOnce?repeatCount=0&delay=5000&period=" + pollInterval)
        .to("direct:default.pollDrive");

    /**
     * Parse Request from Drive Web hooks. Each Parameter from web hook is set
     * into camel exchange header to make it available for camel routes
     */
    from("direct:default.pollDrive")
        .routeId("DrivePollRouter")
        .log("Polling Drive for events")
        .process(new DrivePollEventProcessor(config));

    /**
     * ActionListener: receives exchanges resulting from polling Drive changes &
     * redirects them based on the required action specified in 'action' header
     */
    from("direct:actions").streamCaching()
        .routeId("ActionListener")
        .log("Received an event from Drive polling.")
        .choice()
        .when(download)
        .to("direct:download.filesys")
        .when(makedir)
        .to("direct:makedir.filesys")
        .when(delete)
        .to("direct:delete.filesys")
        .when(renamedir)
        .to("direct:renamedir.filesys")
        .when(renamefile)
        .to("direct:renamefile.filesys")
        .when(update_paths)
        .to("direct:update_paths")
        .when(update)
        .to("direct:update.filesys")
        .otherwise()
        .to("direct:default");

    /**
     * FileDownloader: receives exchanges with info about a file to download &
     * handles by downloading the file to the local system
     */
    from("direct:download.filesys")
        .routeId("FileDownloader")
        .log("Request received to download a file from the Drive.")
        .process(new DriveDownloadProcessor(config))
        .to("direct:update.solr");

    /**
     * FileDeleter: receives message with info about a file to delete & handles
     * by sending message to SolrDeleter
     */
    from("direct:delete.filesys")
        .routeId("FileDeleter")
        .log("Deleting file")
        .process(new DriveDeleteProcessor())
        .to("direct:delete.solr");

    /**
     * DirectoryMaker: receives a message with info about a folder to create &
     * handles by creating that directory on the local file system.
     */
    from("direct:makedir.filesys")
        .routeId("DirectoryMaker")
        .log("Creating a directory on local file system")
        .process(new DriveMakedirProcessor(config));

    /**
     * FileRenamer: receives exchanges with info about a directory to rename &
     * handles by renaming the directory
     */
    from("direct:renamedir.filesys")
        .routeId("DirectoryRenamer")
        .log("Renaming a directory on local file system")
        .process(new DriveDirRenameProcessor(config));

    /**
     * FileRenamer: receives exchanges with info about a file to rename &
     * handles by renaming the file
     */
    from("direct:renamefile.filesys")
        .routeId("FileRenamer")
        .log("Renaming a file on local file system and in Solr")
        .process(new DriveFileRenameProcessor(config))
        .to("direct:update.solr");

    /**
     * FilePathsUpdater: Receives exchanges about path updates after a directory
     * has been renamed
     */
    from("direct:update_paths")
        .routeId("FilePathsUpdater")
        .log("Updating the paths in the properties file and Solr for all the files within a renamed directory")
        .process(new DrivePathUpdateProcessor())
        .to("direct:update.solr");

    /**
     * FileUpdater: receives exchanges with info about a file to be updates and
     * handles by updating the file on the local system
     */
    from("direct:update.filesys")
        .routeId("FileUpdater")
        .log("Request received to update a file")
        .process(new DriveDownloadProcessor(config))
        .to("direct:update.solr");

    /**
     *
     *
     * Connect to Solr and update the Drive information
     */
    from("direct:update.solr")
        .routeId("SolrUpdater")
        .log(LoggingLevel.INFO, "Indexing Drive Document in Solr")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_METHOD).simple("POST")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("https4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /**
     * Connect to Solr and delete the Drive information
     */
    from("direct:delete.solr")
        .routeId("SolrDeleter")
        .log(LoggingLevel.INFO, "Deleting Solr Object")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_METHOD).simple("POST")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("https4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /***
     * Default Drive Route
     */
    from("direct:default.drive")
        .routeId("DefaultDriveListener")
        .log(LoggingLevel.INFO, "Default Action Listener for Drive");

    /****
     * Send Email with error message to email address from Configuration file
     */
    from("direct:send_error_email").doTry().routeId("SendErrorEmail")
        .log("processing a email to be sent using SendErrorEmail Route.")
        .setHeader("subject", simple(emailSubject))
        .setHeader("From", simple("{{email.from}}"))
        .setHeader("To", simple("{{email.to}}"))
        .to("{{email.uri}}").doCatch(Exception.class)
        .log("Error Occurred While Sending Email to specified to address.")
        .end();

  }

  /**
   * @return the clientSecret
   */
  public String getClientSecret() {
    return clientSecret;
  }

  /**
   * @param clientSecret
   *          the clientSecret to set
   */
  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  /**
   * @return the appUserName
   */
  public String getAppUserName() {
    return appUserName;
  }

  /**
   * @param appUserName
   *          the appUserName to set
   */
  public void setAppUserName(String appUserName) {
    this.appUserName = appUserName;
  }

  /**
   * @return the maxCacheTries
   */
  public String getMaxCacheTries() {
    return maxCacheTries;
  }

  /**
   * @param maxCacheTries
   *          the maxCacheTries to set
   */
  public void setMaxCacheTries(String maxCacheTries) {
    this.maxCacheTries = maxCacheTries;
  }

  /**
   * @return the tokenProperties
   */
  public String getTokenProperties() {
    return tokenProperties;
  }

  /**
   * @param tokenProperties
   *          the tokenProperties to set
   */
  public void setTokenProperties(String tokenProperties) {
    this.tokenProperties = tokenProperties;
  }

  /**
   * @return the fileAttributeProperties
   */
  public String getFileAttributeProperties() {
    return fileAttributeProperties;
  }

  /**
   * @param fileAttributeProperties
   *          the fileAttributeProperties to set
   */
  public void setFileAttributeProperties(String fileAttributeProperties) {
    this.fileAttributeProperties = fileAttributeProperties;
  }

  /**
   *
   * @return the acronym properties file
   */
  public String getFileAcronymProperties() {
    return fileAcronymProperties;
  }

  /**
   *
   * @param fileAcronymProperties
   *          the acronym properties file to set
   */
  public void setFileAcronymProperties(String fileAcronymProperties) {
    this.fileAcronymProperties = fileAcronymProperties;
  }

  /**
   * @return the pollInterval
   */
  public String getPollInterval() {
    return pollInterval;
  }

  /**
   * @param pollInterval
   *          the pollInterval to set
   */
  public void setPollInterval(String pollInterval) {
    this.pollInterval = pollInterval;
  }

  /**
   *
   * @return the solrBaseUrl
   */
  public String getSolrBaseUrl() {
    return solrBaseUrl;
  }

  /**
   *
   * @param solrBaseUrl
   */
  public void setSolrBaseUrl(String solrBaseUrl) {
    this.solrBaseUrl = solrBaseUrl;
  }

  /**
   *
   * @return the localStorage
   */
  public String getLocalStorage() {
    return localStorage;
  }

  /**
   *
   * @param localStorage
   */
  public void setLocalStorage(String localStorage) {
    this.localStorage = localStorage;
  }

}
