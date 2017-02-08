# camel-solr

Middleware to map documents from Box or Drive to Solr using Camel

To Run this Project 

Update the base url of Solr Instance in the Configuration.Proeprties

Create a Schema using the below definion

```XML
<schema name="box core" version="1.1">

   <fieldtype name="string"  class="solr.StrField" sortMissingLast="true" omitNorms="true"/>
   <fieldType name="long" class="solr.TrieLongField" precisionStep="0" positionIncrementGap="0"/>
   <fieldType name="text" class="solr.TextField" positionIncrementGap="100"/>
   <fieldtype name="binary" class="solr.BinaryField"/>
  <!-- general -->
  <field name="id"        type="string"   indexed="true"  stored="true"  multiValued="false" required="true"/>
  <field name="type"      type="string"   indexed="true"  stored="true"  multiValued="false" /> 
  <field name="name"      type="string"   indexed="true"  stored="true"  multiValued="false" /> 
  <field name="url"       type="string"   indexed="true"  stored="true"  multiValued="true"/>
  <field name="fileContent" type="text" indexed="true" stored="false" multiValued="true"/>
  <field name="_version_" type="long"     indexed="true"  stored="true"/>
  <field name="file" type="binary"     indexed="false"  stored="true"/>
  <field name="genre"      type="string"   indexed="true"  stored="true"  multiValued="false" />
  <!--Binary data type. The data should be sent/retrieved in as Base64 encoded Strings -->
    

 <!-- field to use to determine and enforce document uniqueness. -->
 <uniqueKey>id</uniqueKey>

 <!-- field for the QueryParser to use when an explicit fieldname is absent -->
 <defaultSearchField>name</defaultSearchField>

 <!-- SolrQueryParser configuration: defaultOperator="AND|OR" -->
 <solrQueryParser defaultOperator="OR"/>
</schema>
```
To build this project use

    mvn install

To run this project from within Maven use

    mvn exec:java

For more help see the Apache Camel documentation

    http://camel.apache.org/
