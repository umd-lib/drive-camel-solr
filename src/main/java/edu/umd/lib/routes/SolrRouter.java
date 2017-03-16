package edu.umd.lib.routes;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;

import com.box.sdk.BoxEvent;

import edu.umd.lib.process.BoxDeleteProcessor;
import edu.umd.lib.process.BoxPollEventProcessor;
import edu.umd.lib.process.BoxUpdateProcessor;
import edu.umd.lib.process.ExceptionProcessor;


/**
 * SolrRouter Contains all Route Configuration for Box and Solr Integration
 * <p>
 *
 * @since 1.0
 */
public class SolrRouter extends RouteBuilder {

  private String clientID = "";
  private String clientSecret = "";
  private String enterpriseID = "";
  private String publicKeyID = "";
  private String privateKeyFile = "";
  private String privateKeyPassword = "";
  private String appUserName = "";
  private String maxCacheTries = "";
  private String propertiesName = "";
  private String pollInterval = "";
  private String syncFolder = "";
  private String solrBaseUrl = "";

  Map<String, String> config = new HashMap<String, String>();

  public final String emailSubject = "Exception Occured in Box-Solr Integration, "
      + "Total Number of Attempts: {{camel.maximum_tries}} retries.";

  Predicate uploaded = header("event_type").isEqualTo(BoxEvent.Type.ITEM_UPLOAD.toString());
  Predicate copied = header("event_type").isEqualTo(BoxEvent.Type.ITEM_COPY.toString());
  Predicate moved = header("event_type").isEqualTo(BoxEvent.Type.ITEM_MOVE.toString());
  Predicate deleted = header("event_type").isEqualTo(BoxEvent.Type.ITEM_TRASH.toString());
  Predicate undelete = header("event_type").isEqualTo(BoxEvent.Type.ITEM_UNDELETE_VIA_TRASH.toString());
  Predicate created = header("event_type").isEqualTo(BoxEvent.Type.ITEM_CREATE.toString());
  Predicate file = header("item_type").isEqualTo("file");

  @Override
  public void configure() throws Exception {

    config.put("clientID", clientID);
    config.put("clientSecret", clientSecret);
    config.put("enterpriseID", enterpriseID);
    config.put("publicKeyID", publicKeyID);
    config.put("privateKeyFile", privateKeyFile);
    config.put("privateKeyPassword", privateKeyPassword);
    config.put("appUserName", appUserName);
    config.put("maxCacheTries", maxCacheTries);
    config.put("propertiesName", propertiesName);
    config.put("syncFolder", syncFolder);
    config.put("solrBaseUrl", solrBaseUrl);

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
    .to("direct:default.pollBox");

    /**
     * Parse Request from Box Web hooks. Each Parameter from web hook is set
     * into camel exchange header to make it available for camel routes
     */
    from("direct:default.pollBox")
    .routeId("BoxPollRouter")
    .log("Polling Box for events")
    .process(new BoxPollEventProcessor(config));

    /**
     * Route Based on Event Types Event Types handled :uploaded,
     * copied,moved,deleted
     */
    from("direct:route.events")
    .routeId("CamelBoxRouter")
    .choice()
    .when(PredicateBuilder.and(uploaded, file))
    .to("direct:uploaded.box")
    .when(PredicateBuilder.and(copied, file))
    .to("direct:uploaded.box")
    .when(PredicateBuilder.and(moved, file))
    .to("direct:uploaded.box")
    .when(PredicateBuilder.and(uploaded, file))
    .to("direct:uploaded.box")
    .when(PredicateBuilder.and(undelete, file))
    .to("direct:uploaded.box")
    .when(PredicateBuilder.and(created, file))
    .to("direct:uploaded.box")
    .when(PredicateBuilder.and(deleted, file))
    .to("direct:deleted.box")
    .otherwise()
    .to("direct:default.box");

    /**
     * Handle Box Events and Parameters and prepare JSON request for Solr
     * update.
     */
    from("direct:uploaded.box")
    .routeId("BoxUpdateProcessor")
    .log("Creating JSON for Solr Update Route with file information.")
    .process(new BoxUpdateProcessor(config))
    .to("direct:update.solr");

    /**
     * Handle Box Events and Parameters and prepare JSON request for Solr
     * Delete.
     */
    from("direct:deleted.box")
    .routeId("BoxDeleteProcessor")
    .log("Creating JSON for Solr Delete Route.")
    .process(new BoxDeleteProcessor(config))
    .to("direct:delete.solr");

    /**
     * Connect to Solr and update the Box information
     */
    from("direct:update.solr")
    .routeId("SolrUpdater")
    .log(LoggingLevel.INFO, "Indexing Box Document in Solr")
    .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
    .setHeader(Exchange.HTTP_METHOD).simple("POST")
    .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
    .to("http4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /**
     * Connect to Solr and delete the Box information
     */
    from("direct:delete.solr")
    .routeId("SolrDeleter")
    .log(LoggingLevel.INFO, "Deleting Solr Object")
    .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
    .setHeader(Exchange.HTTP_METHOD).simple("POST")
    .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
    .to("http4://{{solr.baseUrl}}/update?bridgeEndpoint=true");

    /***
     * Default Box Route
     */
    from("direct:default.box")
    .routeId("DefaultboxListener")
    .log(LoggingLevel.INFO, "Default Action Listener for Listener");

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
   * @return the clienID
   */
  public String getClientID() {
    return clientID;
  }

  /**
   * @param clienID
   *          the clienID to set
   */
  public void setClientID(String clientID) {
    this.clientID = clientID;
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
   * @return the enterpriseID
   */
  public String getEnterpriseID() {
    return enterpriseID;
  }

  /**
   * @param enterpriseID
   *          the enterpriseID to set
   */
  public void setEnterpriseID(String enterpriseID) {
    this.enterpriseID = enterpriseID;
  }

  /**
   * @return the publicKeyID
   */
  public String getPublicKeyID() {
    return publicKeyID;
  }

  /**
   * @param publicKeyID
   *          the publicKeyID to set
   */
  public void setPublicKeyID(String publicKeyID) {
    this.publicKeyID = publicKeyID;
  }

  /**
   * @return the privateKeyFile
   */
  public String getPrivateKeyFile() {
    return privateKeyFile;
  }

  /**
   * @param privateKeyFile
   *          the privateKeyFile to set
   */
  public void setPrivateKeyFile(String privateKeyFile) {
    this.privateKeyFile = privateKeyFile;
  }

  /**
   * @return the privateKeyPassword
   */
  public String getPrivateKeyPassword() {
    return privateKeyPassword;
  }

  /**
   * @param privateKeyPassword
   *          the privateKeyPassword to set
   */
  public void setPrivateKeyPassword(String privateKeyPassword) {
    this.privateKeyPassword = privateKeyPassword;
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
  public String getPropertiesName() {
    return propertiesName;
  }

  /**
   * @param propertiesName
   *          the propertiesName to set
   */
  public void setPropertiesName(String propertiesName) {
    this.propertiesName = propertiesName;
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

  public String getSyncFolder() {
    return syncFolder;
  }

  public void setSyncFolder(String boxTempStore) {
    this.syncFolder = boxTempStore;
  }

  public String getSolrBaseUrl() {
    return solrBaseUrl;
  }

  public void setSolrBaseUrl(String solrBaseUrl) {
    this.solrBaseUrl = solrBaseUrl;
  }

}
