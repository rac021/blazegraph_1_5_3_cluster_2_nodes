#!/bin/bash

  endPoint=$1 # ex : 'http://localhost:9999/blazegraph/sparql'
  data_dir=$2 # ex : /out/store

  java -server -Xms5g -Xmx5g  -Djetty.port=8080 -jar blazegraph.jar &
  sleep 5  # Waits 5 seconds.

  cd $data_dir
  rm "bulk"

  for i in `ls -a *.*`
    do
    echo "---------------------"
    echo "Upload file : $i" 
    echo "---------------------"
    curl -D- -H 'Content-Type: text/turtle' --upload-file $i -X POST $endPoint -O
    echo "---------------------"
  done

#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode                                         \
#'query=SELECT ( COUNT(?s) AS ?count ) where { ?s ?p ?o }  ' -H 'Accept:text/tab-separated-values'

#curl "http://192.168.56.200:8080/solr/store/sparql" --data-urlencode \
#"q=SELECT (COUNT(?s) AS ?total) WHERE { ?s ?p ?o } " -H "Accept: text/csv"

#curl -X POST http://1492.168.56.200:8080/blazegraph/sparql --data-urlencode \
#'query=SELECT ?s ?p ?o WHERE { ?s ?p ?o . } ' -H 'Accept:text/tab-separated-values'

#CSV
#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode                                         \
#'query=PREFIX : <http://www.anaee/fr/soere/ola#> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>         \
#PREFIX oboe-core: <http://ecoinformatics.org/oboe/oboe.1.0/oboe-core.owl#> SELECT DISTINCT ?uriVariableSynthesis   \
#?variableName ?year  ?count   WHERE { ?uriVariableSynthesis a oboe-core:Observation ;                              \
#oboe-core:ofEntity :VariableSynthesis ;  oboe-core:hasMeasurement ?measurvariableName , ?measuryear,               \
#?measurcount ; oboe-core:hasContext ?dbField . ?measuryear a oboe-core:Measurement ;                               \
#oboe-core:ofCharacteristic :Year ; oboe-core:hasValue ?year . ?measurcount a oboe-core:Measurement ;               \
#oboe-core:ofCharacteristic :Count; oboe-core:usesStandard :Number ; oboe-core:hasValue ?count .                    \
#?measurvariableName a oboe-core:Measurement ;  oboe-core:usesStandard :VariableNamingStandard ;                    \
#oboe-core:ofCharacteristic :Name ; oboe-core:hasValue ?variableName . ?dbField a oboe-core:Observation ;           \
#oboe-core:ofEntity :DataBaseField ; oboe-core:hasMeasurement ?measurVarName ; oboe-core:hasContext                 \
#?soluteObservation . } ORDER BY ?uriVariableSynthesis ' -H 'Accept:text/tab-separated-values'

#turtle
#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode 'query=PREFIX :                             \
#<http://www.anaee/fr/soere/ola#> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>                              \
#PREFIX oboe-core: <http://ecoinformatics.org/oboe/oboe.1.0/oboe-core.owl#> SELECT DISTINCT                              \
#?uriVariableSynthesis ?variableName ?year  ?count   WHERE { ?uriVariableSynthesis a oboe-core:Observation ;             \
#oboe-core:ofEntity :VariableSynthesis ;  oboe-core:hasMeasurement ?measurvariableName , ?measuryear,                    \
#?measurcount ; oboe-core:hasContext ?dbField . ?measuryear a oboe-core:Measurement ; oboe-core:ofCharacteristic :Year ; \
#oboe-core:hasValue ?year . ?measurcount a oboe-core:Measurement ; oboe-core:ofCharacteristic :Count;                    \
#oboe-core:usesStandard :Number ; oboe-core:hasValue ?count . ?measurvariableName a oboe-core:Measurement ;              \
#oboe-core:usesStandard :VariableNamingStandard ; oboe-core:ofCharacteristic :Name ; oboe-core:hasValue ?variableName.   \
#?dbField a oboe-core:Observation ; oboe-core:ofEntity :DataBaseField ; oboe-core:hasMeasurement ?measurVarName ;        \
#oboe-core:hasContext ?soluteObservation . } ORDER BY ?uriVariableSynthesis ' -H 'Accept:application/x-turtle'

