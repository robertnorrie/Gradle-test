FROM gradle:7.6-jdk11

ENV JQ_RELEASE_VERSION 1.6

RUN apt-get install -y curl \
  && curl -sL https://deb.nodesource.com/setup_20.x | bash - \
  && apt-get install -y nodejs \
  && curl -L https://www.npmjs.com/install.sh | sh

RUN wget https://github.com/stedolan/jq/releases/download/jq-${JQ_RELEASE_VERSION}/jq-linux64 && mv jq-linux64 jq && chmod +x jq && cp jq /usr/bin/jq
