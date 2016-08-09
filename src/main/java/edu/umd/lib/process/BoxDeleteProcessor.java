package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/****
 * Process the file to add to Solr
 *
 * @author rameshb
 *
 */
public class BoxDeleteProcessor implements Processor {

  public void process(Exchange exchange) throws Exception {

    String file_ID = exchange.getIn().getHeader("item_id", String.class);
    String delete_json = "{ \"delete\" : { \"id\" : \"" + file_ID + "\" } }";
    exchange.getIn().setBody(delete_json);
    System.out.println("Delete Json:" + delete_json);
  }

}
