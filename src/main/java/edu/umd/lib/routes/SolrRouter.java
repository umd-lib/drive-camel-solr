package edu.umd.lib.routes;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;

import edu.umd.lib.process.DriveDeleteProcessor;
import edu.umd.lib.process.DriveDownloadProcessor;
import edu.umd.lib.process.DriveMakedirProcessor;
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
  private String propertiesFile;
  private String pollInterval;
  private String solrBaseUrl;
  private String localStorage;

  Map<String, String> config = new HashMap<String, String>();

  public final String emailSubject = "Exception Occured in Drive-Solr Integration, "
      + "Total Number of Attempts: {{camel.maximum_tries}} retries.";

  Predicate delete = header("action").isEqualTo("delete");
  Predicate download = header("action").isEqualTo("download");
  Predicate makedir = header("action").isEqualTo("make_directory");

  @Override
  public void configure() throws Exception {

    config.put("clientSecretFile", clientSecret);
    config.put("appName", appUserName);
    config.put("maxCacheTries", maxCacheTries);
    config.put("propertiesFile", propertiesFile);
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
     * Parse Request from Box Web hooks. Each Parameter from web hook is set
     * into camel exchange header to make it available for camel routes
     */
    from("direct:default.pollDrive")
        .routeId("DrivePollRouter")
        .log("Polling Drive for events")
        .process(new DrivePollEventProcessor(config));

    /**
     * ActionListener: receives exchanges resulting from polling cloud account
     * changes & redirects them based on the required action specified in
     * 'action' header
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
        .otherwise()
        .to("direct:default");

    /**
     * FileDownloader: receives exchanges with info about a file to download &
     * its associated cloud account & processes with appropriate
     * CloudDownloadProcessor determined by 'account_type' header
     */
    from("direct:download.filesys")
        .routeId("FileDownloader")
        .log("Request received to download a file from the Drive.")
        .process(new DriveDownloadProcessor(config))
        .to("direct:update.solr");

    /**
     * FileDeleter: receives message with info about a file to delete & handles
     * by deleting file on local system & sending message to SolrDeleter
     */
    from("direct:delete.filesys")
        .routeId("FileDeleter")
        .log("Deleting file")
        .process(new DriveDeleteProcessor());
    // .to("direct:delete.solr");

    /**
     * DirectoryMaker: receives a message with info about a folder to make &
     * handles by making that directory on the local file system.
     */
    from("direct:makedir.filesys")
        .routeId("DirectoryMaker")
        .log("Creating a directory on local file system")
        .process(new DriveMakedirProcessor());
    // .to("direct:update.solr");

    /**
     *
     *
     * Connect to Solr and update the Box information
     */
    from("direct:update.solr")
        .routeId("SolrUpdater")
        .log(LoggingLevel.INFO, "Indexing Drive Document in Solr")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_METHOD).simple("POST")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("http4://{{solr.baseUrl}}/update?bridgeEndpoint=true");
    // .to("https4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /**
     * Connect to Solr and delete the Box information
     */
    from("direct:delete.solr")
        .routeId("SolrDeleter")
        .log(LoggingLevel.INFO, "Deleting Solr Object")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_METHOD).simple("POST")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}");
    // .to("https4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /***
     * Default Box Route
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
   * @return the propertiesName
   */
  public String getPropertiesFile() {
    return propertiesFile;
  }

  /**
   * @param propertiesName
   *          the propertiesName to set
   */
  public void setPropertiesFile(String propertiesFile) {
    this.propertiesFile = propertiesFile;
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

  public String getSolrBaseUrl() {
    return solrBaseUrl;
  }

  public void setSolrBaseUrl(String solrBaseUrl) {
    this.solrBaseUrl = solrBaseUrl;
  }

  public String getLocalStorage() {
    return localStorage;
  }

  public void setLocalStorage(String localStorage) {
    this.localStorage = localStorage;
  }

}
