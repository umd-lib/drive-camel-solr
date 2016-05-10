package edu.umd.lib.routes;


import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

import edu.umd.lib.process.*;


public class SolrRouter extends RouteBuilder  {
	
	
	@Override
	public void configure() throws Exception {
		
		/**
         * Read from File Path
         */
        from("file:data/files?noop=true")
	        .routeId("SolrFileProcessor")
	        .process(new SolrFileProcessor())
	        .log(body().toString())
	        .log(LoggingLevel.INFO, "Reading Files from Path")
	        .to("direct:index_file.config");
		
		/**
         * Indexing file from File Content
         */
		from("direct:index_file.config")
			.routeId("IndexingFiles")
			.setHeader(Exchange.CONTENT_TYPE, constant("application/json"))  
	        .setHeader(Exchange.HTTP_METHOD, constant("POST"))
	        .to("direct:update.solr");	
		
		 /**
         * Deleting Index of a file using the file ID
         */
		from("file:data/Solr-delete?noop=true")
			.routeId("DeleteFiles")
	        .to("direct:delete.solr");	
		
		/**
         * Perform the Solr update.
         */
        from("direct:update.solr")
            .routeId("SolrUpdater")
            .log(LoggingLevel.INFO, "Indexing Solr Object")
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))     	    
            .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
            .to("http4://{{solr.baseUrl}}/update")
            .to("log:DEBUG?showBody=true&showHeaders=true");
        
        /**
         * Remove an item from the Solr index.
         */
        from("direct:delete.solr")
	        .routeId("FcrepoSolrDeleter")
	        .process(new SolrDeleteProcessor())
	        .log(LoggingLevel.INFO,"Deleting Solr Object")
	        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
	        .to("http4://{{solr.baseUrl}}/update")
	        .to("log:DEBUG?showBody=true&showHeaders=true");;
		
	}

}
