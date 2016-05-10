# camel-solr

Middleware to map documents from Box or Drive to Solr using Camel

To Run this Project 

Update the base url of Solr Instance in the Configuration.Proeprties

Create a Schema using the below definion

<schema name="box core" version="1.1">

```XML
<fieldtype name="string"  class="solr.StrField" sortMissingLast="true" omitNorms="true"/>
<fieldType name="long" class="solr.TrieLongField" precisionStep="0" positionIncrementGap="0"/>
<!-- general -->
<field name="id"        type="string"   indexed="true"  stored="true"  multiValued="false" required="true"/>
<field name="type"      type="string"   indexed="true"  stored="true"  multiValued="false" /> 
<field name="name"      type="string"   indexed="true"  stored="true"  multiValued="false" /> 
<field name="core0"     type="string"   indexed="true"  stored="true"  multiValued="false" /> 
<field name="_version_" type="long"     indexed="true"  stored="true"/>

<!-- field to use to determine and enforce document uniqueness. -->
<uniqueKey>id</uniqueKey>

<!-- field for the QueryParser to use when an explicit fieldname is absent -->
<defaultSearchField>name</defaultSearchField>

<!-- SolrQueryParser configuration: defaultOperator="AND|OR" -->
<solrQueryParser defaultOperator="OR"/>
</schema>
```
To build and run the project

    mvn clean compile exec:java -Dexec.mainClass=edu.umd.lib.ServicesApp

For more help see the Apache Camel documentation

    http://camel.apache.org/
