package edu.umd.lib.routes;

import java.io.File;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http4.HttpMethods;

import edu.umd.lib.process.*;


public class SolrRouter extends RouteBuilder  {
	
	
	@Override
	public void configure() throws Exception {
		
		
		
        from("file:data/files?noop=true")
	        .routeId("SolrFileProcessor")
	        .process(new SolrFileProcessor())
	        .log(body().toString())
	        .to("file:data/solr?fileName=document-sample.json&fileExist=Append");
		
		/**
         * Indexing file from JSON
         */
		from("file:data/solr?noop=true")
			.routeId("IndexingFiles")
			.process(new SolrIndexProcessor())
	        .to("direct:update.solr");	
		
		 /**
         * Deleting Index of a file using the file ID
         *//*
		from("file:data/Solr-delete?noop=true")
			.routeId("DeleteFiles")
	        .to("direct:delete.solr");	
		
		 *//**
         * Perform the solr update.
         *//*
        from("direct:update.solr")
            .routeId("SolrUpdater")
            .log(LoggingLevel.INFO, "Indexing Solr Object")
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))     	    
            .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
            .to("http4://{{solr.baseUrl}}/update")
            .to("log:DEBUG?showBody=true&showHeaders=true");
        
        *//**
         * Remove an item from the solr index.
         *//*
        from("direct:delete.solr")
	        .routeId("FcrepoSolrDeleter")
	        .process(new SolrDeleteProcessor())
	        .log(LoggingLevel.INFO,"Deleting Solr Object")
	        .setHeader(Exchange.HTTP_QUERY).simple("commitWithin={{solr.commitWithin}}")
	        .to("http4://{{solr.baseUrl}}/update")
	        .to("log:DEBUG?showBody=true&showHeaders=true");;
		*/
	}

}
