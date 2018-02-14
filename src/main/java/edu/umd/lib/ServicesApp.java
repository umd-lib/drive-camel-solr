package edu.umd.lib;

import org.apache.camel.CamelContext;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.log4j.Logger;

import edu.umd.lib.routes.SolrRouter;

/****
 * Main Service App
 *
 * @author audani
 *
 */
public class ServicesApp {
  private static Logger log = Logger.getLogger(ServicesApp.class);

  public static void main(String[] args) {
    CamelContext context = new DefaultCamelContext();

    PropertiesComponent propertiesComponent = context.getComponent("properties", PropertiesComponent.class);
    propertiesComponent.setLocation("classpath:edu.umd.lib.drivesolrconnector.cfg");
    propertiesComponent.setSystemPropertiesMode(PropertiesComponent.SYSTEM_PROPERTIES_MODE_OVERRIDE);
    try {
      context.addRoutes(new SolrRouter());
      context.start();
      Thread.sleep(1000 * 60 * 15); // 15 min
      context.stop();
    } catch (InterruptedException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }
}
