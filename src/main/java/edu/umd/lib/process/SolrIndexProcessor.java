package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.json.JSONObject;

public class SolrIndexProcessor implements Processor
{
	
	/**
     *  Format a message so that a record can be indexed in Solr.
     *
     *  The output format should be:
     *
     *  [
     *		{
     * 			"id": "12",
     * 			"name": "test_1",
     * 			"type": "Text"
     *		},
     *		{
     * 			 "id": "13",
     *  		"name": "test_2",
     *  		"type": "Text"
     *		}    
     * ]
     *
     *  @param exchange The incoming message exchange.
     */
    public void process(Exchange exchange) throws Exception
    {
    	String message = exchange.getIn().getBody(String.class);
    	JSONObject json = new JSONObject(message);
    	System.out.println("JSON-FILE"+json);
    	exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getIn().setHeader(Exchange.HTTP_METHOD, "POST");
    	exchange.getIn().setBody(json.toString());
    }
}
