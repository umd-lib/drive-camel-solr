package edu.umd.lib.process;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/****
 * Process the file to add to Solr
 *
 * @author rameshb
 *
 */
public class BoxWebHookProcessor implements Processor {

  public void process(Exchange exchange) throws Exception {

    String message = exchange.getIn().getBody(String.class);
    Map<String, String> parameters = getQueryParams(message);

    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      String value = entry.getValue();
      exchange.getIn().setHeader(entry.getKey(), value);
    }

    printingMap(parameters);
  }

  /***
   * From response parse the string to form hash map of parameters
   *
   * @param parameters
   *          from the WuFoo Request @return Hash map with parameter name as key
   *          and field value as value @exception
   */
  /***
   * From response parse the string to form hash map of parameters
   *
   * @param parameters
   *          from the WuFoo Request @return Hash map with parameter name as key
   *          and field value as value @exception
   */
  public Map<String, String> getQueryParams(String queryString) {

    try {

      Map<String, String> params = new HashMap<String, String>();

      for (String param : queryString.split("&")) {
        String[] pair = param.split("=");
        String key = URLDecoder.decode(pair[0], "UTF-8");
        String value = "";
        if (pair.length > 1) {
          value = URLDecoder.decode(pair[1], "UTF-8");
        }
        params.put(key, value);
      }
      // printingMap(params);
      return params;

    } catch (UnsupportedEncodingException ex) {
      throw new AssertionError(ex);
    }
  }

  /***
   * Utility function to print maps, Loop through each key set and value and
   * print the contain. If the value is a collection again the collection is
   * converted into a single string and the key value pair is printed
   *
   * @param map
   */
  public void printingMap(Map<String, ?> parameters) {

    for (Map.Entry<String, ?> entry : parameters.entrySet()) {
      if (entry.getValue() instanceof List<?>) {

        List<?> list = (List<?>) entry.getValue();
        String value = "";
        for (int i = 0; i < list.size(); i++) {
          value = value + list.get(i);
        }
        System.out.println(entry.getKey() + ":" + value);
      } else {
        System.out.println(entry.getKey() + ":" + entry.getValue());
      }

    }
  }

}