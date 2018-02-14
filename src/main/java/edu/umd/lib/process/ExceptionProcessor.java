package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.log4j.Logger;

/**
 * ExceptionProcessor creates the Email body content for notifying Exceptions.
 * Based on the Exception that was caught the Exception Message is created and
 * sent to System admin Email Address
 * <p>
 *
 * @since 1.0
 */
public class ExceptionProcessor implements Processor {

  private static Logger log = Logger.getLogger(ExceptionProcessor.class);

  @Override
  public void process(Exchange exchange) {

    try {

      Exception exception = (Exception) exchange.getProperty(Exchange.EXCEPTION_CAUGHT);
      log.error(exception.getMessage());

      String exceptionClass = exception.getClass().getName();
      StringBuilder email_Content = new StringBuilder();

      email_Content.append("Exception: " + exceptionClass + " \n");
      email_Content.append("Exception Message: " + exception.getMessage() + "\n");
      log.info("Exception:" + exceptionClass);
      exception.printStackTrace();

      String message = exchange.getIn().getBody(String.class);
      message = java.net.URLDecoder.decode(message, "UTF-8");
      email_Content.append("\n\nOriginal Message: " + message + " \n");
      log.info("Email body: \n" + email_Content);

      exchange.getOut().setBody(email_Content.toString()); // Email Content

    } catch (Exception e) {
      log.error("Exception occured while handling Route's Exception." + e);
    }
  }

}
