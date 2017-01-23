package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.log4j.Logger;

/****
 * Create JSON to delete the file from BOX
 *
 * @author rameshb
 *
 */
public class BoxDeleteProcessor implements Processor {

  private static Logger log = Logger.getLogger(BoxDeleteProcessor.class);

  public void process(Exchange exchange) throws Exception {

    String file_ID = exchange.getIn().getHeader("item_id", String.class);
    String delete_json = "{ \"delete\" : { \"id\" : \"" + file_ID + "\" } }";
    exchange.getIn().setBody(delete_json);
    log.info("Creating JSON for deleting box file with ID:" + file_ID);
  }

}
