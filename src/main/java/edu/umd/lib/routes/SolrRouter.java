package edu.umd.lib.routes;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

import edu.umd.lib.process.BoxDeleteProcessor;
import edu.umd.lib.process.BoxUploadProcessor;
import edu.umd.lib.process.BoxWebHookProcessor;
import edu.umd.lib.process.SolrDeleteProcessor;

/**
 * SolrRouter Contains all Route Configuration for Drive/Box and Solr
 * Integration
 * <p>
 *
 * @since 1.0
 */
public class SolrRouter extends RouteBuilder {

  @Override
  public void configure() throws Exception {

    /**
     * A generic error handler (specific to this RouteBuilder)
     */
    onException(Exception.class)
        .maximumRedeliveries("{{error.maxRedeliveries}}")
        .log("Index Routing Error: ${routeId}");

    /**
     * Parse Request from WuFoo Web hooks and create hash map for SysAid Route
     */
    from("jetty:{{default.domain}}{{box.routeName}}/{{box.serviceName}}").streamCaching()
        .routeId("BoxListener")
        .process(new BoxWebHookProcessor())
        .log("Wufoo Process Completed")
        .to("direct:route.events");

    /**
     * Route Based on Event Types
     */
    from("direct:route.events")
        .routeId("Event Router")
        .choice()
        .when(header("event_type").isEqualTo("uploaded"))
        .to("direct:uploaded.box")
        .when(header("event_type").isEqualTo("deleted"))
        .to("direct:deleted.box")
        .otherwise()
        .to("direct:default.box");

    /**
     * Event Listener when File is Uploaded
     */
    from("direct:uploaded.box")
        .routeId("UploadProcessor")
        .process(new BoxUploadProcessor())
        .log("A file is Uploaded")
        .to("direct:update.solr");

    /**
     * Event Listener when File is Deleted
     */
    from("direct:deleted.box")
        .routeId("DeletedProcessor")
        .process(new BoxDeleteProcessor())
        .log("A file is Deleted")
        .to("direct:delete.solr");

    /**
     * Read from File Path
     *
     * from("file:data/files?noop=true") .routeId("SolrFileProcessor")
     * .process(new SolrFileProcessor()) .log(body().toString())
     * .log(LoggingLevel.INFO, "Reading Files from Path")
     * .to("direct:index_file.config");
     */

    /**
     * Deleting Index of a file using the file ID
     *
     * from("file:data/Solr-delete?noop=true") .routeId("DeleteFiles")
     * .to("direct:delete.solr");
     */

    /**
     * Perform the Solr update.
     */

    /**
     * Indexing file from File Content
     *
     * from("direct:index_file.config") .routeId("IndexingFiles")
     * .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
     * .setHeader(Exchange.HTTP_METHOD, constant("POST"))
     * .to("direct:update.solr");
     */

    from("direct:update.solr")
        .routeId("SolrUpdater")
        .log(LoggingLevel.INFO, "Indexing Solr Object")
        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("http4://{{solr.baseUrl}}/update?bridgeEndpoint=true")
        .to("log:DEBUG?showBody=true&showHeaders=true");

    /**
     * Remove an item from the Solr index.
     */
    from("direct:delete.solr")
        .routeId("FcrepoSolrDeleter")
        .process(new SolrDeleteProcessor())
        .log(LoggingLevel.INFO, "Deleting Solr Object")
        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
        .to("http4://{{solr.baseUrl}}/update?bridgeEndpoint=true")
        .to("log:DEBUG?showBody=true&showHeaders=true");
    ;

  }

}
