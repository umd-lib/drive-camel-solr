package edu.umd.lib;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.io.Reader;
import java.lang.reflect.Type;
import java.util.Collection;

public class JsonToProductProcessor implements Processor
{
    public void process(Exchange exchange) throws Exception
    {
        Reader reader = exchange.getIn().getBody(Reader.class);
        Gson gson = new Gson();
        Type collectionType = new TypeToken<Collection<Document>>(){}.getType();
        Collection<Document> Documents = gson.fromJson(reader, collectionType);
        exchange.getIn().setBody(Documents);
    }
}
