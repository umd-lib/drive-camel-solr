package edu.umd.lib.process;

import java.io.File;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

/****
 * Create JSON to delete the file from BOX
 *
 * @author rameshb
 *
 */
public class BoxDeleteProcessor implements Processor {

  Map<String, String> config;

  public BoxDeleteProcessor(Map<String, String> config) {
    this.config = config;
  }

  private static Logger log = Logger.getLogger(BoxDeleteProcessor.class);
  private HttpSolrClient solrServer;

  @Override
  public void process(Exchange exchange) throws Exception {

    String file_ID = exchange.getIn().getHeader("item_id", String.class);
    String delete_json = "{ \"delete\" : { \"id\" : \"" + file_ID + "\" } }";

    /****
     * From the delete event we have only the file id. Since the file is already
     * deleted from box it is difficult to form the path. So we connect to Solr
     * to query the filePath for that file and use that file path to delete the
     * file from the local storage. File instance from box need not be deleted
     * so we only need the path of localStorage.
     */
    try {
      solrServer = new HttpSolrClient("http://" + config.get("solrBaseUrl"));
      SolrQuery query = new SolrQuery();
      query.setQuery("id:" + file_ID);
      QueryResponse response = null;
      response = solrServer.query(query);
      SolrDocumentList list = response.getResults();
      if (list.size() > 0) {
        SolrDocument document = list.get(0);
        String localFilePath = (String) document.getFieldValue("localStoragePath");
        File download_file = new File(localFilePath);
        download_file.delete();
        log.info("File with FileId:" + file_ID + ", Deleted from LocalStorage");
      }
    } catch (Exception e) {
      /****
       * Sometimes the file will deleted from local storage but not deleted from
       * Solr. This Error is caught so that the file is deleted from solr for
       * sure. Example: If file was deleted from local storage and then failed
       * to delete from solr. Camel will try again to delete from camel. During
       * that case there will be Exception that local file is not found
       */
      log.info("Failed to find the file from Solr core.");
    }

    exchange.getIn().setBody(delete_json);
    log.info("Creating JSON for deleting box file with ID:" + file_ID);
  }
}