# JSON
#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode 'query=PREFIX :                                  \ 
#<http://www.anaee/fr/soere/ola#> PREFIX rdf: <http://www.w3.org/1999/0222-rdf-syntax-ns#> PREFIX oboe-core:                 \
#<http://ecoinformatics.org/oboe/oboe.1.0/oboe-core.owl#> SELECT DISTINCT ?uriVariableSynthesis ?variableName                \
#?year  ?count   WHERE { ?uriVariableSynthesis a oboe-core:Observation ; oboe-core:ofEntity :VariableSynthesis               \
# ; oboe-core:hasMeasurement ?measurvariableName , ?measuryear, ?measurcount ; oboe-core:hasContext ?dbField .               \
#?measuryear a oboe-core:Measurement ; oboe-core:ofCharacteristic :Year ; oboe-core:hasValue ?year .                         \
#?measurcount \a oboe-core:Measurement ; oboe-core:ofCharacteristic :Count; oboe-core:usesStandard :Number ;                 \
#oboe-core:hasValue ?count . ?measurvariableName a oboe-core:Measurement ;  oboe-core:usesStandard :VariableNamingStandard ; \
#oboe-core:ofCharacteristic :Name ; oboe-core:hasValue ?variableName . ?dbField a oboe-core:Observation ;                    \
#oboe-core:ofEntity :DataBaseField ; oboe-core:hasMeasurement ?measurVarName ; oboe-core:hasContext ?soluteObservation . }   \
#ORDER BY ?uriVariableSynthesis ' -H 'Accept:application/json'

#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode 'query=PREFIX :                 \
#<http://www.anaee/fr/soere/ola#> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>                 \
#PREFIX oboe-core: <http://ecoinformatics.org/oboe/oboe.1.0/oboe-core.owl#> SELECT                          \
#?uriVariableSynthesis ?variableName ?year  ?count   WHERE { ?uriVariableSynthesis a                        \
#oboe-core:Observation ; oboe-core:ofEntity :VariableSynthesis ;  oboe-core:hasMeasurement                  \
#?measurvariableName , ?measuryear, ?measurcount ; oboe-core:hasContext ?dbField . ?measuryear              \
#a oboe-core:Measurement ; oboe-core:ofCharacteristic :Year ; oboe-core:hasValue ?year .                    \
#?measurcount a oboe-core:Measurement ; oboe-core:ofCharacteristic :Count; oboe-core:usesStandard           \
#:Number ; oboe-core:hasValue ?count . ?measurvariableName a oboe-core:Measurement ;                        \
#oboe-core:usesStandard :VariableNamingStandard ; oboe-core:ofCharacteristic :Name ;                        \
#oboe-core:hasValue ?variableName . ?dbField a oboe-core:Observation ; oboe-core:ofEntity :DataBaseField ;  \
#oboe-core:hasMeasurement ?measurVarName ; oboe-core:hasContext ?soluteObservation . } '                    \
#-H 'Accept:application/rdf+xml' | gzip > ola_01.nt

#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode 'query=PREFIX rdfs:             \
#<http://www.w3.org/2000/01/rdf-schema#> PREFIX : <http://www.anaee/fr/soere/ola#> PREFIX oboe-core:        \
#<http://ecoinformatics.org/oboe/oboe.1.0/oboe-core.owl#> SELECT ?uriVariableSynthesis ?measu ?value        \
# { ?uriVariableSynthesis a oboe-core:Observation ; oboe-core:ofEntity :VariableSynthesis ;                  \
#oboe-core:hasMeasurement ?measu . ?measu oboe-core:hasValue ?value . Filter ( regex( ?value, "ph", "i"))}' \
#-H 'Accept:application/rdf+xml' | gzip > ola_01.nt


#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode 'query=SELECT *            \
#WHERE { hint:Query hint:analytic "true" . hint:Query hint:constructDistinctSPO "false" . ?s ?p ?o }   \
#limit 1' -H 'Accept:application/rdf+xml' | gzip > ola_02.nt

#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode 'query=CONSTRUCT           \
#WHERE { hint:Query hint:analytic "true" . hint:Query hint:constructDistinctSPO "false" . ?s ?p ?o }'  \
#-H 'Accept:application/rdf+xml' | gzip > ola.nt

#curl -X POST http://192.168.56.200:147.99.222.28/blazegraph/sparql?GETSTMTS&includeInferred=false

#curl 147.99.222.28:8080/blazegraph/namespace

#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode 'query=CONSTRUCT          \
#WHERE { hint:Query hint:analytic "true" . hint:Query hint:constructDistinctSPO "false" . ?s ?p ?o }' \
#-H 'Accept:application/rdf+xml' | gzip > ola.nt

#curl --header 'Accept: text/plain' http://147.99.222.28:8080/blazegraph/namespace/kb/properties

#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode "query=SELECT ?o { ?s ?p ?o } \
#LIMIT 1" -H "Accet:application/sparql-results+json"

#curl -X POST http://192.168.56.200:8080/blazegraph/sparql --data-urlencode "query=SELECT ?o \ 
# { ?s ?p ?o } LIMIT 1"  -H 'Accept:application/rdf+xml'
