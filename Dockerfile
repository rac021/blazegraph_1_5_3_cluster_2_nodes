#
# Base image consisting of Debian, Java 7, Ant, Maven and Git
#

FROM java:7

MAINTAINER ecoinfo <ecoinfo@inra.fr>

LABEL org.ceylon-lang.dockerfile.description="Base image consisting of Debian, Java 7, Ant, Maven and Git" \
      org.ceylon-lang.dockerfile.vendor="RedHat" \
      org.ceylon-lang.dockerfile.version="1.1"

USER root

RUN echo " ** Install tools "  && \
    echo " "  && \
    echo "root:root_pass" | chpasswd && \ 
    adduser --disabled-password --gecos '' anaee && \
    echo "anaee:anaee_pass" | chpasswd && \       
    apt-get -y update && \
    apt-get install -y net-tools && \
    apt-get install -y git ant maven docbook2x sysstat sudo patch && \
    apt-get install vim -y && \
    apt-get install procmail && \    
    apt-get clean  && \   
    cd /  && \
    mkdir nas && \
    mkdir data && \
    chown -R anaee:anaee nas && \
    chown -R anaee:anaee data
    
    # to use sudo 
    #echo "import pty; pty.spawn('/bin/bash')" > /tmp/asdf.py && \
    #python /tmp/asdf.py && \
    #echo "anaee	ALL=(ALL:ALL) ALL" >> /etc/sudoers -y
    
USER anaee

RUN echo " ** Install Blazegraph_1_5_3 "  && \
    cd /tmp && \
    git clone https://github.com/rac021/blazegraph_1_5_3_cluster_2_nodes && \    
    cd /tmp/blazegraph_1_5_3_cluster_2_nodes && \
    ant install
    
WORKDIR /nas/bigdata/benchmark/bin

ENTRYPOINT ./bigdata
