package edu.umd.lib;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.component.solr.SolrConstants;
import org.apache.camel.impl.DefaultCamelContext;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.apache.camel.component.gson.GsonDataFormat;


public class App
{
    public static void main( String[] args ) throws Exception
    {
        CamelContext context = new DefaultCamelContext();

        PropertiesComponent propertiesComponent = context.getComponent("properties", PropertiesComponent.class);
        propertiesComponent.setLocation("classpath:configuration.properties");
        propertiesComponent.setSystemPropertiesMode(PropertiesComponent.SYSTEM_PROPERTIES_MODE_OVERRIDE);

       
        JSONObject json = new JSONObject();
        JSONArray array = new JSONArray();
        json.put("id", "1");
        json.put("name", "filename_1");
        json.put("type", "fileType-test");
        array.put(json);
        final String js = array.toString();
        System.out.println("FIle"+js);
        
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception
            {
               
                from("file:data/solr?noop=true")
                		.setBody().simple(js)
                		.setHeader(Exchange.CONTENT_TYPE, constant("application/json")) 
                	    .setHeader(Exchange.HTTP_CHARACTER_ENCODING, constant("UTF-8")) 
                        .setHeader(SolrConstants.OPERATION, constant(SolrConstants.OPERATION_ADD_BEAN))
                        .to("http4://{{solr.host}}:{{solr.port}}/solr/box/update")
                        .setHeader(SolrConstants.OPERATION, constant(SolrConstants.OPERATION_COMMIT))
                        .to("log:DEBUG?showBody=true&showHeaders=true");
                
            }
        });
        context.start();
        Thread.sleep(1000 * 60 * 15); // 15 min
        context.stop();
    }
}
