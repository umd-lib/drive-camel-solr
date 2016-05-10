package edu.umd.lib;

import org.apache.solr.client.solrj.beans.Field;

import com.google.gson.annotations.SerializedName;

public class Document {

    @Field
    @SerializedName("filename")
    private String filename;

    @Field
    @SerializedName("fileType")
    private String fileType;
    
    @Field
    @SerializedName("fileID")
    private String fileID;
    
       
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Document{");
        sb.append("name='").append(filename).append('\'');
        sb.append(", type='").append(fileType).append('\'');
        sb.append(", id='").append(fileID).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
