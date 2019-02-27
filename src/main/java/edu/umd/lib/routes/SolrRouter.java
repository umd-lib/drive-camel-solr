package edu.umd.lib.routes;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;

import edu.umd.lib.process.DriveDeleteProcessor;
import edu.umd.lib.process.DriveFileContentUpdateProcessor;
import edu.umd.lib.process.DriveFileMoveProcessor;
import edu.umd.lib.process.DriveFileRenameProcessor;
import edu.umd.lib.process.DriveNewFileProcessor;
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
  Predicate renamefile = header("action").isEqualTo("rename_file");
  Predicate update = header("action").isEqualTo("update_file");
  Predicate movefile = header("action").isEqualTo("move_file");

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
        .when(renamefile)
        .to("direct:renamefile.filesys")
        .when(update)
        .to("direct:update.filesys")
        .when(movefile)
        .to("direct:movefile.filesys")
        .otherwise()
        .to("direct:default");

    /**
     * NewFileProcessor: receives exchanges with info about adding a new file to
     * Solr
     */
    from("direct:newfile.filesys")
        .routeId("NewFile")
        .log("Request received to add a new file")
        .process(new DriveNewFileProcessor(config))
        .to("direct:update.solr");

    /**
     * FileDeleter: receives exchanges with info about a file to delete from
     * Solr
     */
    from("direct:delete.filesys")
        .routeId("FileDeleter")
        .log("Deleting file from Solr")
        .process(new DriveDeleteProcessor(config))
        .to("direct:delete.solr");

    /**
     * FileRenamer: receives exchanges with info renaming a file in Solr
     */
    from("direct:renamefile.filesys")
        .routeId("FileRenamer")
        .log("Renaming a file in Solr")
        .process(new DriveFileRenameProcessor(config))
        .to("direct:update.solr");

    /**
     * FileMover: receives exchanges with info about moving a file in Solr
     */
    from("direct:movefile.filesys")
        .routeId("FileMover")
        .log("Updating file path in Solr")
        .process(new DriveFileMoveProcessor(config))
        .to("direct:update.solr");

    /**
     * FileUpdater: receives exchanges with info updating the content of a file
     * in Solr
     */
    from("direct:update.filesys")
        .routeId("FileUpdater")
        .log("Request received to update a file")
        .process(new DriveFileContentUpdateProcessor(config))
        .to("direct:update.solr");

    /**
     *
     * Connect to Solr and update the Drive information
     */
    from("direct:update.solr")
        .routeId("SolrUpdater")
        .log(LoggingLevel.INFO, "Indexing Drive Document in Solr")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_METHOD).simple("POST")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("{{solr.scheme}}4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /**
     * Connect to Solr and delete the Drive information
     */
    from("direct:delete.solr")
        .routeId("SolrDeleter")
        .log(LoggingLevel.INFO, "Deleting Solr Object")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_METHOD).simple("POST")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("{{solr.scheme}}4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

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
        .log("Error Occurred While Sending Email to specified address.")
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
