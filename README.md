
## Preconfigured Blazegraph Cluster with 2 DataServices.

No need to download the entire project ( Docker folder is sufficient )

Steps :

    - cd blazegraph_1_5_3_cluster_2_nodes/Docker
    
    - docker build -t blazegraph .
    
    - ./script.sh
    
    - ./scriptLoader.sh "http://192.168.56.102:8080/blazegraph/sparql" /directoryFile
    
  
#### Docker Hub : https://hub.docker.com/r/rac021/blz_cluster_2_nodes
#### And play with docker for test : https://labs.play-with-docker.com


# Welcome to Blazegraph

Blazegraph™ is our ultra high-performance graph database supporting Blueprints and RDF/SPARQL APIs. It supports up to 50 Billion edges on a single machine and has a High Availability and Scale-out architecture. It is in production use for Fortune 500 customers such as EMC, Autodesk, and many others.  It powers the Wikimedia Foundation's Wiki Data Query Service.  See the latest [Feature Matrix](http://www.blazegraph.com/blazegraph#FeatureMatrix).


[Sign up](http://eepurl.com/VLpUj) to get the latest news on Blazegraph.

Please also visit us at our: [website](http://www.blazegraph.com), [wiki](https://wiki.blazegraph.com), and [blog](https://wiki.blazegraph.com/).

Find an issue?   Having a problem?  See [JIRA](https://jira.blazegraph.com) or purchase [Support](https://www.blazegraph.com/buy).
