package edu.umd.lib.process;

import java.io.File;
import java.util.Random;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.json.JSONObject;

public class SolrFileProcessor implements Processor{
	Random rand = new Random();
	public void process(Exchange exchange) throws Exception {
		

		int randomID = rand.nextInt(200) + 1;
		File file = exchange.getIn().getBody(File.class);
        JSONObject json = new JSONObject();
        json.put("id", randomID);
        json.put("name", file.getName());        
        json.put("type", "TextDocument");
        exchange.getIn().setBody("["+json.toString()+"]");        
	}
        
	
}
