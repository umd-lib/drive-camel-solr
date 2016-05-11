package edu.umd.lib.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.json.JSONObject;
import org.xml.sax.SAXException;

public class SolrFileProcessor implements Processor{
	
	Random rand = new Random();
	Tika tika = new Tika();
	
	public void process(Exchange exchange) throws Exception {
		
		int randomID = rand.nextInt(200) + 1;
		File file = exchange.getIn().getBody(File.class);
        JSONObject json = new JSONObject();
        json.put("id", randomID);//Modify code to Include File ID from Drive
        json.put("name", file.getName());   
        json.put("type", tika.detect(file));
        json.put("url", "https://www.google.com/");//Modify code to Include Url from Drive
        json.put("fileContent", parseToPlainText(file));
        exchange.getIn().setBody("["+json.toString()+"]");        
	}
        
	public String parseToPlainText(File file) throws IOException, SAXException, TikaException {
	    BodyContentHandler handler = new BodyContentHandler();
	 
	    AutoDetectParser parser = new AutoDetectParser();
	    Metadata metadata = new Metadata();
	    try{
	    	InputStream targetStream = new FileInputStream(file.getAbsolutePath());
	    	parser.parse(targetStream, handler, metadata);
	    	return handler.toString();
	    }catch(Exception e){
	    	e.printStackTrace();
	    	 return "Empty String";
	    }
	}
	
}
