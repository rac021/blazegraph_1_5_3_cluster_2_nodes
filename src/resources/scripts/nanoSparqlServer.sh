#!/bin/bash

#
# Start a NanoSparqlServer fronting for a bigdata federation.
#
# usage: configFile

source `dirname $0`/bigdataenv

if [ $# -eq 3 ]; then

port=$1
namespace=$2

#rw or ro ( readOnly )
readOnly=$3
#grep -l "web.xml" *.jar
#jar uf bigdata-1.5.0-20160520.jar bigdata-war/src/WEB-INF/web.xml
 
if [ ! -z "$readOnly" ]; then
   
  if [ "$readOnly" = "ro" ]; then
     echo " Starting in ReadOnly mode.. "
     CURRENT_JAR=`grep -l "web.xml" ../lib/*.jar`
     echo "------------------------------"
     echo " updating $CURRENT_JAR .. "
     echo "-----------------------------"
     mkdir -p bigdata-war/src/WEB-INF && \
     # Copy web.xml ( ReadOnly Mode )
     cp overrideWebXml/webWithConfReadOnly.xml bigdata-war/src/WEB-INF/web.xml

  elif [ "$readOnly" = "rw" ]; then
     echo " Starting in Read-Write mode.."
     CURRENT_JAR=`grep -l "web.xml" ../lib/*.jar`
     echo "------------------------------"
     echo " updating $CURRENT_JAR .. "
     echo "-----------------------------"
     mkdir -p bigdata-war/src/WEB-INF && \
     # Copy web.xml ( Read-Write Mode )
     cp overrideWebXml/web.xml  bigdata-war/src/WEB-INF/web.xml
  
  else 
     echo " ReadWrite Mode : ro ( ReadOnly ) - rw ( Read-Write ) "
     exit 3
  fi

fi

# Update Jar defor deployment
  jar uf $CURRENT_JAR bigdata-war/src/WEB-INF/web.xml
  rm -rf bigdata-war
    

# Note: This will cause the NanoSparqlServer instance to ping the lastCommitTime
# on the federation. This provides more efficient query since all queries issued
# by this instance will use efficient read-historical operations. It will also
# prevent the history associated with that commit point from being recycled. The
# read lock is optional and may be any commit time on the database.
readLock=-1

# The default is 16 threads.  This raises the #of threads up to 16 for the 
# cluster.
nthreads=64

echo "port=$port namespace=$namespace config=$BIGDATA_CONFIG"

# Remote Debuging 
export JAVA_OPTS="$JAVA_OPTS -Xdebug -Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n"
export JPDA_ADDRESS=8000
export JPDA_TRANSPORT=dt_socket


java ${JAVA_OPTS} \
   -cp ${CLASSPATH} \
    com.bigdata.rdf.sail.webapp.NanoSparqlServer \
	-nthreads $nthreads \
    $port \
    $namespace \
    ${BIGDATA_CONFIG} ${BIGDATA_CONFIG_OVERRIDES}

#java ${JAVA_OPTS} \
#    -cp ${CLASSPATH} \
#    com.bigdata.rdf.sail.webapp.NanoSparqlServer \
#    -readLock $readLock \
#	-nthreads $nthreads \
#    $port \
#    $namespace \
#    ${BIGDATA_CONFIG} ${BIGDATA_CONFIG_OVERRIDES}

else
    echo "invalid argument : please pass exactly three arguments   "
    echo " arg_1 : port                                            "
    echo " arg_2 : namespacee                                      "
    echo " arg_2 : ro ( readOnly) // rw ( read-write ) mode        "
    
fi

