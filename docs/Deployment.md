# Description

This document describes the basic setup for the Google Drive / Camel tool. Please note for UMD Library staff that there is some archival information on Confluence, which relates to Box integrations but could still be useful. However, this documentation will be migrated and removed in time.

Purpose of this tool is to connect to Google Team Drives and create a local file store and Solr index of all files accessible to the service account.

## Google Drive Service Account

Access the [Google Cloud Platform](https://console.cloud.google.com) and enable the API for the Google Drive API.

Select Credentials and select from the "Create credentials" dropdown, "Help Me Choose".

From the "Add credentials to your project" screen, select:

* Which API are you using? "Google Drive API"
* Where will you be calling the API from? "Other non-UI"
* What data will you be accessing? "Application data"
* Are you using Google App Engine or Google Compute Engine? No, I'm not using them

On the next screen, enter a Service account name (arbitrary), and for a role, select "Project > Owner". Then create a Service account ID (arbitrary) and create a JSON key.

This key will download. Place it somewhere accessible to the tool.

## Run Using Maven

### Prereq: A running Solr instance.

For UMD, the only core currently configured for this tool is libi-core, which is available in Bitbucket. This core is intended for Solr 6.4 but may work with other versions.

### Edit blueprint.xml

Find this at /src/main/resources/OSGI-INF/blueprint/blueprint.xml

Configure the following fields:

* solr.baseUrl = path to Solr, including core. e.g., localhost:8983/solr/core .
* solr.commitWithin = numeral value instructing Solr how soon to commit the changes. e.g., 1000
* drive.routeName = configuration for Camel. e.g., drive
* drive.serviceName = configuration for Camel. e.g., drive-listener
* default.domain = ??? (is this still necessary or a Box relic?). e.g., http://localhost:8080
* camel.maximum_tries = If the tool fails to retrieve a file, how many times should it retry before giving up? e.g., 3
* camel.redelivery_delay = How long to wait between attempts. e.g., 1000
* email.uri = where Camel should find the smtp server. e.g., smtp://localhost:25
* drive.clientsecret = where the tool should find the JSON Google API key. e.g., /path/to/key.json
* drive.app_user_name = ??? (is this still necessary or an earlier Drive relic?)
* drive.max_cache_entries = ??? (is this necessary?)
* drive.properties_file = path to the properties file, which is used to maintain token information. This file must be writable. e.g., /path/to/googledrive.properties
* drive.poll_interval = how often to poll Google for changes. e.g., 120s
* drive.local_storage = where the tool should store the files locally. e.g., /path/to/files/

Note: baseUrl should be renamed to solrUrl. That, or the core should get its own configuration property.

Once this file is configured and Solr is running, you can run the tool using the following command:

> mvn install && mvn -Dmaven.camel.port=8181 camel:run

## Run Using Karaf

Copy edu.umd.lib.drivesolrconnector.cfg into /path/to/karaf/etc/ and configure using the same guidelines described above in blueprint.xml

If you haven't deployed your package into Nexus do this now:

> mvn clean deploy

Access the Karaf client:

> /path/to/karaf/bin/client

In the Karaf shell, add the feature:

> feature:repo-add mvn:edu.umd.lib/drive-camel-solr/0.1-SNAPSHOT/xml/features

And install:

> feature:install drive-camel-solr

Note that if you encounter problems, you can uninstall the tool using:

> feature:uninstall drive-camel-solr
