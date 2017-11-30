package edu.umd.lib.routes;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;

import edu.umd.lib.process.DriveDeleteProcessor;
import edu.umd.lib.process.DriveDirRenameProcessor;
import edu.umd.lib.process.DriveFileMoveProcessor;
import edu.umd.lib.process.DriveFileRenameProcessor;
import edu.umd.lib.process.DriveMoveDirProcessor;
import edu.umd.lib.process.DriveNewFileProcessor;
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
  private String pollInterval;
  private String allowedFileSize;

  private String solrBaseUrl;
  private String driveAcronymProperties;

  Map<String, String> config = new HashMap<String, String>();

  public final String emailSubject = "Exception Occured in Drive-Solr Integration, "
      + "Total Number of Attempts: {{camel.maximum_tries}} retries.";

  Predicate delete = header("action").isEqualTo("delete_file");
  Predicate newfile = header("action").isEqualTo("new_file");
  Predicate renamedir = header("action").isEqualTo("rename_dir");
  Predicate renamefile = header("action").isEqualTo("rename_file");
  Predicate update = header("action").isEqualTo("update_file");
  Predicate update_paths = header("action").isEqualTo("update_paths");
  Predicate movefile = header("action").isEqualTo("move_file");
  Predicate moveDir = header("action").isEqualTo("move_dir");

  @Override
  public void configure() throws Exception {

    config.put("clientSecretFile", clientSecret);
    config.put("appName", appUserName);
    config.put("maxCacheTries", maxCacheTries);
    config.put("tokenProperties", tokenProperties);
    config.put("driveAcronymProperties", driveAcronymProperties);
    config.put("solrBaseUrl", solrBaseUrl);
    config.put("allowedFileSize", allowedFileSize);

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
        .when(newfile)
        .to("direct:newfile.filesys")
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
        .when(movefile)
        .to("direct:movefile.filesys")
        .when(moveDir)
        .to("direct:movedir.filesys")
        .otherwise()
        .to("direct:default");

    /**
     * FileDownloader: receives exchanges with info about a file to download &
     * handles by downloading the file to the local system
     */
    from("direct:newfile.filesys")
        .routeId("NewFile")
        .log("Request received to add a new file")
        .process(new DriveNewFileProcessor(config))
        .to("direct:update.solr");

    /**
     * FileDeleter: receives message with info about a file/dir to delete &
     * handles by sending message to SolrDeleter
     */
    from("direct:delete.filesys")
        .routeId("FileDeleter")
        .log("Deleting file from Solr")
        .process(new DriveDeleteProcessor(config))
        .to("direct:delete.solr");

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
     * FileMover: receives exchanges with info about a file to move & handles by
     * moving the file to the destination path
     */
    from("direct:movefile.filesys")
        .routeId("FileMover")
        .log("Moving a file on local file system and in Solr")
        .process(new DriveFileMoveProcessor(config))
        .to("direct:update.solr");

    /**
     * DirMover: receives exchanges with info about a directory to move &
     * handles by moving the directory along with the files in it to the
     * destination path
     */
    from("direct:movedir.filesys")
        .routeId("DirectoryMover")
        .log("Moving a directory and its contents on local file system")
        .process(new DriveMoveDirProcessor(config))
        .to("direct:update.solr");

    /**
     * FilePathsUpdater: Receives exchanges about path updates after a directory
     * has been renamed
     */
    from("direct:update_paths")
        .routeId("FilePathsUpdater")
        .log("Updating the paths in the properties file and Solr for all the files within a renamed directory")
        .process(new DrivePathUpdateProcessor(config))
        .to("direct:update.solr");

    /**
     * FileUpdater: receives exchanges with info about a file to be updates and
     * handles by updating the file on the local system
     */
    from("direct:update.filesys")
        .routeId("FileUpdater")
        .log("Request received to update a file")
        .process(new DriveNewFileProcessor(config))
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
        .to("http4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /**
     * Connect to Solr and delete the Drive information
     */
    from("direct:delete.solr")
        .routeId("SolrDeleter")
        .log(LoggingLevel.INFO, "Deleting Solr Object")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_METHOD).simple("POST")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("http4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

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
   *
   * @return the acronym properties file
   */
  public String getDriveAcronymProperties() {
    return driveAcronymProperties;
  }

  /**
   *
   * @param driveAcronymProperties
   *          the drive acronym properties file to set
   */
  public void setDriveAcronymProperties(String driveAcronymProperties) {
    this.driveAcronymProperties = driveAcronymProperties;
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
   * @return the file size
   */
  public String getAllowedFileSize() {
    return allowedFileSize;
  }

  /**
   *
   * @param allowedFileSize
   */
  public void setAllowedFileSize(String allowedFileSize) {
    this.allowedFileSize = allowedFileSize;
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

}
