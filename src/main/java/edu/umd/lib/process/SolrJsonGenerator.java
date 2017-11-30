package edu.umd.lib.process;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

public class SolrJsonGenerator {

  private static Logger log = Logger.getLogger(SolrJsonGenerator.class);

  SolrJsonGenerator() {

  }

  public static String deleteJson() {
    return null;
  }

  /*
   * public String generateJsonMessage(Exchange exchange) throws Exception {
   *
   * String action = exchange.getIn().getHeader("action", String.class); String
   * sourceID = exchange.getIn().getHeader("source_id", String.class); String
   * sourceName = exchange.getIn().getHeader("source_name", String.class);
   * String storagePath = exchange.getIn().getHeader("local_path",
   * String.class); String sourceType =
   * exchange.getIn().getHeader("source_type", String.class); String group =
   * exchange.getIn().getHeader("group", String.class); String teamDrive =
   * exchange.getIn().getHeader("teamDrive", String.class); String category =
   * exchange.getIn().getHeader("category", String.class); String creationTime =
   * exchange.getIn().getHeader("creation_time", String.class); String
   * modifiedTime = exchange.getIn().getHeader("modified_time", String.class);
   *
   * String messageBody = null; JSONObject json = new JSONObject();
   * json.put("id", sourceID);
   *
   * switch (action) { case "new_file": messageBody = newFileJson(json, );
   * break; case "rename_file": renameFileJson(); break; case "move_file":
   * moveFileJson(); break; case "update_file": updateFileJson(); break; case
   * "delete_file": deleteEventJson(); break; } return messageBody;
   *
   * }
   */

  public static String newFileJson(Exchange exchange) throws JSONException {
    String id = exchange.getIn().getHeader("source_id", String.class);
    String title = exchange.getIn().getHeader("source_name", String.class);
    String group = exchange.getIn().getHeader("group", String.class);
    String teamDrive = exchange.getIn().getHeader("teamDrive", String.class);
    String category = exchange.getIn().getHeader("category", String.class);
    String creationTime = exchange.getIn().getHeader("creation_time", String.class);
    String modifiedTime = exchange.getIn().getHeader("modified_time", String.class);
    String fileType = exchange.getIn().getHeader("file_type", String.class);
    String fileChecksum = exchange.getIn().getHeader("file_checksum", String.class);
    String fileContent = exchange.getIn().getHeader("file_content", String.class);
    String storagePath = exchange.getIn().getHeader("storage_path", String.class);
    String fileEncoded = exchange.getIn().getHeader("encodedMsg", String.class);
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
    // json.put("fileContent", fileContent);
    json.put("type", fileType);
    json.put("category", category);
    json.put("fileChecksum", fileChecksum);
    // json.put("fileEncoded", fileEncoded);
    json.put("created", creationTime);
    json.put("updated", modifiedTime);

    exchange.getIn().removeHeader("id");
    exchange.getIn().removeHeader("title");
    exchange.getIn().removeHeader("storagePath");
    exchange.getIn().removeHeader("genre");
    exchange.getIn().removeHeader("url");
    exchange.getIn().removeHeader("group");
    exchange.getIn().removeHeader("teamDrive");
    // exchange.getIn().removeHeader("fileContent");
    exchange.getIn().removeHeader("type");
    exchange.getIn().removeHeader("category");
    exchange.getIn().removeHeader("fileChecksum");
    // exchange.getIn().removeHeader("fileEncoded");
    exchange.getIn().removeHeader("created");
    exchange.getIn().removeHeader("updated");

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

  public static String renameFileJson(JSONObject json, Exchange exchange) throws JSONException {

    JSONObject sourceNameObj = new JSONObject();
    json.put("title", sourceNameObj.put("set", exchange.getIn().getHeader("source_name", String.class)));

    JSONObject storagePathObj = new JSONObject();
    json.put("fullPath", storagePathObj.put("set", exchange.getIn().getHeader("full_path", String.class)));

    JSONObject modifiedTimeObj = new JSONObject();
    json.put("updated", modifiedTimeObj.put("set", exchange.getIn().getHeader("modified_time", String.class)));

    return json.toString();
  }

  public static String moveFileJson(JSONObject json, Exchange exchange) throws JSONException {
    JSONObject storagePathObj = new JSONObject();
    json.put("fullPath", storagePathObj.put("set", exchange.getIn().getHeader("full_path", String.class)));

    JSONObject categoryObj = new JSONObject();
    json.put("category", categoryObj.put("set", exchange.getIn().getHeader("category", String.class)));

    return json.toString();
  }

  public static String updateFileJson(JSONObject json, Exchange exchange) throws JSONException {
    // Tika tika = new Tika();
    // File destItem = new File(storagePath);
    // String fileType = tika.detect(destItem);

    // JSONObject fileTypeObj = new JSONObject();
    // json.put("type", fileTypeObj.put("set", fileType));

    // JSONObject fileContentObj = new JSONObject();
    // json.put("fileContent", fileContentObj.put("set",
    // parseToPlainText2(destItem, fileType)));

    JSONObject modifiedTimeObj = new JSONObject();
    json.put("updated", modifiedTimeObj.put("set", exchange.getIn().getHeader("modified_time", String.class)));

    return null;
  }

}
