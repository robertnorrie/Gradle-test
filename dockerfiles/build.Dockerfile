# docker build -t gitlab.lrz.de:5005/i7/partial-exploration --compress - < Dockerfile

FROM openjdk:18-jdk-slim-bullseye

RUN apt-get update && apt-get install -y \
    autoconf \
    automake \
    file \
    g++ \
    gcc \
    libtool \
    make \
    patch \
    subversion \
    swig \
    unzip \
    wget \
    git \
    ssh \
    \
    python3 \
  && rm -rf /var/lib/apt/lists/*