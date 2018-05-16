# drive-solr

Middleware to map documents from Google Team Drives to Solr using Camel.

For setup information, see: https://confluence.umd.edu/pages/viewpage.action?pageId=559448383

# Working of the tool

This tool provides a means for indexing Google Team Drive documents into Solr and listening for changes to those files. It supports file move, rename, delete, add, and update events as well as bulk-update when adding/deleting files. If a file is deleted, it will also be removed from Solr.

When the tool runs for the first time, it fetches the information for all the files that are stored under the published folder of the Team Drives (It skips files that are Google documents). The tool stores the state of each drive by fetching token values from the Drive API. The token values are stored in a properties file called googledrivetoken.properties. The tool determines if it is being run for the first time by checking the existence of the token properties file.If the file does not exist or if the the file exists but its size is zero, then it performs the bulk operation. Else it starts checking for incremental changes in the Team Drives using the token values stored in the properties file. The tool uses the file information to generates JSON messages for the files and sends them to Solr for indexing.

The tool is configured to run continuously after a configured interval of time. On every run, it checks if a new Team Drive has been added. If it detects that a new Drive has been added, it fetches all the files, generates JSON messages and stores the information in Solr. For existing drives, the tool reads the token values from the properties file, and checks if the Drive has undergone any changes since the stored token state. Once it detects any changes that have occurred inside the published folder, it fetches those changes and determines the type of change (like add, delete, move, update) that has occurred. Based on the type of file event that has occurred, it send the request to the appropriate route. Routes have been defined in the tool for handling each event. Each route maps to a Processor that processes the message and generates the JSON message specific to the event. The JSON message is then sent to the delete/update route for effecting the change in Solr.

# Camel Routes

We have defined 5 routes for each of the file actions (new file, delete file, move file, update file, rename file).
Each route maps to a Processor for processing the changes.

SolrRouter class contains all the routing logic.

RouteId       Processor
NewFile       DriveNewFileProcessor
FileDeleter	  DriveDeleteProcessor
FileRenamer   DriveFileRenameProcessor
FileMover     DriveFileMoveProcessor
FileUpdater   DriveFileContentUpdateProcessor

# Example of new file event
Once a new file event event is detected, the sendNewFileRequest method in the DrivePollEventProcessor class is called. This method generates a map which stores all the information about the file in the form of key-value pairs. It also stores the event that has occurred. This map is passed to the sendActionExchange method where the map values are stored the message header. The message object is then set in the Exchange object. The exchange object is passed to the SolrRouter class. In SolrRouter, the event is read from the exchange message and the route with route Id 'NewFile' is selected for further processing. This route maps to the Processor file 'DriveNewFileProcessor'. This class then calls the SolrJsonGenerator(newFileJson method) to generate the Solr message and passes this message back to the SolrUpdater route which send the message to Solr server.

# Google Drive API

The Google Drive API reference documentation can be found here
https://developers.google.com/drive/v3/reference/

The Drive Changes API helps us to determine that a Drive folder/file has been accessed/modified. However, it does not tell us what change event has occurred. This was a challenge while developing since we needed to figure out ways to detect the type of change event (new file, file rename, delete file, file update, file move). The manageFileEvents() method in the DrivePollEventProcessor file contains the logic for detecting the different file events and processing them accordingly.

For checking new file event,
We are checking if the file Id exists in Solr. We are using the Solr API object (SolrClient and SolrQuery) to generate a Solr query for querying the file id. If the result returns null, then it is a new file event

For checking File update event,
We are comparing the MD5 checksum values of the Drive file and the file Stored in Solr. If the checksum values do not match, then the file content has been updated.

For checking File rename event,
We are comparing the filenames of the stored file and the Drive file.If they do not match, then it is a file rename event

For checking the File move event,
We are checking if the source paths of the files match. If they don't, then the file has been moved.
(The Drive API does not give us the full path of the file. We have written a method getSourcePath() for generating the full Drive path of the file. Since the Drive API gives us information about the immediate parent of a file, we keep looping through the hierarchy by fetching the parents until we reach the root.)



 





