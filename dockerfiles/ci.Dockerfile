# docker build -t gitlab.lrz.de:5005/i7/partial-exploration --compress - < Dockerfile

FROM openjdk:18-slim-bullseye

RUN apt-get update && apt-get install -y python3-yaml python3-tabulate && rm -rf /var/lib/apt/lists/*