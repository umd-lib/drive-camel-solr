package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.json.JSONObject;

public class SolrDeleteProcessor implements Processor{
	    
	
	/**
	     *  Format a message so that a record can be deleted in Solr.
	     *
	     *  The output format should be:
	     *
	     *  {
	     *    "delete" : {
	     *      "id" : "/foo"
	     *    }
	     *  }
	     *
	     *  @param exchange The incoming message exchange.
	     */
	   public void process(final Exchange exchange) throws Exception {

	    	String message = exchange.getIn().getBody(String.class);
	        JSONObject json = new JSONObject(message);
	        exchange.getIn().setBody(json.toString());
	        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
	        exchange.getIn().setHeader(Exchange.HTTP_METHOD, "POST");
	    }

		
	}
