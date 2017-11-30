package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.log4j.Logger;

public abstract class AbstractSolrProcessor implements Processor {
  private static Logger log = Logger.getLogger(AbstractSolrProcessor.class);

  abstract String generateMessage(Exchange exchange) throws Exception;

  @Override
  public void process(Exchange exchange) throws Exception {
    String messageBody = generateMessage(exchange);
    log.info("Json msg: " + messageBody);
    exchange.getIn().setBody(messageBody);
  }

}
