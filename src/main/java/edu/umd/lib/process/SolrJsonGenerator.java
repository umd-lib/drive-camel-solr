package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

public class SolrJsonGenerator {

  private static Logger log = Logger.getLogger(SolrJsonGenerator.class);

  public SolrJsonGenerator() {
  }

  public static String newFileJson(Exchange exchange, String encodedMsg, String fileContent) throws JSONException {
    String id = exchange.getIn().getHeader("source_id", String.class);
    String title = exchange.getIn().getHeader("source_name", String.class);
    String group = exchange.getIn().getHeader("group", String.class);
    String teamDrive = exchange.getIn().getHeader("teamDrive", String.class);
    String category = exchange.getIn().getHeader("category", String.class);
    String sub_category = exchange.getIn().getHeader("sub_category", String.class);
    String creationTime = exchange.getIn().getHeader("creation_time", String.class);
    String modifiedTime = exchange.getIn().getHeader("modified_time", String.class);
    String fileType = exchange.getIn().getHeader("file_type", String.class);
    String fileChecksum = exchange.getIn().getHeader("file_checksum", String.class);
    String storagePath = exchange.getIn().getHeader("storage_path", String.class);

    String genre = "Google Drive";
    String url = "https://drive.google.com/open?id=" + id;

    JSONObject json = new JSONObject();
    json.put("id", id);
    json.put("title", title);
    json.put("storagePath", storagePath);
    json.put("genre", genre);
    json.put("url", url);
    json.put("group", group);
    json.put("teamDrive", teamDrive);
    json.put("fileContent", fileContent);
    json.put("type", fileType);
    json.put("category", category);
    json.put("sub_category", sub_category);
    json.put("fileChecksum", fileChecksum);
    json.put("fileEncoded", encodedMsg);
    json.put("created", creationTime);
    json.put("updated", modifiedTime);

    String messageBody = "[" + json.toString() + "]";
    return messageBody;
  }

  public static String deleteJson(Exchange exchange) throws JSONException {
    String id = exchange.getIn().getHeader("source_id", String.class);
    JSONObject json = new JSONObject();
    json.put("id", id);

    String messageBody = "{'delete':" + json.toString() + "}";
    return messageBody;
  }

  public static String renameFileJson(Exchange exchange) throws JSONException {
    JSONObject json = new JSONObject();

    json.put("id", exchange.getIn().getHeader("source_id", String.class));

    JSONObject sourceNameObj = new JSONObject();
    json.put("title", sourceNameObj.put("set", exchange.getIn().getHeader("source_name", String.class)));

    JSONObject storagePathObj = new JSONObject();
    json.put("storagePath", storagePathObj.put("set", exchange.getIn().getHeader("storage_path", String.class)));

    JSONObject modifiedTimeObj = new JSONObject();
    json.put("updated", modifiedTimeObj.put("set", exchange.getIn().getHeader("modified_time", String.class)));

    String messageBody = "{'add':{'doc':" + json.toString() + "}}";
    return messageBody;
  }

  public static String moveFileJson(Exchange exchange) throws JSONException {
    JSONObject json = new JSONObject();

    json.put("id", exchange.getIn().getHeader("source_id", String.class));

    JSONObject storagePathObj = new JSONObject();
    json.put("storagePath", storagePathObj.put("set", exchange.getIn().getHeader("storage_path", String.class)));

    JSONObject categoryObj = new JSONObject();
    json.put("category", categoryObj.put("set", exchange.getIn().getHeader("category", String.class)));

    JSONObject subCategoryObj = new JSONObject();
    json.put("sub_category", subCategoryObj.put("set", exchange.getIn().getHeader("sub_category", String.class)));

    JSONObject groupObj = new JSONObject();
    json.put("group", groupObj.put("set", exchange.getIn().getHeader("group", String.class)));

    JSONObject teamDriveObj = new JSONObject();
    json.put("teamDrive", teamDriveObj.put("set", exchange.getIn().getHeader("teamDrive", String.class)));

    String messageBody = "{'add':{'doc':" + json.toString() + "}}";
    return messageBody;
  }

  public static String updateFileJson(Exchange exchange, String encodedMsg, String fileContent) throws JSONException {
    JSONObject json = new JSONObject();

    json.put("id", exchange.getIn().getHeader("source_id", String.class));

    JSONObject fileContentObj = new JSONObject();
    json.put("fileContent", fileContentObj.put("set", fileContent));

    JSONObject encodeFileObj = new JSONObject();
    json.put("fileEncoded", encodeFileObj.put("set", encodedMsg));

    JSONObject fileCheckSumObj = new JSONObject();
    json.put("fileChecksum", fileCheckSumObj.put("set", exchange.getIn().getHeader("file_checksum", String.class)));

    JSONObject modifiedTimeObj = new JSONObject();
    json.put("updated", modifiedTimeObj.put("set", exchange.getIn().getHeader("modified_time", String.class)));

    String messageBody = "{'add':{'doc':" + json.toString() + "}}";
    return messageBody;
  }
}
