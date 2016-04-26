#!/bin/bash

docker run -d --net mynet123 --name host_2 \
       --add-host host_0:192.168.56.100    \
       --add-host host_1:192.168.56.101    \ 
       --add-host host_2:192.168.56.102    \
       --ip 192.168.56.102 -p 7777:9999    \
       --expose 9999 -it --entrypoint /bin/bash blazegraph -c "./bigdata start ; while true; do sleep 1000; done "
       
docker run -d --net mynet123 --name host_1 \
       --add-host host_0:192.168.56.100    \
       --add-host host_1:192.168.56.101    \
       --add-host host_2:192.168.56.102    \
       --ip 192.168.56.101 -p 8888:9999    \
       -it --entrypoint /bin/bash blazegraph -c "./bigdata start; while true; do sleep 1000; done "
       
docker run -d --net mynet123 --name host_0 \
       --add-host host_0:192.168.56.100    \
       --add-host host_1:192.168.56.101    \
       --add-host host_2:192.168.56.102    \
       --ip 192.168.56.100 -p 9999:9999    \
       -it --entrypoint /bin/bash blazegraph -c "./bigdata start; while true; do sleep 1000; done "
       
docker exec host_2 ./nanoSparqlServer.sh 9999 ola 



